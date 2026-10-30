"""Replay frozen external outputs through a test-only Java observer in a disposable DB."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.evaluate_external_pack import load_pack
from scripts.diagnose_external_pack import diagnose, passage_key, MODES
from scripts.rehearse_java_workspace import runtime_environment
from runreviq.database import ingest, credentials, connect
from runreviq.evaluate_java import evaluation_database, private_json, authorized_sources, assess_response, METHODS


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def first_failure(required, events):
    """Follow actual execution order; never infer a score for an unscored candidate."""
    key = passage_key(required)
    generated = next(e for e in events if e['stage'] == 'candidate_generation')
    if key not in {passage_key(p) for p in generated['passages']}:
        return dict(stage='candidate_generation', evidence=generated)
    for event in events:
        stage = event['stage']
        if stage == 'exclusion_gate' and event['excluded']:
            return dict(stage='excluded_sentence_terms', evidence=event)
        if 'passage' not in event or passage_key(event['passage']) != key:
            continue
        decision = event.get('decision')
        if stage == 'lexical_gate' and decision != 'eligible':
            return dict(stage=decision, evidence=event)
        if stage == 'coverage' and decision != 'eligible':
            return dict(stage=decision, evidence=event)
        if stage in ('selection', 'assembly_decision') and decision != 'selected':
            return dict(stage=decision, evidence=event)
    raise ValueError('Missing passage has no decisive trace event')


def classify(case, row):
    events = row['events']; response = row['response']
    if response['status'] == 'needs_context_or_review':
        return dict(id=case['id'], category='metadata_context_review', diagnoses=[],
                    evidence=next(e for e in events if e['stage'] == 'applicability'))
    if case['expected_behavior'] == 'conflict':
        return dict(id=case['id'], category='prose_conflict_not_graded_as_ranking', diagnoses=[], observed_status=response['status'])
    required = case['required_passages']
    if not required:
        return dict(id=case['id'], category='no_required_passages_semantics_ungraded', diagnoses=[])
    missing = [p for p in required if passage_key(p) not in {passage_key(h) for h in response['passages']}]
    applicable = set(response['applicable_documents'])
    if any(p['document_id'] not in applicable for p in missing):
        return dict(id=case['id'], category='applicability_miss', diagnoses=[])
    diagnoses = [dict(passage={k: p[k] for k in ('document_id','revision','start_line','end_line')},
                      **first_failure(p, events)) for p in missing]
    return dict(id=case['id'], category='eligible_evidence_miss' if missing else 'required_evidence_retrieved', diagnoses=diagnoses)


def corpus_digest(db):
    with connect(dbname=db) as conn:
        rows = conn.execute('''SELECT d.id,d.current_revision,d.allowed_roles,v.revision,v.checksum,v.metadata,v.content
            FROM opslens.documents d JOIN opslens.document_versions v ON v.document_id=d.id ORDER BY d.id,v.revision''').fetchall()
        withdrawals = conn.execute('SELECT document_id,revision,reason FROM opslens.document_withdrawals ORDER BY document_id,revision').fetchall()
    return hashlib.sha256(json.dumps([rows,withdrawals],sort_keys=True).encode()).hexdigest()


def run(sources, gold, baseline, output, passage_scope_experiment=False):
    if output.exists():
        raise ValueError('Refusing to overwrite trace output')
    output.mkdir(mode=0o700,parents=True,exist_ok=False)
    frozen = [gold, sources/'manifest.json', *sorted(sources.glob('*.md')), *(baseline/(m+'.json') for m in MODES)]
    before = {str(p): sha(p) for p in frozen}
    with tempfile.TemporaryDirectory(prefix='runreviq-offline-trace-') as temporary:
        stage = Path(temporary)
        catalog, key, provenance = load_pack(sources,gold,stage)
        reports = {m: json.loads((baseline/(m+'.json')).read_text()) for m in MODES}
        diagnose(key,provenance,reports)  # Same original pack/artifact and intact case sets.
        modes = (*MODES, 'passage-scope-v1') if passage_scope_experiment else MODES
        requests = []
        for mode in modes:
            rows = {r['id']: r for r in reports['reviewed-v5' if mode == 'passage-scope-v1' else mode]['results']}
            for case in key['cases']:
                context = dict(case['context'])
                if 'observed_http_status' in context:
                    context['http_status'] = context.pop('observed_http_status')
                requests.append(dict(id=case['id'], mode=mode, question=case['question'], context=context,
                                     frozen_response=rows[case['id']]['raw_response']))
        with evaluation_database() as db:
            for doc in sorted(catalog,key=lambda d:(d['id'],d['revision'])):
                ingest([doc],dbname=db,root=stage)
            corpus = corpus_digest(db)
            passwords = credentials()
            private_json(stage/'readers.json',dict(database=db,reader_passwords={r:passwords[f'READER_{r.upper()}_PASSWORD'] for r in ('platform','security','intern')}))
            private_json(stage/'requests.json',requests)  # Questions and frozen responses only; no answer key.
            env = dict(runtime_environment(),HOME=str(Path.home()),RUNREVIQ_OFFLINE_TRACE_DIR=str(stage))
            log_path=output/'runner.log'
            log_path.touch(mode=0o600,exist_ok=False)
            with log_path.open('w') as log:
                completed = subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','-ntp',
                    '-Dtest=OfflineTraceTest','test'],cwd=ROOT,env=env,stdout=log,stderr=subprocess.STDOUT)
            if completed.returncode:
                raise RuntimeError('Isolated trace replay failed; no output accepted')
            traces = json.loads((stage/'traces.json').read_text())
            expected = {(r['id'],r['mode']) for r in requests}
            if len(traces)!=len(requests) or {(r['id'],r['mode']) for r in traces} != expected:
                raise ValueError('Incomplete trace replay')
            cases = {c['id']:c for c in key['cases']}
            summaries = {m:[] for m in modes}
            measurements = {m:[] for m in modes}
            for row in traces:
                allowed = authorized_sources(db,cases[row['id']]['context']['role'])
                snapshot = next(e for e in row['events'] if e['stage']=='authorized_snapshot')
                if {p['document_id'] for p in snapshot['documents']} != set(allowed):
                    raise ValueError('Trace snapshot differs from authorized reader')
                for source in snapshot['documents']:
                    metadata, content = allowed[source['document_id']]
                    if source['revision'] != metadata['revision'] or source['source_sha256'] != hashlib.sha256(content.encode()).hexdigest():
                        raise ValueError('Trace source integrity differs from authorized reader')
                case=cases[row['id']]
                context=dict(case['context'])
                if 'observed_http_status' in context:context['http_status']=context.pop('observed_http_status')
                required=[{k:v for k,v in p.items() if k!='exact_text'} for p in case['required_passages']]
                check=dict(id=case['id'],question=case['question'],context=context,category='external',expected_status=row['response']['status'],expected_passages=required)
                method='experimental_passage_scope_v1' if row['mode']=='passage-scope-v1' else METHODS[row['mode']]
                integrity=assess_response(check,row['response'],allowed,method)
                if not integrity['integrity_ok']:raise ValueError('Experimental response integrity failed; no accepted report')
                hits={passage_key(p) for p in row['response']['passages']};targets={passage_key(p) for p in required}
                forbidden=sorted({p[0] for p in hits} & set(case['forbidden_passage_ids']))
                if forbidden:raise ValueError('Forbidden experimental passage; no accepted report')
                measurements[row['mode']].append(dict(id=case['id'],status=row['response']['status'],
                    required_count=len(targets),returned_count=len(hits),matched_count=len(hits&targets),
                    recall=len(hits&targets)/len(targets) if targets else None,
                    strict_precision=len(hits&targets)/len(hits) if hits else None,
                    forbidden_returned=forbidden,semantic_behavior='not_automatically_graded'))
                summaries[row['mode']].append(classify(case,row))
            if corpus_digest(db)!=corpus:
                raise ValueError('Read-only replay changed source/publication state')
        if {str(p):sha(p) for p in frozen}!=before:
            raise ValueError('Frozen inputs changed during replay')
        class_hash = hashlib.sha256()
        for path in sorted((ROOT/'backend/target/classes').rglob('*.class')):
            class_hash.update(str(path.relative_to(ROOT/'backend/target/classes')).encode());class_hash.update(path.read_bytes())
        baseline_responses={r['id']:r['response'] for r in traces if r['mode']=='reviewed-v5'}
        comparisons={mode:[] for mode in modes}
        for row in traces:
            base=baseline_responses[row['id']]; response=row['response']
            comparisons[row['mode']].append(dict(id=row['id'],
                response_equal_except_method={k:v for k,v in base.items() if k!='retrieval_method'}=={k:v for k,v in response.items() if k!='retrieval_method'},
                status_changed=base['status']!=response['status'],passages_changed=base['passages']!=response['passages']))
        result = dict(provenance=provenance, frozen_file_hashes=before, corpus_sha256=corpus,
            instrumented_classes_sha256=class_hash.hexdigest(),
            code_revision=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
            git_dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip()),
            summaries=summaries, measurements=measurements, comparisons=comparisons, traces=traces,
            guarantees=['traced_equals_untraced_and_baselines_equal_frozen','repeated_trace_identical','reader_snapshot_only','source_publication_state_unchanged','frozen_inputs_unchanged'])
        private_json(output/'trace-report.json',result)
    for mode in modes:
        counts=Counter(d['stage'] for r in summaries[mode] for d in r['diagnoses'])
        print(mode,json.dumps(dict(counts),sort_keys=True))


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('sources','gold','baseline','output'):
        parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--passage-scope-experiment', action='store_true', help='Also evaluate the isolated candidate; never changes live configuration')
    args=parser.parse_args();run(args.sources,args.gold,args.baseline,args.output,args.passage_scope_experiment)
