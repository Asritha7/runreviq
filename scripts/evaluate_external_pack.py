"""Evaluate a supplied source/gold pack locally without changing ranking or publishing labels."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT))
from runreviq.database import ingest, prepare
from runreviq.evaluate_java import JAR, METHODS, evaluation_database, java_runtime, http_json, authorized_sources, assess_response, private_json


def digest(data):return hashlib.sha256(data).hexdigest()


def load_pack(sources, gold, stage):
    manifest=json.loads((sources/'manifest.json').read_text()); key=json.loads(gold.read_text())
    catalog=[]; originals={}; limits={}; seen=set()
    destination=stage/'datasets/northstar';destination.mkdir(parents=True)
    for item in manifest['documents']:
        name=item['file']; identifier=item['document_id']; revision=item['revision']
        if Path(name).name!=name or not name.endswith('.md') or (identifier,revision) in seen:
            raise ValueError('Unsafe source filename or duplicate revision')
        seen.add((identifier,revision)); data=(sources/name).read_bytes()
        if digest(data)!=item['sha256']:raise ValueError('Source hash mismatch')
        text=data.decode('utf-8'); lines=text.splitlines();start=item['source_line_start'];end=item['source_line_end']
        if not 1<=start<=end<=len(lines):raise ValueError('Invalid proposed evidence range')
        match=re.fullmatch(r'>=(\d+\.\d+\.\d+),\s*<(\d+\.\d+\.\d+)',item['software_version_range'])
        if not match:raise ValueError('Unsupported version syntax')
        notes=[]; supported=item['precondition']=='any'
        if not supported:notes.append('unsupported_precondition:'+item['precondition'])
        role=item['evidence_role']
        units=[] if role=='prohibition' else [dict(role=role,start_line=start,end_line=end)]
        if role=='prohibition':notes.append('prohibition_left_unclassified_without_reviewed_normalized_claim')
        scope=item['http_scope'];scope=dict(mode='general',codes=[]) if scope=='any' else dict(mode='only',codes=[int(scope)])
        doc=dict(id=identifier,title=lines[0].lstrip('# ').strip(),revision=revision,service=item['service'],environment=item['environment'],
            version_min=match[1],version_max_exclusive=match[2],key_rotation_enabled=None,allowed_roles=item['allowed_roles'],
            metadata_verified=supported,status=item['approval_status'],claims=[],path='datasets/northstar/'+name,
            evidence_profile=dict(schema_version=1,http_scope=scope,units=units))
        (destination/name).write_bytes(data);prepare([doc],root=stage)
        originals[(identifier,revision)]=text;catalog.append(doc);limits[f'{identifier}@{revision}']=notes
    ids=set()
    for case in key['cases']:
        if case['id'] in ids:raise ValueError('Duplicate question ID')
        ids.add(case['id'])
        for p in case['required_passages']:
            text=originals[(p['document_id'],p['revision'])]
            if '\n'.join(text.splitlines()[p['start_line']-1:p['end_line']])!=p['exact_text']:
                raise ValueError('Gold quotation does not match source')
    fingerprint=digest(json.dumps(dict(manifest=manifest,gold=key,sources={f'{k[0]}@{k[1]}':v for k,v in originals.items()}),sort_keys=True).encode())
    # Exact overlap check only: no claim to detect semantic duplication or independent authorship.
    questions={re.sub(r'\s+',' ',c['question'].strip().lower()):c['id'] for c in key['cases']}
    overlap=[]
    for p in (ROOT/'datasets').rglob('*.json'):
        try:value=json.loads(p.read_text())
        except (ValueError,UnicodeError):continue
        def walk(v):
            if isinstance(v,dict):
                q=v.get('question')
                if isinstance(q,str) and re.sub(r'\s+',' ',q.strip().lower()) in questions:overlap.append(str(p.relative_to(ROOT)))
                for child in v.values():walk(child)
            elif isinstance(v,list):
                for child in v:walk(child)
        walk(value)
    source_hashes={digest(v.encode()) for v in originals.values()}
    source_overlap=[str(p.relative_to(ROOT)) for p in (ROOT/'datasets').rglob('*.md') if digest(p.read_bytes()) in source_hashes]
    return catalog,key,dict(input_sha256=fingerprint,author_declaration=key.get('author'),independence='externally_supplied_not_independently_verified',
        approvals='simulated_fixture_metadata_only',adaptation_limits=limits,exact_question_overlap=sorted(set(overlap)),exact_source_overlap=source_overlap,
        note='No semantic duplicate guarantee. Original gold unchanged; no normalized claims invented. Unsupported preconditions remain unverified.')


def run(sources,gold,output):
    output.mkdir(mode=0o700,parents=True,exist_ok=False)
    with tempfile.TemporaryDirectory(prefix='runreviq-external-') as temporary:
        stage=Path(temporary);catalog,key,provenance=load_pack(sources,gold,stage)
        private_json(output/'provenance.json',provenance)
        subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','-ntp','package','-DskipTests'],stdout=subprocess.DEVNULL,check=True)
        jar=stage/'backend.jar';shutil.copyfile(JAR,jar)
        provenance['java_jar_sha256']=digest(jar.read_bytes())
        with evaluation_database() as database:
            # Separate transactions preserve historical revisions; only sources enter SQL.
            for doc in sorted(catalog,key=lambda d:(d['id'],d['revision'])):ingest([doc],dbname=database,root=stage)
            for mode in ('reviewed-v5','focused-v6'):
                folder=stage/mode;folder.mkdir(mode=0o700);results=[]
                with java_runtime(database,folder,jar,mode) as (port,tokens):
                    for case in key['cases']:
                        context=dict(case['context']);role=context.pop('role')
                        if 'observed_http_status' in context:context['http_status']=context.pop('observed_http_status')
                        response=http_json(port,'/v1/search',body=dict(context,question=case['question']),token=tokens[role])
                        required=[{k:v for k,v in p.items() if k!='exact_text'} for p in case['required_passages']]
                        # Structured integrity validation uses the actual status, not a fabricated semantic pass label.
                        check=dict(id=case['id'],question=case['question'],context=dict(context,role=role),category='external',
                                   expected_status=response['status'],expected_passages=required)
                        scored=assess_response(check,response,authorized_sources(database,role),METHODS[mode])
                        hits=response.get('passages',[])
                        matched=[p for p in required if any(all(h.get(k)==v for k,v in p.items()) for h in hits)]
                        forbidden=[h['document_id'] for h in hits if h['document_id'] in case['forbidden_passage_ids']]
                        unsupported=[]
                        if case['expected_behavior']=='conflict':unsupported.append('prose_only_conflict_detection')
                        if case['expected_behavior']=='needs_context':unsupported.append('arbitrary_precondition_clarification')
                        status_expectation={'selected':'selected','compatible':'selected'}.get(case['expected_behavior'])
                        results.append(dict(id=case['id'],expected_behavior=case['expected_behavior'],actual_status=response['status'],
                            unsupported_capabilities=unsupported,semantic_behavior='not_automatically_graded',
                            status_match=None if status_expectation is None else response['status']==status_expectation,
                            recall=None if not required else len(matched)/len(required),
                            strict_returned_precision=None if not required else len(matched)/len(hits) if hits else 0,
                            exact_required_set=len(matched)==len(required) and len(hits)==len(required),
                            forbidden_returned=forbidden,structured_integrity_ok=scored['integrity_ok'],
                            raw_response=response if scored['integrity_ok'] else {'redacted':'integrity failure'},
                            note='Strict precision counts all non-required passages as extras; optional complementary evidence may be useful.'))
                private_json(output/(mode+'.json'),dict(provenance=provenance,mode=mode,results=results))
                if any(not r['structured_integrity_ok'] for r in results):raise RuntimeError('Structured integrity failure; unsafe response redacted')
    print('Recorded paired first-run outputs locally; no ranking changes or default promotion.')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--sources',type=Path,required=True);p.add_argument('--gold',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();run(a.sources,a.gold,a.output)
