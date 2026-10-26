"""Ranking gates must not turn topic context or exclusions into evidence."""
import unittest

from runreviq.ranking import normalize_text, query_text, rank, searchable_text, exact_terms, has_exact_terms


class RankingTests(unittest.TestCase):
    def test_scope_exclusions_do_not_score_but_safety_instructions_remain(self):
        text='Inspect resolution. Certificate renewal is outside this procedure. Do not restart all replicas.'
        self.assertEqual(searchable_text(text),'Inspect resolution. Do not restart all replicas.')
        self.assertEqual(text,'Inspect resolution. Certificate renewal is outside this procedure. Do not restart all replicas.')
        self.assertEqual(searchable_text('This does not cover passwords.'),'')

    def test_question_scaffolding_and_action_nouns(self):
        self.assertEqual(query_text('How should I inspect JWKS failures?'),'I JWKS failures')
        self.assertEqual(normalize_text('Recovery verification validation'),'recover verify verify')

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

    def test_operational_aliases_preserve_negation_and_unrelated_words(self):
        self.assertEqual(normalize_text('Do not requeue jobs on new instances.'), 'Do not retry tasks on new replicas.')
        self.assertEqual(normalize_text('jobless instantaneous'), 'jobless instantaneous')
        self.assertEqual(normalize_text('database slot'), 'database connection')

    def test_exact_codes_and_identifiers_respect_boundaries_and_punctuation(self):
        terms=exact_terms('HTTP 503 for BUILD-9 at version 2.4.0?')
        self.assertEqual(terms,['503','BUILD-9','2.4.0'])
        self.assertTrue(has_exact_terms('503: build-9, version 2.4.0.',terms))
        for text in ('5030 BUILD-9 2.4.0', '503 BUILD-99 2.4.0', '503 BUILD-9 2.4.01'):
            self.assertFalse(has_exact_terms(text,terms))
        self.assertTrue(has_exact_terms('MARKER-CODE.', ['MARKER-CODE']))
        self.assertEqual(exact_terms('What causes 503.'), ['503'])
