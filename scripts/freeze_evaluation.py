"""Freeze private evaluation inputs and declared criteria; never run or tune retrieval."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.adjudicate_retrieval import private_write, require
from scripts.evaluate_external_pack import load_pack

MODES = ['reviewed-v5', 'focused-v6', 'relaxed-lexical-v1']
CATEGORIES = {'wrong_procedure', 'prose_conflict', 'incomplete_context', 'useful_prohibition',
              'monitoring_vs_live_state', 'shared_keyword_distractor', 'authorization', 'applicability'}
METRICS = {'required_evidence_recall_min', 'adjudicated_precision_lower_bound_min',
           'task_mismatch_rate_max', 'inappropriate_abstention_rate_max'}
GUARDS = {'unauthorized_disclosures_max': 0, 'citation_integrity_failure_max': 0,
          'policy_eligibility_violations_max': 0}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def question_hash(question):
    return hashlib.sha256(re.sub(r'\s+', ' ', question.strip().casefold()).encode()).hexdigest()


def code_hashes():
    # Pin the actual implementations and adapters, not just a mutable branch name.
    files = list((ROOT/'backend/src').rglob('*')) + list((ROOT/'runreviq').rglob('*'))
    files += list((ROOT/'scripts').glob('*.py'))
    files += list((ROOT/'database').rglob('*.sql'))
    files += [ROOT/name for name in ('backend/pom.xml','backend/mvnw','backend/.mvn/wrapper/maven-wrapper.properties','pyproject.toml','uv.lock')]
    return {str(p.relative_to(ROOT)): sha(p) for p in sorted(files)
            if p.is_file() and '__pycache__' not in p.parts and (p.suffix in ('.py','.java','.js','.css','.html','.properties','.json','.xml','.sql','.toml','.lock') or p.name == 'mvnw')}


def validate_protocol(protocol, key):
    require(set(protocol) == {'schema_version','dataset_id','provenance','coverage','acceptance'}, 'Invalid protocol fields')
    require(type(protocol['schema_version']) is int and protocol['schema_version'] == 1, 'Unsupported schema')
    require(isinstance(protocol['dataset_id'],str) and protocol['dataset_id'].strip(), 'Dataset identity required')
    p = protocol['provenance']
    require(set(p) == {'author_type','author','prior_exposure','independence_declaration','source_metadata_review'}, 'Invalid provenance fields')
    require(p['author_type'] in ('human','ai','mixed','unknown'), 'Invalid author type')
    require(p['prior_exposure'] in ('none_declared','inspected','used_for_tuning','unknown'), 'Invalid exposure')
    require(p['source_metadata_review'] in ('pending','declared_complete'), 'Invalid metadata review')
    for name in ('author','independence_declaration'):
        require(isinstance(p[name],str) and p[name].strip(), 'Authorship and declaration required')
    ids = {c['id'] for c in key['cases']}
    require(ids and len(ids) == len(key['cases']), 'Missing or duplicate cases')
    require(set(protocol['coverage']) == ids, 'Coverage must list every case exactly')
    observed = set()
    for tags in protocol['coverage'].values():
        require(isinstance(tags,list) and tags and all(isinstance(t,str) for t in tags), 'Invalid coverage tags')
        require(len(tags) == len(set(tags)) and set(tags) <= CATEGORIES, 'Invalid or duplicate coverage category')
        observed.update(tags)
    require(observed == CATEGORIES, 'Missing required coverage category')
    a = protocol['acceptance']
    require(set(a) == METRICS | set(GUARDS) | {'human_review_required','unresolved_judgments_max','rationale'}, 'Invalid acceptance fields')
    for name in METRICS:
        value = a[name]
        require(type(value) in (int,float) and math.isfinite(value) and 0 <= value <= 1, 'Rate thresholds must be finite numbers in [0,1]')
    for name, value in {**GUARDS,'unresolved_judgments_max':0}.items():
        require(type(a[name]) is int and a[name] == value, 'Safety and unresolved-judgment gates must be zero')
    require(a['human_review_required'] is True, 'Human review cannot be disabled')
    require(isinstance(a['rationale'],str) and a['rationale'].strip(), 'Threshold rationale required')


def freeze(sources, gold, protocol_path, known_gold, output):
    require(not output.exists(), 'Output already exists')
    require(sources.resolve() not in output.resolve().parents, 'Receipt must stay outside source corpus')
    require(bool(known_gold), 'Known question sets must be declared')
    protocol = json.loads(protocol_path.read_text())
    inputs = [protocol_path, gold, sources/'manifest.json', *known_gold]
    manifest = json.loads((sources/'manifest.json').read_text())
    for d in manifest['documents']:
        name = d['file']
        require(isinstance(name,str) and Path(name).name == name and name.endswith('.md'), 'Unsafe source filename')
        inputs.append(sources/name)
    require(all(p.is_file() and not p.is_symlink() for p in inputs), 'Inputs must be regular non-symlink files')
    before = {str(p.resolve()): sha(p) for p in inputs}
    with tempfile.TemporaryDirectory(prefix='runreviq-freeze-') as temp:
        catalog, key, adapter = load_pack(sources, gold, Path(temp))
    validate_protocol(protocol, key)
    current = {}
    for doc in sorted(catalog, key=lambda d:d['revision']):
        current[doc['id']] = doc
    line_counts = {d['id']:len((sources/Path(d['path']).name).read_text().splitlines()) for d in current.values()}
    for case in key['cases']:
        require(isinstance(case['question'], str) and case['question'].strip(), 'Empty question')
        require(isinstance(case['expected_behavior'], str) and case['expected_behavior'].strip(), 'Expected behavior required')
        forbidden = case['forbidden_passage_ids']
        require(isinstance(forbidden,list) and all(i in current for i in forbidden), 'Unknown forbidden source')
        for passage in case['required_passages']:
            doc = current[passage['document_id']]
            require(case['context']['role'] in doc['allowed_roles'], 'Positive label outside role boundary')
            require(doc['revision'] == passage['revision'] and doc['id'] not in forbidden, 'Stale or contradictory positive label')
            require(type(passage['start_line']) is int and type(passage['end_line']) is int and
                    1 <= passage['start_line'] <= passage['end_line'] <= line_counts[doc['id']] and passage['exact_text'].strip(), 'Invalid positive passage range')
    known = set()
    for path in known_gold:
        data = json.loads(path.read_text())
        cases = data['cases'] if isinstance(data,dict) else data
        for case in cases:
            known.add(question_hash(case['question']))
    hashes = [question_hash(c['question']) for c in key['cases']]
    require(len(set(hashes)) == len(hashes), 'Duplicate normalized questions within proposed set')
    overlap = sorted(set(hashes) & known)
    provenance = protocol['provenance']
    reasons = []
    if provenance['author_type'] != 'human': reasons.append('not_declared_human_authored')
    if provenance['prior_exposure'] != 'none_declared': reasons.append('prior_exposure_or_unknown')
    if overlap or adapter['exact_question_overlap']: reasons.append('known_question_overlap')
    result = dict(schema_version=1, created_at_utc=datetime.now(timezone.utc).isoformat(), protocol=protocol, protocol_file=str(protocol_path.resolve()), input_hashes=before, code_hashes=code_hashes(),
        modes=MODES, adapter=adapter, exact_known_question_overlap_hashes=overlap,
        holdout_status='development_only' if reasons else 'reserved_claim_unverified',
        holdout_reasons=reasons, independence='not_verified', semantic_overlap='not_verified',
        readiness='blocked_pending_authorized_independent_human_review',
        limitations=['Coverage tags and author declarations require human verification.',
                    'Hashes detect changes, not authorship, semantic duplication, current ACLs or truthful labels.',
                    'This receipt does not run retrieval or decide acceptance; no runtime promotion.'])
    require(all(sha(Path(p)) == h for p,h in before.items()), 'Inputs changed during freeze')
    result['receipt_sha256'] = hashlib.sha256(json.dumps(result,sort_keys=True,allow_nan=False).encode()).hexdigest()
    private_write(output, result)
    return result


def verify(receipt):
    value = json.loads(receipt.read_text())
    payload = {k:v for k,v in value.items() if k != 'receipt_sha256'}
    require(hashlib.sha256(json.dumps(payload,sort_keys=True,allow_nan=False).encode()).hexdigest() == value['receipt_sha256'], 'Receipt content changed')
    require(value['schema_version'] == 1 and value['modes'] == MODES, 'Invalid receipt')
    require(value['input_hashes'] and value['code_hashes'], 'Missing bindings')
    require(all(sha(Path(p)) == h for p,h in value['input_hashes'].items()), 'Frozen input changed')
    require(value['protocol_file'] in value['input_hashes'], 'Unbound protocol file')
    require(json.loads(Path(value['protocol_file']).read_text()) == value['protocol'], 'Receipt criteria differ from frozen protocol')
    require(code_hashes() == value['code_hashes'], 'Pinned implementation changed')
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command',required=True)
    p = sub.add_parser('freeze')
    for arg in ('sources','gold','protocol','output'): p.add_argument('--'+arg,type=Path,required=True)
    p.add_argument('--known-gold',type=Path,action='append',required=True)
    p = sub.add_parser('verify');p.add_argument('--receipt',type=Path,required=True)
    a = parser.parse_args()
    try:
        result = verify(a.receipt) if a.command == 'verify' else freeze(a.sources,a.gold,a.protocol,a.known_gold,a.output)
        print(json.dumps(dict(holdout_status=result['holdout_status'],independence=result['independence'],readiness=result['readiness'])))
    except (ValueError,KeyError,TypeError,OSError):
        parser.exit(1,'Evaluation freeze rejected: invalid inputs, changed bindings, or existing output. No acceptance decision made.\n')


if __name__ == '__main__': main()
