"""External scenarios are isolated evidence fixtures, not answer-generation tests."""
import os
import unittest
from unittest.mock import patch

from runreviq.evaluate_external import load_fixture, run, integrity_ok, verify_quotes


class ExternalFixtureTests(unittest.TestCase):
    def test_all_sources_validate_and_expected_answers_are_not_ingested(self):
        prepared = load_fixture()
        self.assertEqual(len(prepared), 10)
        for scenario, _, _, batches in prepared:
            for batch in batches:
                for doc, metadata, content, _ in batch:
                    self.assertNotIn('expected', metadata)
                    self.assertNotIn('question', metadata)
                    self.assertNotIn(scenario['question'], content)
                    self.assertNotIn(scenario['expected'], content)
                    self.assertEqual(doc['service'], scenario['context']['service'])

    def test_citation_status_distinguishes_absent_verified_and_failed(self):
        import hashlib
        empty = dict(passages=[], conflict_details=[])
        with patch('runreviq.evaluate_external.load_authorized_revision') as loader:
            self.assertEqual(verify_quotes(empty, 'platform', 'unused'), 'not_applicable')
            loader.assert_not_called()
            content = 'Exact source.'
            hit = dict(document_id='RB-1', revision=1, start_line=1, end_line=1,
                       quote=content, source_sha256=hashlib.sha256(content.encode()).hexdigest())
            loader.return_value = dict(content=content, is_current=True, guidance_status='active')
            result = dict(passages=[hit], conflict_details=[])
            self.assertEqual(verify_quotes(result, 'platform', 'unused'), 'verified')
            hit['quote'] = 'Invented source.'
            self.assertEqual(verify_quotes(result, 'platform', 'unused'), 'failed')
            loader.return_value = None
            self.assertEqual(verify_quotes(result, 'platform', 'unused'), 'failed')
        self.assertTrue(integrity_ok(dict(results=[dict(id='S01', citation_integrity='not_applicable')])))
        self.assertFalse(integrity_ok(dict(results=[dict(id='S01', citation_integrity='failed')])))

    def test_integrity_exit_does_not_ignore_access_failure(self):
        result = dict(id='S10', citation_integrity='verified', platform_disclosure_absent=True,
                      platform_revision_denied=True, revoked_revision_denied=True,
                      revoked_search_disclosure_absent=True)
        self.assertTrue(integrity_ok(dict(results=[result])))
        for key in list(result)[1:]:
            with self.subTest(key=key):
                self.assertFalse(integrity_ok(dict(results=[dict(result, **{key: False})])))


@unittest.skipUnless(os.environ.get('RUNREVIQ_TEST_DATABASE') == '1', 'Requires disposable PostgreSQL')
class ExternalDatabaseTests(unittest.TestCase):
    def test_isolated_context_conflicts_history_and_revocation(self):
        report = run()
        self.assertTrue(integrity_ok(report))
        from runreviq.compare_external import load_labels, measure
        measured = measure(report, load_labels())
        self.assertEqual(measured['mean_recall_at_5'], 1)
        self.assertEqual(measured['mean_precision_at_returned_5'], 1)
        self.assertEqual(measured['withholding_accuracy'], 1)
        self.assertEqual(measured['false_positive_rate'], 0)
        cases = {case['id']: case for case in report['results']}
        self.assertEqual(cases['S01']['selection']['recommended_documents'], ['RB-101'])
        self.assertEqual(cases['S02']['search']['status'], 'conflict')
        self.assertEqual(cases['S02']['quotation_count'], 2)
        self.assertEqual(cases['S03']['search']['questions'][0]['field'], 'key_rotation_enabled')
        self.assertEqual([r['guidance_status'] for r in cases['S04']['history']], ['superseded', 'active'])
        self.assertEqual(cases['S05']['selection']['conflicts'], [])
        self.assertEqual(cases['S07']['selection']['recommended_documents'], ['POL-702'])
        self.assertTrue(cases['S10']['security_revision_available'])
        self.assertEqual(cases['S10']['stored_answer_regeneration'], 'not_implemented_no_answer_store')


class ExternalComparisonTests(unittest.TestCase):
    def test_frozen_baseline_metrics_include_empty_results_and_reject_changed_corpus(self):
        import copy
        import json
        from runreviq.evaluate_external import FIXTURE
        from runreviq.compare_external import compare, load_labels, measure
        baseline = json.loads((FIXTURE / 'baseline-v3.json').read_text())
        metrics = measure(baseline, load_labels())
        self.assertEqual(metrics['answerable_cases'], 8)
        self.assertEqual(metrics['withholding_cases'], 3)
        self.assertEqual(metrics['mean_recall_at_5'], 1.5 / 8)
        self.assertEqual(metrics['mean_precision_at_returned_5'], 2 / 8)
        self.assertEqual(metrics['false_positive_rate'], 0)
        self.assertEqual(metrics['returned_quotation_count'], 4)
        empty = copy.deepcopy(baseline)
        for case in empty['results']:
            case['quotation_count'] = 0
            case['citation_integrity'] = 'not_applicable'
            for field in ('search', 'security_search'):
                if field in case:
                    case[field]['passages'] = []
                    case[field]['recommended_documents'] = []
        self.assertEqual(measure(empty, load_labels())['citation_integrity'], 'not_applicable')
        changed = dict(baseline, dataset_sha256='changed')
        with self.assertRaises(ValueError):
            compare(baseline, changed)
