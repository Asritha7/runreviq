import unittest
from scripts.generalization_metrics import summarize


class GeneralizationMetricsTests(unittest.TestCase):
    def row(self, **changes):
        return dict(dict(required_count=1,returned_count=0,matched_count=0,
            eligible_required_evidence=True,inappropriate_evidence_abstention=True,
            labeled_wrong_source_count=0,unadjudicated_extra_count=0,repository_latency_ms=[1.,2.,3.]),**changes)

    def test_recall_precision_and_wrong_sources_have_separate_denominators(self):
        rows=[self.row(returned_count=3,matched_count=1,inappropriate_evidence_abstention=False,
                       labeled_wrong_source_count=1,unadjudicated_extra_count=1),self.row()]
        r=summarize(rows)
        self.assertEqual(r['evidence_recall'],.5)
        self.assertEqual(r['strict_returned_precision'],1/3)
        self.assertEqual(r['labeled_wrong_source_passages'],1)
        self.assertEqual(r['labeled_wrong_source_case_rate'],.5)
        self.assertEqual(r['unadjudicated_extra_passages'],1)
        self.assertEqual(r['inappropriate_evidence_abstention_rate'],.5)
        self.assertIsNone(r['semantic_wrong_procedure_rate'])

    def test_policy_block_not_counted_as_inappropriate_retrieval_abstention(self):
        r=summarize([self.row(eligible_required_evidence=False,inappropriate_evidence_abstention=False)])
        self.assertEqual(r['eligible_required_cases'],0)
        self.assertIsNone(r['inappropriate_evidence_abstention_rate'])
        self.assertEqual(r['evidence_recall'],0)

    def test_latency_and_empty_values_are_explicit(self):
        r=summarize([self.row()])
        self.assertEqual(r['repository_latency_ms'],dict(p50=2.,p95=3.,samples=3))
        self.assertIsNone(summarize([])['evidence_recall'])
        self.assertIsNone(summarize([])['strict_returned_precision'])
