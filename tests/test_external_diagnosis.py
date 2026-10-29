import copy
import unittest
from scripts.diagnose_external_pack import diagnose, MODES


class DiagnosisTests(unittest.TestCase):
    def setUp(self):
        self.passage = dict(document_id='SYNTHETIC', revision=1, start_line=2, end_line=2)
        self.key = dict(cases=[dict(id='test', expected_behavior='selected',
            required_passages=[self.passage], forbidden_passage_ids=[])])
        self.provenance = dict(input_sha256='frozen', adaptation_limits={})
        self.reports = {mode: dict(mode=mode,
            provenance=dict(input_sha256='frozen', java_jar_sha256='same-artifact'),
            results=[dict(id='test', structured_integrity_ok=True, actual_status='insufficient_evidence',
                raw_response=dict(status='insufficient_evidence', passages=[], applicable_documents=['SYNTHETIC']))])
            for mode in MODES}

    def result(self):
        return diagnose(self.key, self.provenance, self.reports)

    def test_applicable_miss_is_not_claimed_to_be_a_threshold_failure(self):
        result = self.result()
        self.assertEqual(result['differing_cases'], [])
        self.assertEqual(result['modes'][MODES[0]]['cases'][0]['boundaries'],
                         ['missing_passages_from_applicable_documents'])

    def test_retrieving_conflict_evidence_does_not_count_as_semantic_success(self):
        self.key['cases'][0]['expected_behavior'] = 'conflict'
        for report in self.reports.values():
            row = report['results'][0]
            row['actual_status'] = row['raw_response']['status'] = 'selected'
            row['raw_response']['passages'] = [self.passage]
        self.assertIn('expected_conflict_not_reported', self.result()['modes'][MODES[0]]['cases'][0]['boundaries'])

    def test_metadata_block_keeps_adaptation_limits(self):
        self.provenance['adaptation_limits'] = {'DISTRACTOR@1': ['unsupported_precondition:synthetic']}
        for report in self.reports.values():
            row = report['results'][0]
            row['actual_status'] = row['raw_response']['status'] = 'needs_context_or_review'
            row['raw_response']['uncertain_documents'] = ['DISTRACTOR']
        row = self.result()['modes'][MODES[0]]['cases'][0]
        self.assertIn('context_or_metadata_block', row['boundaries'])
        self.assertIn('DISTRACTOR@1', row['adaptation_limits'])

    def test_rejects_changed_inputs_artifacts_cases_and_integrity(self):
        mutations = [lambda r: r['provenance'].update(input_sha256='changed'),
                     lambda r: r['provenance'].update(java_jar_sha256='different'),
                     lambda r: r['results'].append(copy.deepcopy(r['results'][0])),
                     lambda r: r['results'][0].update(structured_integrity_ok=False)]
        for mutate in mutations:
            with self.subTest(mutation=mutate):
                reports = copy.deepcopy(self.reports)
                mutate(reports[MODES[0]])
                with self.assertRaises(ValueError):
                    diagnose(self.key, self.provenance, reports)

    def test_changed_passages_are_reported_and_forbidden_detected(self):
        self.key['cases'][0]['forbidden_passage_ids'] = ['FORBIDDEN']
        self.reports[MODES[1]]['results'][0]['raw_response']['passages'] = [dict(self.passage, document_id='FORBIDDEN')]
        result = self.result()
        self.assertEqual(result['differing_cases'], ['test'])
        self.assertIn('forbidden_passage_returned', result['modes'][MODES[1]]['cases'][0]['boundaries'])
