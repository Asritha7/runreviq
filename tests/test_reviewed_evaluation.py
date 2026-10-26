import copy
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from runreviq.evaluate_reviewed import (load_fixture, gold_groups, metrics, covers, aggregate,
                                       diagnose, evaluate_configuration, CONFIGURATIONS)


class ReviewedEvaluationTests(unittest.TestCase):
    def test_fixture_freeze_and_provenance(self):
        docs,cases,sources,freeze=load_fixture()
        self.assertEqual((len(docs),len(cases)),(26,60))
        self.assertEqual(freeze['provenance'],'implementation_authored_development')
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            (root/'freeze.json').write_text('{"files":{"changed.md":"wrong"}}')
            (root/'changed.md').write_text('changed')
            with self.assertRaises(ValueError):load_fixture(root)

    def test_span_containment_duplicates_and_partial_overlap(self):
        a=dict(document_id='A',revision=1,start_line=3,end_line=3)
        b=dict(a,document_id='B')
        groups=gold_groups([a,b],{'A':'# A\n\nSame text','B':'# B\n\nSame text'})
        self.assertEqual(len(groups),1)
        hits=[dict(a,start_line=2,end_line=4),b]
        result=metrics(groups,hits)
        self.assertEqual(result['recall'],1)
        self.assertEqual(result['precision_returned'],.5)
        self.assertEqual(result['precision_fixed_5'],.2)
        self.assertFalse(covers(dict(a,end_line=3),dict(a,end_line=4)))
        self.assertFalse(covers(dict(a,revision=2),a))
        self.assertEqual(metrics(groups,[])['recall'],0)

    def test_metric_denominators(self):
        base=dict(passed=False,result={'passages':[]},unauthorized_disclosures=[],inapplicable_passages=[],citation_integrity=True,recall=0,precision_returned=0,precision_fixed_5=0)
        rows=[dict(base,expected_status='selected',actual_status='conflict'),dict(base,expected_status='needs_context_or_review',actual_status='needs_context_or_review')]
        result=aggregate(rows)
        self.assertEqual(result['incorrect_abstention'],dict(numerator=1,denominator=1,value=1))
        self.assertEqual(result['false_conflict_rate'],dict(numerator=1,denominator=2,value=.5))
        self.assertEqual(result['appropriate_abstention']['value'],1)
        self.assertIsNone(aggregate([])['false_positive_rate']['value'])

    def test_missing_stage_diagnostics(self):
        label=dict(document_id='A',revision=1,start_line=3,end_line=3)
        docs=[dict(id='A',allowed_roles=['platform'])]
        trace=dict(selection=dict(status='selected',uncertain_documents=[],applicable_documents=['A']),effective_context=dict(role='platform'),candidates=[],rankable=[],qualified=[],pool=[])
        for stage,cause in [('candidates','source_range_omission'),('rankable','lexical_gate'),('qualified','relevance_ranking'),('pool','candidate_limit')]:
            self.assertEqual(diagnose([label],docs,trace),cause)
            trace[stage]=[label]
        self.assertEqual(diagnose([label],docs,trace),'result_limit_or_bundle_selection')

    def test_api_does_not_accept_offline_controls(self):
        from runreviq.api import SearchRequest
        body=dict(question='keys',service='identity-service',software_version='2.4.0',environment='production')
        for key in ['_diagnostics','_assemble']:
            with self.assertRaises(ValueError):SearchRequest.model_validate(dict(body,**{key:False}))

    @unittest.skipUnless(os.environ.get('RUNREVIQ_TEST_DATABASE')=='1','Requires disposable PostgreSQL')
    def test_paired_arms_preserve_integrity_and_hide_diagnostics(self):
        from runreviq.search import search
        docs,cases,sources,_=load_fixture()
        for config in CONFIGURATIONS:
            # All scenarios run against fresh disposable databases; no correctness
            # assertion hides known relevance failures in the development fixture.
            def checked_search(**kwargs):
                actual=search(**kwargs)
                plain=dict(kwargs);plain.pop('_diagnostics')
                self.assertEqual(actual,search(**plain))
                return actual
            with patch('runreviq.evaluate_reviewed.search',side_effect=checked_search):
                report=evaluate_configuration(config,docs,cases,sources)
            self.assertEqual(report['summary']['cases'],60)
            self.assertEqual(report['summary']['access_isolation_failures']['numerator'],0)
            self.assertEqual(report['summary']['citation_failures']['numerator'],0)
            if config!='legacy_paragraphs':
                self.assertEqual(report['summary']['inapplicable_passage_failures']['numerator'],0)
            self.assertTrue(all('trace' not in r['result'] and '_diagnostics' not in r['result'] for r in report['results']))
