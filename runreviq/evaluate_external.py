"""Evaluate the user-supplied synthetic scenarios in isolated disposable databases."""
import copy
import hashlib
import json
import uuid

from psycopg import sql

from runreviq.database import (bootstrap, connect, ingest, prepare,
                              load_authorized_documents, load_authorized_revision)
from runreviq.search import search, RETRIEVAL_METHOD
from runreviq.selector import ROOT, select

FIXTURE = ROOT / 'datasets/external-validation'


def load_fixture():
    scenarios = json.loads((FIXTURE / 'scenarios.json').read_text())
    prepared = []
    for scenario in scenarios:
        root = (FIXTURE / scenario['folder']).resolve()
        if not root.is_relative_to(FIXTURE.resolve()):
            raise ValueError('Invalid scenario folder')
        batches = json.loads((root / 'catalog-batches.json').read_text())
        records = [prepare(batch, root=root) for batch in batches]
        prepared.append((scenario, root, batches, records))
    return prepared


def verify_quotes(result, role, dbname):
    """Check returned passages and conflict quotes through the same reader role."""
    quotes = list(result['passages'])
    for detail in result['conflict_details']:
        for document in detail['documents']:
            quotes.extend(document['citations'])
    if not quotes:
        return 'not_applicable'
    for hit in quotes:
        revision = load_authorized_revision(role, hit['document_id'], hit['revision'], dbname=dbname)
        if not revision or not revision['is_current'] or revision['guidance_status'] != 'active':
            return 'failed'
        content = revision['content']
        if (hit['quote'] != '\n'.join(content.splitlines()[hit['start_line']-1:hit['end_line']])
                or hit['source_sha256'] != hashlib.sha256(content.encode()).hexdigest()):
            return 'failed'
    return 'verified'


def evaluate_scenario(scenario, root, batches, dbname):
    # Import only source metadata/content. Questions and expectations never enter PostgreSQL.
    for batch in batches:
        ingest(batch, root=root, dbname=dbname)
    context = scenario['context']
    selection = select(load_authorized_documents(context['role'], dbname=dbname), **context)
    result = search(dbname=dbname, question=scenario['question'], **context)
    report = dict(id=scenario['id'], question=scenario['question'], expected=scenario['expected'],
                  selection=selection, search=result,
                  quotation_count=len(result['passages']) + sum(len(d['citations']) for c in result['conflict_details'] for d in c['documents']),
                  citation_integrity=verify_quotes(result, context['role'], dbname),
                  answer_generation='not_implemented')
    if scenario['id'] == 'S04':
        report['history'] = [load_authorized_revision('platform', 'RB-401', revision, dbname=dbname)
                             for revision in (1, 2)]
    if scenario['id'] == 'S10':
        security_context = dict(context, role='security')
        security_result = search(dbname=dbname, question=scenario['question'], **security_context)
        report['security_search'] = security_result
        report['quotation_count'] += len(security_result['passages'])
        report['security_revision_available'] = load_authorized_revision('security', 'SEC-1001', 1, dbname=dbname) is not None
        statuses = {report['citation_integrity'], verify_quotes(security_result, 'security', dbname)}
        report['citation_integrity'] = ('failed' if 'failed' in statuses else
                                       'verified' if 'verified' in statuses else 'not_applicable')
        restricted = batches[0][0]
        source = (root / restricted['path']).read_text().splitlines()[2]
        platform_text = json.dumps(result)
        report['platform_disclosure_absent'] = all(value not in platform_text for value in
                                                   (restricted['id'], restricted['title'], source))
        report['platform_revision_denied'] = load_authorized_revision('platform', restricted['id'], 1, dbname=dbname) is None
        revoked = copy.deepcopy(restricted)
        revoked['allowed_roles'] = []
        ingest([revoked], root=root, dbname=dbname)
        after = search(dbname=dbname, question=scenario['question'], **security_context)
        report['after_revocation'] = after
        report['revoked_revision_denied'] = load_authorized_revision('security', restricted['id'], 1, dbname=dbname) is None
        report['revoked_search_disclosure_absent'] = all(value not in json.dumps(after) for value in
                                                       (restricted['id'], restricted['title'], source))
        report['stored_answer_regeneration'] = 'not_implemented_no_answer_store'
    return report


def run():
    prepared = load_fixture()  # Validate every source before any database mutation.
    frozen = [dict(scenario=s, records=records) for s, _, _, records in prepared]
    results = []
    for scenario, root, batches, _ in prepared:
        dbname = 'opslens_test_external_' + uuid.uuid4().hex
        with connect(dbname='postgres', autocommit=True) as conn:
            conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(dbname)))
        try:
            bootstrap(dbname)
            results.append(evaluate_scenario(scenario, root, batches, dbname))
        finally:
            with connect(dbname='postgres', autocommit=True) as conn:
                conn.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(dbname)))
    return dict(dataset_sha256=hashlib.sha256(json.dumps(frozen, sort_keys=True).encode()).hexdigest(),
                retrieval_method=RETRIEVAL_METHOD, provenance='user_supplied_synthetic_independence_asserted_by_submitter',
                scope='Backend evidence retrieval, citations and revision access; not generated-answer quality or browser authentication',
                results=results)


def integrity_ok(report):
    for result in report['results']:
        if result['citation_integrity'] not in {'verified', 'not_applicable'}:
            return False
        if result['id'] == 'S10' and not all(result[key] for key in
                ('platform_disclosure_absent', 'platform_revision_denied',
                 'revoked_revision_denied', 'revoked_search_disclosure_absent')):
            return False
    return True


def main():
    report = run()
    print(json.dumps(report, indent=2))
    # This report requires human assessment of the proposed behavioral expectations.
    # Success means no detected quote/access integrity failure, not ten scenarios passed.
    return 0 if integrity_ok(report) else 1


if __name__ == '__main__':
    raise SystemExit(main())
