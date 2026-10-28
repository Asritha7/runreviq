"""Evaluate Java HTTP retrieval against labeled targets in an isolated local runtime."""
import argparse
from contextlib import contextmanager
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import uuid

from psycopg import sql, Error as DatabaseError
from runreviq.database import ROOT, bootstrap, connect, credentials, ingest, prepare
from runreviq.evaluate_search import aggregate, load_cases, passage_key, relevance, suite_data, validate_cases
from runreviq.ranking import http_terms
from runreviq.selector import select

JAR = ROOT / 'backend/target/runreviq-backend-0.1.0-SNAPSHOT.jar'
METHOD = 'postgres_english_reviewed_v5'
STATUSES = {'selected', 'conflict', 'needs_context_or_review', 'insufficient_evidence'}


class EvaluationFailure(Exception):
    """Fixed safe diagnostics; never echo HTTP error bodies or private configuration."""


def private_json(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as stream:
        json.dump(value, stream)


def http_json(port, path, *, body=None, token=None):
    # No arbitrary host/URL, redirects, proxy environment or remote credentials.
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=20)
    try:
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = 'Bearer ' + token
        conn.request('POST' if body is not None else 'GET', path,
                     None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8'), headers)
        response = conn.getresponse()
        data = response.read(4_000_001)
        if response.status != 200 or len(data) > 4_000_000:
            raise EvaluationFailure('Java HTTP request failed or exceeded the response limit')
        value = json.loads(data)
        if not isinstance(value, dict):
            raise EvaluationFailure('Java returned an invalid response')
        return value
    except (OSError, http.client.HTTPException, ValueError):
        raise EvaluationFailure('Java HTTP request failed or returned invalid JSON') from None
    finally:
        conn.close()


@contextmanager
def java_runtime(dbname, directory, jar=JAR):
    if not re.fullmatch(r'opslens_java_eval_[0-9a-f]{32}', dbname):
        raise EvaluationFailure('Refusing a non-evaluation database')
    passwords = credentials()
    tokens = {role: secrets.token_urlsafe(40) for role in ('platform', 'security', 'intern')}
    private_json(directory / 'readers.json', dict(database=dbname, reader_passwords={r: passwords[f'READER_{r.upper()}_PASSWORD'] for r in tokens}))
    private_json(directory / 'auth.json', [dict(subject='evaluation-' + r, role=r, token=t) for r, t in tokens.items()])
    # Only the two private file paths reach the JVM; no admin credential or labels.
    command = ['java', '-jar', str(jar), '--server.address=127.0.0.1', '--server.port=0',
               '--runreviq.auth.mode=demo', '--runreviq.auth.config-path=' + str(directory / 'auth.json'),
               '--runreviq.database.enabled=true', '--runreviq.database.config-path=' + str(directory / 'readers.json'),
               '--runreviq.drafts.enabled=false', '--runreviq.workflows.enabled=false']
    log_path = directory / 'java.log'
    process = None
    try:
        with log_path.open('w') as log:
            log_path.chmod(0o600)
            process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise EvaluationFailure('Isolated Java runtime exited before startup')
                match = re.search(r'Tomcat started on port ([0-9]+) ', log_path.read_text())
                if match:
                    port = int(match.group(1))
                    health = http_json(port, '/health')
                    if health.get('runtime') != 'java' or health.get('operational_api') != 'evidence-reads':
                        raise EvaluationFailure('Isolated runtime health check failed')
                    yield port, tokens
                    return
                time.sleep(.1)
            raise EvaluationFailure('Isolated Java startup timed out')
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


@contextmanager
def evaluation_database():
    name = 'opslens_java_eval_' + uuid.uuid4().hex
    created = False
    try:
        with connect(dbname='postgres', autocommit=True) as conn:
            conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
            created = True
        bootstrap(name)
        yield name
    finally:
        if created:
            assert re.fullmatch(r'opslens_java_eval_[0-9a-f]{32}', name)
            with connect(dbname='postgres', autocommit=True) as conn:
                conn.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(name)))


def verify_corpus(dbname, records):
    """Bind the report fingerprint to the sources actually ingested, not a later edit."""
    expected = sorted((doc['id'], doc['revision'], metadata, content, checksum, [] if doc['status'] == 'revoked' else sorted(doc['allowed_roles']))
                      for doc, metadata, content, checksum in records)
    with connect(dbname=dbname) as conn:
        actual = conn.execute('''SELECT d.id, d.current_revision, v.metadata, v.content, v.checksum, d.allowed_roles
            FROM opslens.documents d JOIN opslens.document_versions v
            ON v.document_id=d.id AND v.revision=d.current_revision ORDER BY d.id''').fetchall()
    actual = [(identifier, revision, metadata, content, checksum, sorted(roles)) for identifier, revision, metadata, content, checksum, roles in actual]
    if actual != expected:
        raise EvaluationFailure('Source corpus changed after validation')


def authorized_sources(dbname, role):
    with connect(role=role, dbname=dbname) as conn:
        conn.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ, READ ONLY')
        rows = conn.execute('''SELECT v.metadata, v.content, d.allowed_roles
            FROM opslens.documents d JOIN opslens.document_versions v
              ON v.document_id=d.id AND v.revision=d.current_revision
            WHERE NOT EXISTS (SELECT 1 FROM opslens.document_withdrawals w
              WHERE w.document_id=d.id AND w.revision=d.current_revision)''').fetchall()
    return {metadata['id']: (dict(metadata, allowed_roles=roles), content) for metadata, content, roles in rows}


def assess_response(case, response, sources):
    """Validate structured references and exact evidence before scoring labels.

    Invalid responses are redacted from reports. This does not establish semantic
    entailment or detect every possible disclosure in arbitrary explanatory prose.
    """
    problems = []
    def require(ok, reason):
        if not ok and reason not in problems:
            problems.append(reason)
    def reference(identifier, revision=None):
        found = sources.get(identifier) if isinstance(identifier, str) else None
        require(found is not None, 'unauthorized_or_missing_reference')
        if found is not None and revision is not None:
            require(type(revision) is int and revision == found[0]['revision'], 'stale_or_invalid_revision')
        return found
    def walk(node):
        if isinstance(node, dict):
            source = reference(node['document_id'], node.get('revision')) if 'document_id' in node else None
            if 'quote' in node:
                require(source is not None, 'citation_without_source')
                if source:
                    start, end = node.get('start_line'), node.get('end_line')
                    lines = source[1].splitlines()
                    bounded = type(start) is int and type(end) is int and 1 <= start <= end <= len(lines)
                    require(bounded, 'invalid_citation_range')
                    require(bounded and '\n'.join(lines[start-1:end]) == node.get('quote')
                            and hashlib.sha256(source[1].encode()).hexdigest() == node.get('source_sha256'), 'invalid_citation')
            for value in node.values():
                walk(value)
        elif isinstance(node, list):
            for value in node:
                walk(value)
    try:
        require(isinstance(response.get('status'), str) and response['status'] in STATUSES, 'invalid_status')
        require(response.get('retrieval_method') == METHOD, 'unexpected_retrieval_method')
        hits, recommended = response['passages'], response['recommended_documents']
        require(isinstance(hits, list) and len(hits) <= 5, 'invalid_passage_count')
        require(isinstance(recommended, list), 'invalid_recommendations')
        for key in ('applicable_documents', 'uncertain_documents', 'recommended_documents'):
            for identifier in response[key]:
                reference(identifier)
        for pair in response['conflicts']:
            for identifier in pair:
                reference(identifier)
        walk(response)
        keys = [passage_key(p) for p in hits]
        require(len(keys) == len(set(keys)), 'duplicate_passages')
        require(recommended == list(dict.fromkeys(p['document_id'] for p in hits)), 'recommendation_mismatch')
        require((response.get('status') == 'selected') == bool(hits), 'status_passage_mismatch')
        context = dict(case['context'])
        codes = http_terms(case['question'])
        if context.get('http_status') is None and len(codes) == 1:
            context['http_status'] = int(next(iter(codes)))
        for hit in hits:
            source = reference(hit['document_id'], hit['revision'])
            require(all(k in hit for k in ('quote','source_sha256','start_line','end_line')), 'missing_citation')
            if source:
                require(select([source[0]], **context)['status'] == 'selected', 'inapplicable_passage')
    except (KeyError, TypeError, ValueError, AttributeError):
        require(False, 'malformed_response')
    safe_hits = response.get('passages', []) if not problems else []
    metrics = relevance(case['expected_passages'], safe_hits)
    withheld = case['expected_status'] != 'selected'
    status = response.get('status') if isinstance(response.get('status'), str) and response['status'] in STATUSES else 'invalid'
    outcome = status == case['expected_status'] and (not safe_hits if withheld else metrics['recall'] == 1)
    relevant_count = len({passage_key(p) for p in safe_hits} & {passage_key(p) for p in case['expected_passages']})
    return dict(id=case['id'], category=case.get('category', 'external'), question=case['question'],
                passed=not problems and outcome, expected_status=case['expected_status'], actual_status=status,
                withheld=withheld, integrity_ok=not problems, integrity_errors=problems,
                citation_checks=[True for _ in safe_hits] if not problems else [False],
                retrieved=safe_hits, precision_fixed_at_5=relevant_count / 5,
                exact_evidence_set=not problems and outcome and not metrics['irrelevant'], **metrics)


def summarize(results):
    report = aggregate(results)
    positives = [r for r in results if not r['withheld']]
    report.update(precision_fixed_at_5=statistics.mean(r['precision_fixed_at_5'] for r in positives) if positives else None,
                  exact_evidence_set_passed=sum(r['exact_evidence_set'] for r in results),
                  integrity_failures=sum(not r['integrity_ok'] for r in results))
    return report


def evaluate_http(port, tokens, dbname, cases, suite, fingerprint, external=False):
    results = []
    for case in cases:
        context = dict(case['context'])
        role = context.pop('role')
        started = time.perf_counter()
        response = http_json(port, '/v1/search', body=dict(context, question=case['question']), token=tokens[role])
        elapsed = (time.perf_counter() - started) * 1000
        result = assess_response(case, response, authorized_sources(dbname, role))
        result['latency_ms'] = round(elapsed, 2)
        results.append(result)
    return dict(**summarize(results), suite=suite, runtime='java_http', dataset_sha256=fingerprint,
                retrieval_method=METHOD, question_source='external' if external else 'built_in',
                independence='not_verified' if external else 'same_author_development',
                categories={category: summarize([r for r in results if r['category'] == category]) for category in sorted({r['category'] for r in results})},
                results=results)


def exit_code(report, report_only=False):
    if report['integrity_failures']:
        return 1
    return 0 if report_only or report['exact_evidence_set_passed'] == report['cases'] else 1


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=['regression','realistic','validation','paraphrase'], default='realistic')
    parser.add_argument('--questions', type=Path, help='External labels for the chosen corpus; never sent to the server')
    parser.add_argument('--validate-only', action='store_true', help='Validate and fingerprint without Java or PostgreSQL')
    parser.add_argument('--report-only', action='store_true', help='Allow relevance misses; integrity errors still fail')
    args = parser.parse_args(argv)
    try:
        docs, root, _ = suite_data(args.suite)
        cases = load_cases(args.suite, args.questions)
        records = prepare(docs, root=root)
        validate_cases(cases, records)
        for case in cases:
            codes = http_terms(case['question'])
            explicit = case['context'].get('http_status')
            if explicit is not None and codes and codes != {str(explicit)}:
                raise ValueError('Conflicting HTTP context')
        fingerprint = hashlib.sha256(json.dumps(dict(cases=cases, records=records), sort_keys=True).encode()).hexdigest()
    except (ValueError, OSError, TypeError, KeyError):
        parser.error('Invalid evaluation input; no runtime or database was created')
    if args.validate_only:
        print(json.dumps(dict(validated=True, cases=len(cases), dataset_sha256=fingerprint)))
        return 0
    try:
        with tempfile.TemporaryDirectory(prefix='runreviq-java-evaluation-') as folder:
            directory = Path(folder)
            with (directory/'build.log').open('w') as log:
                build = subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','-ntp','-DskipTests','package'], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=180)
            if build.returncode:
                raise EvaluationFailure('Java build failed; run Maven verify to inspect build diagnostics')
            jar = directory/'backend.jar'
            shutil.copyfile(JAR, jar)
            jar.chmod(0o600)
            with jar.open('rb') as stream:
                jar_hash = hashlib.file_digest(stream, 'sha256').hexdigest()
            with evaluation_database() as name:
                ingest(docs, root=root, dbname=name)
                verify_corpus(name, records)
                with java_runtime(name, directory, jar) as (port, tokens):
                    report = evaluate_http(port, tokens, name, cases, args.suite, fingerprint, bool(args.questions))
            report['quality_gate'] = 'passed' if exit_code(report) == 0 else 'failed'
            report['java_jar_sha256'] = jar_hash
            report['metric_notes'] = {
                'quality_gate': 'Strict exact labeled evidence set and expected status for every case; unlabeled passages count as extra under these labels.',
                'passed': 'Correct status and all labeled passages retrieved; extra irrelevant passages do not fail this outcome.',
                'exact_evidence_set_passed': 'Correct outcome with no missing or extra passages.',
                'precision_at_returned_5': 'Mean positive-case labeled hits divided by returned passages; empty returns score zero.',
                'precision_fixed_at_5': 'Mean positive-case distinct labeled hits divided by five, including empty slots.',
                'integrity': 'Structured reference authorization, current sources, applicability and exact quotes/hashes; not semantic entailment.',
                'latency': 'Local HTTP request time, excluding build/startup and evaluator integrity reads; not a load benchmark.',
            }
            print(json.dumps(report, indent=2))
            return exit_code(report, args.report_only)
    except (EvaluationFailure, OSError, subprocess.TimeoutExpired, DatabaseError):
        print('Java evaluation failed; no quality score is reported. Check the local build/database setup.', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
