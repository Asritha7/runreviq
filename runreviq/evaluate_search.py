"""Reproducible synthetic retrieval evaluation in an isolated disposable database."""
import hashlib
import json
import statistics
import time
import uuid

from psycopg import sql
from runreviq.database import connect, bootstrap, ingest
from runreviq.selector import ROOT, load_documents
from runreviq.search import search


def evaluate(dbname):
    cases = json.loads((ROOT / 'datasets/evaluation/questions.json').read_text())
    results = []
    for case in cases:
        started = time.perf_counter()
        result = search(dbname=dbname, question=case['question'], **case['context'])
        elapsed = (time.perf_counter() - started) * 1000
        expected = case['expected_passages']
        ranks = [i + 1 for i, hit in enumerate(result['passages'])
                 if any(all(hit[k] == v for k, v in label.items()) for label in expected)]
        citations_ok = True
        with connect(role=case['context']['role'], dbname=dbname) as conn:
            for hit in result['passages']:
                row = conn.execute('SELECT content FROM opslens.document_versions WHERE document_id=%s AND revision=%s',
                                   (hit['document_id'], hit['revision'])).fetchone()
                citations_ok &= bool(row and '\n'.join(row[0].splitlines()[hit['start_line']-1:hit['end_line']]) == hit['quote']
                                     and hashlib.sha256(row[0].encode()).hexdigest() == hit['source_sha256'])
        status_ok = result['status'] == case['expected_status']
        withheld = case['expected_status'] != 'selected'
        passed = status_ok and citations_ok and (bool(ranks) if expected else not result['passages'] and not result['recommended_documents'])
        results.append(dict(id=case['id'], passed=passed, expected_status=case['expected_status'], actual_status=result['status'],
                            rank=min(ranks) if ranks else None, citations_ok=citations_ok, withheld=withheld, latency_ms=round(elapsed,2)))
    positive = [r for r in results if not r['withheld']]
    refusals = [r for r in results if r['withheld']]
    return dict(cases=len(results), passed=sum(r['passed'] for r in results),
                recall_at_5=sum(r['rank'] is not None for r in positive)/len(positive),
                mean_reciprocal_rank=sum(1/r['rank'] if r['rank'] else 0 for r in positive)/len(positive),
                refusal_accuracy=sum(r['passed'] for r in refusals)/len(refusals),
                citation_accuracy=sum(r['citations_ok'] for r in positive)/len(positive),
                median_latency_ms=round(statistics.median(r['latency_ms'] for r in results),2), results=results)


def main():
    dbname = 'opslens_test_search_' + uuid.uuid4().hex
    with connect(dbname='postgres', autocommit=True) as conn:
        conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(dbname)))
    try:
        bootstrap(dbname)
        ingest(load_documents(), dbname=dbname)
        report = evaluate(dbname)
        print(json.dumps(report, indent=2))
        return 0 if report['passed'] == report['cases'] else 1
    finally:
        with connect(dbname='postgres', autocommit=True) as conn:
            conn.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(dbname)))


if __name__ == '__main__':
    raise SystemExit(main())
