import unittest
from scripts.trace_external_pack import first_failure, classify


class TraceDiagnosisTests(unittest.TestCase):
    def setUp(self):
        self.p = dict(document_id='TEST-1',revision=1,start_line=2,end_line=2)
        self.events = [dict(stage='candidate_generation',passages=[self.p]),
                       dict(stage='lexical_gate',passage=self.p,decision='eligible'),
                       dict(stage='exclusion_gate',excluded=False,trigger_terms=[]),
                       dict(stage='coverage',passage=self.p,decision='document_coverage',coverage=.2,floor=.35)]
        self.case = dict(id='synthetic',expected_behavior='selected',required_passages=[self.p])
        self.row = dict(events=self.events,response=dict(status='insufficient_evidence',passages=[],applicable_documents=['TEST-1']))

    def test_first_decisive_gate_has_raw_evidence(self):
        diagnosis=first_failure(self.p,self.events)
        self.assertEqual(diagnosis['stage'],'document_coverage')
        self.assertEqual(diagnosis['evidence'],self.events[-1])
        self.events[1]['decision']='exact_terms'
        self.assertEqual(first_failure(self.p,self.events)['stage'],'exact_terms')

    def test_passage_scope_is_distinct_from_global_and_coverage(self):
        self.events[1]['decision']='passage_excluded_terms'
        self.assertEqual(first_failure(self.p,self.events)['stage'],'passage_excluded_terms')
        self.events[1]['decision']='eligible'
        self.assertEqual(first_failure(self.p,self.events)['stage'],'document_coverage')

    def test_global_exclusion_precedes_coverage(self):
        self.events[2].update(excluded=True,trigger_terms=['synthetic'])
        self.assertEqual(first_failure(self.p,self.events)['stage'],'excluded_sentence_terms')

    def test_missing_generation_and_unexplained_miss(self):
        self.events[0]['passages']=[]
        self.assertEqual(first_failure(self.p,self.events)['stage'],'candidate_generation')
        self.events[0]['passages']=[self.p]
        self.events[-1]['decision']='eligible'
        with self.assertRaises(ValueError):first_failure(self.p,self.events)

    def test_top_k_and_bundle_are_distinct_stages(self):
        self.events[-1]['decision']='eligible'
        self.events.append(dict(stage='selection',passage=self.p,decision='top_k',rank=6,limit=5))
        self.assertEqual(first_failure(self.p,self.events)['stage'],'top_k')
        self.events[-1]['decision']='selected'
        self.events.append(dict(stage='assembly_decision',passage=self.p,decision='duplicate_role_quote'))
        self.assertEqual(first_failure(self.p,self.events)['stage'],'duplicate_role_quote')

    def test_metadata_and_prose_conflict_are_not_ranking_misses(self):
        self.row['response']['status']='needs_context_or_review'
        self.events.insert(0,dict(stage='applicability',status='needs_context_or_review'))
        self.assertEqual(classify(self.case,self.row)['category'],'metadata_context_review')
        self.row['response']['status']='selected';self.case['expected_behavior']='conflict'
        self.assertEqual(classify(self.case,self.row)['category'],'prose_conflict_not_graded_as_ranking')

    def test_applicability_miss_not_called_rank_failure(self):
        self.row['response']['applicable_documents']=[]
        self.assertEqual(classify(self.case,self.row)['category'],'applicability_miss')
