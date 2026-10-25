"""Offline retrieval evaluation; CLI writes only to a disposable database."""
import argparse
import hashlib
import json
import math
import statistics
import time
import uuid

from psycopg import sql
from runreviq.database import connect, bootstrap, ingest, prepare
from runreviq.selector import ROOT, load_documents
from runreviq.search import search, passages, RETRIEVAL_METHOD

BENCHMARK = ROOT / 'datasets/benchmark'


def suite_data(suite):
    if suite == 'regression':
        return load_documents(), ROOT, ROOT / 'datasets/evaluation/questions.json'
    if suite in {'realistic', 'validation'}:
        filename = 'questions.json' if suite == 'realistic' else 'validation-questions.json'
        return json.loads((BENCHMARK / 'catalog.json').read_text()), BENCHMARK, BENCHMARK / filename
    raise ValueError('Unknown evaluation suite')


def passage_key(passage):
    return tuple(passage[k] for k in ('document_id', 'revision', 'start_line', 'end_line'))


def validate_cases(cases, records):
    """Reject broken labels rather than counting them as retrieval failures."""
    available = {}
    for doc, _, content, _ in records:
        for passage in passages(content):
            available[(doc['id'], doc['revision'], passage['start_line'], passage['end_line'])] = doc
    ids = set()
    for case in cases:
        if case['id'] in ids:
            raise ValueError('Duplicate evaluation case ID')
        ids.add(case['id'])
        expected = case['expected_passages']
        if case['expected_status'] not in {'selected', 'insufficient_evidence', 'conflict', 'needs_context_or_review'}:
            raise ValueError('Unknown expected status')
        if bool(expected) != (case['expected_status'] == 'selected'):
            raise ValueError('Only positive cases must have passage labels')
        keys = [passage_key(p) for p in expected]
        if len(keys) != len(set(keys)):
            raise ValueError('Duplicate passage label')
        for key in keys:
            if key not in available:
                raise ValueError('Label does not identify an exact source passage')
            if case['context']['role'] not in available[key]['allowed_roles']:
                raise ValueError('Positive label is inaccessible to the test role')


def relevance(expected, retrieved):
    """Exact labeled passage relevance; missing results have precision zero."""
    expected_keys = {passage_key(p) for p in expected}
    hit_keys = set()
    first = None
    for index, passage in enumerate(retrieved, 1):
        key = passage_key(passage)
        if key in expected_keys:
            hit_keys.add(key)
            if first is None:
                first = index
    return dict(recall=len(hit_keys)/len(expected_keys) if expected_keys else None,
                precision=len(hit_keys)/len(retrieved) if retrieved else 0.0,
                rank=first, missing=[p for p in expected if passage_key(p) not in hit_keys],
                irrelevant=[p for p in retrieved if passage_key(p) not in expected_keys])


def aggregate(results):
    positive = [r for r in results if not r['withheld']]
    refusals = [r for r in results if r['withheld']]
    def mean(values):
        return statistics.mean(values) if values else None
    latencies = sorted(r['latency_ms'] for r in results)
    return dict(cases=len(results), passed=sum(r['passed'] for r in results),
                recall_at_5=mean([r['recall'] for r in positive]),
                hit_rate_at_5=mean([int(r['rank'] is not None) for r in positive]),
                precision_at_returned_5=mean([r['precision'] for r in positive]),
                mean_reciprocal_rank=mean([1/r['rank'] if r['rank'] else 0 for r in positive]),
                refusal_accuracy=mean([int(r['passed']) for r in refusals]),
                false_positive_rate=mean([int(bool(r['retrieved'])) for r in refusals]),
                citation_accuracy=mean([int(ok) for r in results for ok in r['citation_checks']]),
                median_latency_ms=statistics.median(latencies) if latencies else None,
                p95_latency_ms=latencies[math.ceil(.95*len(latencies))-1] if latencies else None)


def evaluate(dbname, suite='regression'):
    documents, root, question_path = suite_data(suite)
    cases = json.loads(question_path.read_text())
    records = prepare(documents, root=root)
    validate_cases(cases, records)
    fingerprint = hashlib.sha256(json.dumps({'cases': cases, 'records': records}, sort_keys=True).encode()).hexdigest()
    results = []
    for case in cases:
        started = time.perf_counter()
        result = search(dbname=dbname, question=case['question'], **case['context'])
        elapsed = (time.perf_counter() - started) * 1000
        retrieved = result['passages']
        metrics = relevance(case['expected_passages'], retrieved)
        citation_checks = []
        current_checks = []
        with connect(role=case['context']['role'], dbname=dbname) as conn:
            for hit in retrieved:
                row = conn.execute('''SELECT v.content, d.current_revision,
                    EXISTS(SELECT 1 FROM opslens.document_withdrawals w WHERE w.document_id=v.document_id AND w.revision=v.revision)
                    FROM opslens.document_versions v JOIN opslens.documents d ON d.id=v.document_id
                    WHERE v.document_id=%s AND v.revision=%s''', (hit['document_id'], hit['revision'])).fetchone()
                citation_checks.append(bool(row and '\n'.join(row[0].splitlines()[hit['start_line']-1:hit['end_line']]) == hit['quote']
                                            and hashlib.sha256(row[0].encode()).hexdigest() == hit['source_sha256']))
                current_checks.append(bool(row and row[1] == hit['revision'] and not row[2]))
        status_ok = result['status'] == case['expected_status']
        withheld = case['expected_status'] != 'selected'
        integrity_ok = all(citation_checks) and all(current_checks)
        passed = status_ok and integrity_ok and (metrics['recall'] == 1 if not withheld else not retrieved and not result['recommended_documents'])
        results.append(dict(id=case['id'], category=case.get('category', 'regression'), question=case['question'],
                            passed=passed, expected_status=case['expected_status'], actual_status=result['status'],
                            integrity_ok=integrity_ok, citation_checks=citation_checks, withheld=withheld,
                            latency_ms=round(elapsed,2), retrieved=retrieved, **metrics))
    return dict(**aggregate(results), suite=suite, dataset_sha256=fingerprint,
                retrieval_method=RETRIEVAL_METHOD,
                categories={category: aggregate([r for r in results if r['category']==category])
                            for category in sorted({r['category'] for r in results})}, results=results)


def report_exit_code(report, report_only=False):
    if not all(r['integrity_ok'] for r in report['results']):
        return 1
    return 0 if report_only or report['passed'] == report['cases'] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', choices=['regression', 'realistic', 'validation'], default='regression')
    parser.add_argument('--report-only', action='store_true', help='Report relevance failures without failing the command; integrity failures still fail')
    args = parser.parse_args()
    documents, root, _ = suite_data(args.suite)
    dbname = 'opslens_test_search_' + uuid.uuid4().hex
    with connect(dbname='postgres', autocommit=True) as conn:
        conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(dbname)))
    try:
        bootstrap(dbname)
        ingest(documents, dbname=dbname, root=root)
        report = evaluate(dbname, args.suite)
        print(json.dumps(report, indent=2))
        return report_exit_code(report, args.report_only)
    finally:
        with connect(dbname='postgres', autocommit=True) as conn:
            conn.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(dbname)))


if __name__ == '__main__':
    raise SystemExit(main())
