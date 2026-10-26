"""Compare evidence retrieval against fixed labels; never score generated answers."""
import argparse
import hashlib
import json
from pathlib import Path
from statistics import mean

from runreviq.evaluate_external import FIXTURE, load_fixture
from runreviq.evaluate_search import relevance, validate_cases


def load_labels():
    labels = json.loads((FIXTURE / 'relevance-labels.json').read_text())
    scenarios = {scenario['id']: (scenario, records) for scenario, _, _, records in load_fixture()}
    ids = set()
    for label in labels:
        if label['id'] in ids or label['result_field'] not in {'search', 'security_search'}:
            raise ValueError('Invalid comparison label')
        ids.add(label['id'])
        scenario, batches = scenarios[label['scenario_id']]
        current = {}
        for batch in batches:
            for record in batch:
                current[record[0]['id']] = record
        context = dict(scenario['context'])
        if label['result_field'] == 'security_search':
            context['role'] = 'security'
        validate_cases([dict(id=label['id'], question=scenario['question'], context=context,
                             expected_status=label['expected_status'], expected_passages=label['expected_passages'])], list(current.values()))
    return labels


def measure(report, labels):
    scenarios = {r['id']: r for r in report['results']}
    cases = []
    for label in labels:
        result = scenarios[label['scenario_id']][label['result_field']]
        metrics = relevance(label['expected_passages'], result['passages'])
        cases.append(dict(id=label['id'], expected_status=label['expected_status'], actual_status=result['status'],
                          status_matches=result['status'] == label['expected_status'],
                          expected_count=len(label['expected_passages']), retrieved_count=len(result['passages']),
                          **metrics))
    positive = [c for c in cases if c['expected_count']]
    negative = [c for c in cases if not c['expected_count']]
    quote_counts = [r['quotation_count'] for r in report['results']]
    verified = sum(r['quotation_count'] for r in report['results']
                   if r['citation_integrity'] is True or r['citation_integrity'] == 'verified')
    access = scenarios['S10']
    return dict(answerable_cases=len(positive), withholding_cases=len(negative),
                status_accuracy=mean(c['status_matches'] for c in cases),
                mean_recall_at_5=mean(c['recall'] for c in positive),
                mean_precision_at_returned_5=mean(c['precision'] for c in positive),
                false_positive_rate=mean(bool(c['retrieved_count']) for c in negative),
                withholding_accuracy=mean(c['status_matches'] and not c['retrieved_count'] and
                    not scenarios[label['scenario_id']][label['result_field']]['recommended_documents']
                    for c, label in zip(cases, labels) if not c['expected_count']),
                returned_quotation_count=sum(quote_counts),
                citation_integrity='not_applicable' if not sum(quote_counts) else 'verified' if verified == sum(quote_counts) else 'failed',
                authorization_checks={key: access[key] for key in ('platform_disclosure_absent', 'platform_revision_denied',
                    'revoked_revision_denied', 'revoked_search_disclosure_absent')}, cases=cases)


def compare(before, after):
    if before['dataset_sha256'] != after['dataset_sha256']:
        raise ValueError('Cannot compare changed questions or corpus')
    labels = load_labels()
    return dict(dataset_sha256=before['dataset_sha256'],
                labels_sha256=hashlib.sha256(json.dumps(labels, sort_keys=True).encode()).hexdigest(),
                scope='Exact evidence recall/precision, withholding and access checks; not answer correctness',
                baseline_method=before['retrieval_method'], current_method=after['retrieval_method'],
                before=measure(before, labels), after=measure(after, labels))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('after', type=Path)
    parser.add_argument('--before', type=Path, default=FIXTURE / 'baseline-v3.json')
    args = parser.parse_args()
    print(json.dumps(compare(json.loads(args.before.read_text()), json.loads(args.after.read_text())), indent=2))


if __name__ == '__main__':
    main()
