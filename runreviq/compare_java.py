"""Compare Java candidate reports only on the same frozen questions and sources."""
import argparse
import json
import math
from pathlib import Path
import re

from runreviq.evaluate_search import passage_key
from runreviq.evaluate_java import summarize


def checked(report):
    if (report.get('runtime') != 'java_http' or not re.fullmatch(r'[0-9a-f]{64}', report.get('dataset_sha256', ''))
            or not report.get('results') or report['cases'] != len(report['results'])):
        raise ValueError('Invalid report identity or case count')
    ids = [r['id'] for r in report['results']]
    if len(set(ids)) != len(ids):
        raise ValueError('Duplicate case ID')
    calculated = summarize(report['results'])
    for key, value in calculated.items():
        if isinstance(value, float):
            if not math.isfinite(value) or not isinstance(report.get(key), (int, float)) or not math.isfinite(report[key]) or abs(report[key]-value) > 1e-10:
                raise ValueError('Report aggregates disagree with cases')
        elif report.get(key) != value:
            raise ValueError('Report aggregates disagree with cases')
    return {r['id']: r for r in report['results']}


def expected_keys(result):
    irrelevant = {passage_key(p) for p in result['irrelevant']}
    relevant = {passage_key(p) for p in result['retrieved']} - irrelevant
    return relevant | {passage_key(p) for p in result['missing']}


def compare(baseline, candidate):
    before, after = checked(baseline), checked(candidate)
    if (baseline['retrieval_method'] != 'postgres_english_reviewed_v5'
            or candidate['retrieval_method'] != 'postgres_english_focused_v6'):
        raise ValueError('Expected reviewed-v5 baseline and focused-v6 candidate')
    for key in ('dataset_sha256','suite','question_source','independence'):
        if baseline[key] != candidate[key]:
            raise ValueError('Comparison requires identical frozen inputs and provenance')
    if before.keys() != after.keys():
        raise ValueError('Case IDs differ')
    regressions, gains, precision_losses = [], [], []
    for identifier, old in before.items():
        new = after[identifier]
        if any(old[k] != new[k] for k in ('question','expected_status','withheld','category')) or expected_keys(old) != expected_keys(new):
            raise ValueError('Case labels or questions differ')
        if (not new['integrity_ok'] or (old['passed'] and not new['passed'])
                or (old['recall'] is not None and new['recall'] < old['recall'])):
            regressions.append(identifier)
        if not old['passed'] and new['passed']:
            gains.append(identifier)
        if not old['withheld'] and new['precision'] < old['precision']:
            precision_losses.append(identifier)
    keys = ('passed','exact_evidence_set_passed','recall_at_5','precision_at_returned_5','precision_fixed_at_5','refusal_accuracy','integrity_failures')
    metrics = {k: dict(baseline=baseline[k],candidate=candidate[k],delta=None if baseline[k] is None or candidate[k] is None else candidate[k]-baseline[k]) for k in keys}
    return dict(dataset_sha256=baseline['dataset_sha256'],suite=baseline['suite'],independence=baseline['independence'],
                metrics=metrics,recall_status_or_integrity_regressions=regressions,newly_matched_outcomes=gains,
                per_case_precision_regressions=precision_losses,
                candidate_strict_quality_gate=candidate['quality_gate'],
                note='Development comparison only. No automatic promotion or independent effectiveness claim.')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    args = parser.parse_args(argv)
    try:
        result = compare(json.loads(args.baseline.read_text()), json.loads(args.candidate.read_text()))
    except (OSError, ValueError, KeyError, TypeError):
        parser.error('Incompatible or invalid reports; no comparison produced')
    print(json.dumps(result, indent=2))
    # Lower precision is visible in the report; never conceal lost evidence or integrity.
    return 1 if result['recall_status_or_integrity_regressions'] else 0


if __name__ == '__main__':
    raise SystemExit(main())
