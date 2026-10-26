"""Paired development ablation; diagnostic output is offline and never API-visible."""
import argparse
import copy
import hashlib
import json
import subprocess
import sys
import uuid
from pathlib import Path
from statistics import mean

from psycopg import sql
from runreviq.database import connect, bootstrap, ingest, prepare
from runreviq.evaluate_search import EvaluationCase
from runreviq.search import search, RETRIEVAL_METHOD
from runreviq.selector import ROOT, select

FIXTURE = ROOT / 'datasets/reviewed-validation'
CONFIGURATIONS = ('legacy_paragraphs', 'reviewed_ranges', 'reviewed_bundle')


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def load_fixture(root=FIXTURE):
    freeze = json.loads((root/'freeze.json').read_text())
    for name, expected in freeze['files'].items():
        path = (root/name).resolve()
        if not path.is_relative_to(root.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Frozen fixture changed: '+name)
    docs = json.loads((root/'catalog.json').read_text())
    cases = json.loads((root/'questions.json').read_text())
    records = prepare(docs, root=root)
    sources = {d['id']: content for d, _, content, _ in records}
    by_id = {d['id']: d for d in docs}
    reviews = json.loads((root/'reviews.json').read_text())
    if len(reviews) != len(docs) or len({r['document_id'] for r in reviews}) != len(docs):
        raise ValueError('Missing or duplicate fixture provenance')
    for review in reviews:
        doc = by_id[review['document_id']]
        if (review['author'] == review['reviewer'] or review['decision'] != 'approved_fixture'
                or review['profile'] != doc['evidence_profile']
                or review['source_sha256'] != hashlib.sha256(sources[doc['id']].encode()).hexdigest()):
            raise ValueError('Invalid simulated approval provenance')
    if not cases or len({c['id'] for c in cases}) != len(cases):
        raise ValueError('Empty or duplicate cases')
    for case in cases:
        EvaluationCase.model_validate(case)
        if bool(case['expected_passages']) != (case['expected_status'] == 'selected'):
            raise ValueError('Expected passages belong to positive cases only')
        for label in case['expected_passages']:
            doc = by_id[label['document_id']]
            if (label['revision'] != doc['revision'] or not 1 <= label['start_line'] <= label['end_line'] <= len(sources[doc['id']].splitlines())
                    or case['context']['role'] not in doc['allowed_roles']):
                raise ValueError('Invalid source-bound gold span')
    return docs, cases, sources, freeze


def covers(hit, label):
    return (hit['document_id'] == label['document_id'] and hit['revision'] == label['revision']
            and hit['start_line'] <= label['start_line'] <= label['end_line'] <= hit['end_line'])


def gold_groups(labels, sources):
    # Exact whitespace-normalized gold quotations define duplicate alternatives,
    # not inferred semantic similarity. IDs/revisions must still match a label.
    groups = {}
    for label in labels:
        quote = '\n'.join(sources[label['document_id']].splitlines()[label['start_line']-1:label['end_line']])
        groups.setdefault(' '.join(quote.split()), []).append(label)
    return list(groups.values())


def metrics(groups, hits):
    found, useful = set(), 0
    for hit in hits:
        matches = {i for i, group in enumerate(groups) if any(covers(hit, label) for label in group)}
        if matches - found:
            useful += 1
        found.update(matches)
    return dict(recall=len(found)/len(groups) if groups else None,
                precision_returned=useful/len(hits) if hits else 0,
                precision_fixed_5=useful/5, missing_groups=[g for i,g in enumerate(groups) if i not in found])


def diagnose(group, docs, trace):
    selection = trace['selection']
    ids = {p['document_id'] for p in group}
    context = trace['effective_context']
    relevant = [d for d in docs if d['id'] in ids]
    if all(context['role'] not in d['allowed_roles'] for d in relevant):
        return 'authorization_exclusion'
    if any(d['id'] in selection['uncertain_documents'] for d in relevant):
        return 'metadata_or_context_incomplete'
    if not ids.intersection(selection['applicable_documents']):
        return 'applicability_exclusion'
    if selection['status'] != 'selected':
        return 'global_conflict_or_review_withholding'
    def present(stage):
        return any(covers(p,l) for p in trace[stage] for l in group)
    if not present('candidates'):
        return 'source_range_omission'
    if not present('rankable'):
        return 'lexical_gate'
    if not present('qualified'):
        return 'relevance_ranking'
    if not present('pool'):
        return 'candidate_limit'
    return 'result_limit_or_bundle_selection'


def ratio(rows, predicate):
    return dict(numerator=sum(bool(predicate(r)) for r in rows), denominator=len(rows),
                value=mean([int(bool(predicate(r))) for r in rows]) if rows else None)


def aggregate(rows):
    positive = [r for r in rows if r['expected_status']=='selected']
    withheld = [r for r in rows if r['expected_status']!='selected']
    nonconflict = [r for r in rows if r['expected_status']!='conflict']
    return dict(cases=len(rows), passed=sum(r['passed'] for r in rows), positive_cases=len(positive),
                recall_at_5=mean([r['recall'] for r in positive]) if positive else None,
                precision_at_returned_5=mean([r['precision_returned'] for r in positive]) if positive else None,
                precision_at_5=mean([r['precision_fixed_5'] for r in positive]) if positive else None,
                false_positive_rate=ratio(withheld, lambda r: bool(r['result']['passages'])),
                false_conflict_rate=ratio(nonconflict,lambda r:r['actual_status']=='conflict'),
                appropriate_abstention=ratio(withheld,lambda r:r['actual_status']==r['expected_status'] and not r['result']['passages']),
                incorrect_abstention=ratio(positive,lambda r:r['actual_status']!='selected'),
                access_isolation_failures=ratio(rows,lambda r:bool(r['unauthorized_disclosures'])),
                inapplicable_passage_failures=ratio(rows,lambda r:bool(r['inapplicable_passages'])),
                citation_failures=ratio(rows,lambda r:not r['citation_integrity']))


def evaluate_configuration(config, docs, cases, sources, root=FIXTURE):
    if config not in CONFIGURATIONS:
        raise ValueError('Unknown evaluation configuration')
    configured = copy.deepcopy(docs)
    if config == 'legacy_paragraphs':
        for doc in configured: doc.pop('evidence_profile')
    dbname = 'opslens_test_reviewed_'+uuid.uuid4().hex
    with connect(dbname='postgres',autocommit=True) as conn:
        conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(dbname)))
    rows=[]
    try:
        bootstrap(dbname);ingest(configured, root=root, dbname=dbname)
        for case in cases:
            trace={}
            result=search(question=case['question'],dbname=dbname,_diagnostics=trace,
                          _assemble=config!='reviewed_ranges',**case['context'])
            groups=gold_groups(case['expected_passages'],sources)
            measured=metrics(groups,result['passages'])
            serialized=json.dumps(result)
            unauthorized=[d['id'] for d in docs if case['context']['role'] not in d['allowed_roles'] and
                          (d['id'] in serialized or sources[d['id']].splitlines()[2] in serialized)]
            # Assess against frozen reviewed applicability, even in legacy arm.
            inapplicable=[];integrity=True
            for hit in result['passages']:
                doc=next(d for d in docs if d['id']==hit['document_id'])
                if select([doc],**trace['effective_context'])['status']!='selected':inapplicable.append(doc['id'])
                content=sources[doc['id']]
                integrity &= (hit['revision']==doc['revision'] and hit['quote']=='\n'.join(content.splitlines()[hit['start_line']-1:hit['end_line']]) and hit['source_sha256']==hashlib.sha256(content.encode()).hexdigest())
            # Conflict citations are evidence too.
            for conflict in result['conflict_details']:
                for doc in conflict['documents']:
                    for cite in doc['citations']:
                        content=sources[doc['document_id']]
                        integrity &= cite['quote']=='\n'.join(content.splitlines()[cite['start_line']-1:cite['end_line']]) and cite['source_sha256']==hashlib.sha256(content.encode()).hexdigest()
            passed=(result['status']==case['expected_status'] and integrity and not unauthorized and not inapplicable
                    and (measured['recall']==1 if groups else not result['passages']))
            rows.append(dict(id=case['id'],category=case['category'],question=case['question'],context=case['context'],expected_status=case['expected_status'],actual_status=result['status'],expected_passages=case['expected_passages'],passed=passed,
                             **measured,miss_diagnostics=[dict(alternatives=g,cause=diagnose(g,configured,trace)) for g in measured['missing_groups']],
                             unauthorized_disclosures=unauthorized,inapplicable_passages=inapplicable,citation_integrity=bool(integrity),result=result,trace=trace))
        return dict(configuration=config,configuration_sha256=digest(configured),summary=aggregate(rows),results=rows)
    finally:
        with connect(dbname='postgres',autocommit=True) as conn:
            conn.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(dbname)))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validate-only',action='store_true')
    parser.add_argument('--report-only',action='store_true')
    args=parser.parse_args()
    docs,cases,sources,freeze=load_fixture()
    if args.validate_only:
        print(json.dumps(dict(documents=len(docs),cases=len(cases),fixture_sha256=digest(freeze))))
        return 0
    report=dict(schema_version=1,provenance='implementation_authored_development',approval_provenance='simulated_fixture_records_not_human_review',fixture_sha256=digest(freeze),
                python_version=sys.version.split()[0],lock_sha256=hashlib.sha256((ROOT/'uv.lock').read_bytes()).hexdigest(),
                code_revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
                code_dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip()),retrieval_method=RETRIEVAL_METHOD,
                implementation_sha256=digest({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted((ROOT/'runreviq').glob('*.py'))}),
                configurations=[evaluate_configuration(c,docs,cases,sources) for c in CONFIGURATIONS])
    print(json.dumps(report,indent=2))
    unsafe=any(r['unauthorized_disclosures'] or not r['citation_integrity'] for c in report['configurations'] for r in c['results'])
    return 1 if unsafe or (not args.report_only and any(not r['passed'] for c in report['configurations'] for r in c['results'])) else 0


if __name__=='__main__':
    raise SystemExit(main())
