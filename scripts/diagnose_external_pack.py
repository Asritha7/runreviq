"""Diagnose frozen paired outputs offline; never query the app or alter the benchmark."""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.evaluate_external_pack import load_pack
from runreviq.evaluate_java import private_json

MODES = ('reviewed-v5', 'focused-v6')


def passage_key(p):
    return tuple(p[k] for k in ('document_id', 'revision', 'start_line', 'end_line'))


def diagnose(key, provenance, reports):
    """Classifications describe observable pipeline boundaries, not inferred rank scores."""
    cases = {case['id']: case for case in key['cases']}
    if len(cases) != len(key['cases']):
        raise ValueError('Duplicate gold case ID')
    outputs = {}
    jars = set()
    for mode in MODES:
        report = reports[mode]
        if report['mode'] != mode or report['provenance']['input_sha256'] != provenance['input_sha256']:
            raise ValueError('Report does not belong to the frozen pack/mode')
        jars.add(report['provenance']['java_jar_sha256'])
        rows = {r['id']: r for r in report['results']}
        if len(rows) != len(report['results']) or rows.keys() != cases.keys():
            raise ValueError('Report case IDs differ from gold')
        result = []
        for identifier, case in cases.items():
            row = rows[identifier]
            if not row['structured_integrity_ok']:
                raise ValueError('Cannot diagnose an integrity-failed response')
            raw = row['raw_response']
            if raw['status'] != row['actual_status']:
                raise ValueError('Inconsistent recorded status')
            hits = {passage_key(p) for p in raw['passages']}
            required = {passage_key(p) for p in case['required_passages']}
            missing = required - hits
            applicable = set(raw.get('applicable_documents', []))
            uncertain = set(raw.get('uncertain_documents', []))
            boundaries = []
            if not required:
                boundaries.append('no_required_evidence_semantics_ungraded')
            elif not missing:
                boundaries.append('required_evidence_retrieved_semantics_ungraded')
            elif raw['status'] == 'needs_context_or_review':
                boundaries.append('context_or_metadata_block')
            elif all(p[0] in applicable for p in missing):
                boundaries.append('missing_passages_from_applicable_documents')
            else:
                boundaries.append('missing_documents_at_applicability_boundary')
            if case['expected_behavior'] == 'conflict' and raw['status'] != 'conflict':
                boundaries.append('expected_conflict_not_reported')
            if case['expected_behavior'] == 'needs_context':
                boundaries.append('specific_clarification_not_verified')
            forbidden = sorted({p[0] for p in hits} & set(case['forbidden_passage_ids']))
            if forbidden:
                boundaries.append('forbidden_passage_returned')
            relevant = {p[0] for p in required} | uncertain
            limits = {k: v for k, v in provenance['adaptation_limits'].items()
                      if k.rsplit('@', 1)[0] in relevant and v}
            result.append(dict(id=identifier, observed_status=raw['status'], boundaries=boundaries,
                missing_required_passages=[dict(zip(('document_id','revision','start_line','end_line'), p))
                                           for p in sorted(missing)],
                adaptation_limits=limits, forbidden_returned=forbidden,
                returned_passages=[list(p) for p in sorted(hits)]))
        outputs[mode] = dict(cases=result, boundary_counts=dict(Counter(
            boundary for row in result for boundary in row['boundaries'])))
    if len(jars) != 1:
        raise ValueError('Paired reports used different Java artifacts')
    left, right = (outputs[m]['cases'] for m in MODES)
    differences = [a['id'] for a, b in zip(left, right)
                   if (a['observed_status'], a['returned_passages']) != (b['observed_status'], b['returned_passages'])]
    return dict(input_sha256=provenance['input_sha256'], java_jar_sha256=next(iter(jars)),
        modes=outputs, differing_cases=differences,
        limits='Offline boundary diagnosis only. No token/score trace, semantic grading, approval or ranking change. '
               'Applicable-document misses do not establish which lexical gate rejected a passage.')


def run(sources, gold, first_run, output):
    if output.exists():
        raise ValueError('Refusing to overwrite diagnostics')
    with tempfile.TemporaryDirectory(prefix='runreviq-diagnosis-') as temporary:
        _, key, provenance = load_pack(sources, gold, Path(temporary))
        reports = {mode: json.loads((first_run / (mode + '.json')).read_text()) for mode in MODES}
        result = diagnose(key, provenance, reports)
    output.mkdir(mode=0o700, parents=True, exist_ok=False)
    private_json(output / 'diagnosis.json', result)
    print(json.dumps({mode: result['modes'][mode]['boundary_counts'] for mode in MODES}, indent=2))
    print('Different status/passage sets:', len(result['differing_cases']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('sources', 'gold', 'first-run', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    run(args.sources, args.gold, args.first_run, args.output)
