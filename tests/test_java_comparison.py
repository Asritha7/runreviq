import copy
import json
import unittest
from runreviq.database import ROOT
from runreviq.compare_java import compare

class JavaComparisonTests(unittest.TestCase):
    def setUp(self):
        root=ROOT/'datasets/java-evaluation'
        self.baseline=json.loads((root/'realistic-v5.json').read_text())
        self.candidate=json.loads((root/'realistic-focused-v6.json').read_text())

    def test_same_input_comparison_keeps_gains_and_remaining_strict_failure_visible(self):
        result=compare(self.baseline,self.candidate)
        self.assertEqual(result['recall_status_or_integrity_regressions'],[])
        self.assertGreater(result['metrics']['precision_at_returned_5']['delta'],0)
        self.assertEqual(result['candidate_strict_quality_gate'],'failed')
        self.assertEqual(result['independence'],'same_author_development')

    def test_changed_inputs_missing_cases_and_false_aggregates_rejected(self):
        for change in ('fingerprint','question','labels','duplicate','method','aggregate','nan','provenance'):
            candidate=copy.deepcopy(self.candidate)
            if change=='fingerprint': candidate['dataset_sha256']='0'*64
            if change=='question': candidate['results'][0]['question']='Changed question'
            if change=='labels': candidate['results'][0]['missing']=[dict(document_id='UNKNOWN-1',revision=1,start_line=1,end_line=1)]
            if change=='duplicate': candidate['results'].append(candidate['results'][0])
            if change=='method': candidate['retrieval_method']='unknown'
            if change=='aggregate': candidate['passed']=0
            if change=='nan': candidate['precision_at_returned_5']=float('nan')
            if change=='provenance': candidate['independence']='independent'
            with self.subTest(change=change),self.assertRaises(ValueError):compare(self.baseline,candidate)

    def test_changed_outcome_is_reported_not_hidden(self):
        from runreviq.evaluate_java import summarize
        candidate=copy.deepcopy(self.candidate)
        row=next(r for r in candidate['results'] if not r['withheld'])
        row['passed']=False;row['exact_evidence_set']=False;row['integrity_ok']=False
        candidate.update(summarize(candidate['results']))
        result=compare(self.baseline,candidate)
        self.assertIn(row['id'],result['recall_status_or_integrity_regressions'])
