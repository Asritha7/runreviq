"""Ranking gates must not turn topic context or exclusions into evidence."""
import unittest

from runreviq.ranking import normalize_text, query_text, rank, searchable_text


class RankingTests(unittest.TestCase):
    def test_scope_exclusions_do_not_score_but_safety_instructions_remain(self):
        text='Inspect resolution. Certificate renewal is outside this procedure. Do not restart all replicas.'
        self.assertEqual(searchable_text(text),'Inspect resolution. Do not restart all replicas.')
        self.assertEqual(text,'Inspect resolution. Certificate renewal is outside this procedure. Do not restart all replicas.')
        self.assertEqual(searchable_text('This does not cover passwords.'),'')

    def test_question_scaffolding_and_action_nouns(self):
        self.assertEqual(query_text('How should I inspect JWKS failures?'),'I JWKS failures')
        self.assertEqual(normalize_text('Recovery verification validation'),'recover verify validate')

    def test_title_only_and_missing_concepts_cannot_qualify(self):
        candidates=[dict(document_id='A-1')]
        self.assertEqual(rank(candidates,['certificate','renew'],[['observe']],[['certificate','renew']]),[])
        self.assertEqual(rank(candidates,['database','upgrade','command','schema'],[['database']],[[]]),[])
        self.assertEqual(rank(candidates,[],[['database']],[[]]),[])

    def test_document_context_can_support_but_not_replace_passage_text(self):
        candidates=[dict(document_id='A-1',start_line=1),dict(document_id='A-1',start_line=4),dict(document_id='B-1',start_line=1)]
        selected=rank(candidates,['cache','eviction','memory'],[['cache','eviction'],['memory','inspect'],['unrelated']],[[],[],[]])
        self.assertIn(candidates[1],selected)
        self.assertNotIn(candidates[2],selected)

    def test_result_cap_and_ties_are_deterministic(self):
        candidates=[dict(document_id=f'A-{n}') for n in range(8)]
        self.assertEqual(rank(candidates,['pool'],[['pool']]*8,[[]]*8),candidates[:5])
