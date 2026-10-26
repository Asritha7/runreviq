"""External scenarios are isolated evidence fixtures, not answer-generation tests."""
import os
import unittest

from runreviq.evaluate_external import load_fixture, run, integrity_ok


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

    def test_integrity_exit_does_not_ignore_access_failure(self):
        result = dict(id='S10', citation_integrity=True, platform_disclosure_absent=True,
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
