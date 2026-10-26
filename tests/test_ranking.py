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

    def test_contextual_http_matching_preserves_specifics_and_explicit_restrictions(self):
        from runreviq.ranking import http_terms, numeric_match
        self.assertEqual(http_terms('401 authentication errors'), {'401'})
        self.assertEqual(http_terms('Set capacity to 401'), set())
        self.assertTrue(numeric_match('Inspect authentication diagnostics and JWKS.', ['401'], {'401'}))
        for source in ('For HTTP 503 errors, restart.',
                       'For HTTP 503 incidents only. For 401 see another document.',
                       'Restricted to HTTP 503. A 401 incident is different.',
                       'Only for HTTP 503 errors; 401 requires other diagnostics.',
                       'Not for HTTP 401 incidents.'):
            with self.subTest(source=source):
                self.assertFalse(numeric_match(source, ['401'], {'401'}))
        self.assertTrue(numeric_match('For HTTP 401 errors only, inspect keys.', ['401'], {'401'}))
        self.assertFalse(numeric_match('General authentication procedure BUILD-99.', ['401', 'BUILD-9'], {'401'}))
        self.assertFalse(numeric_match('Version 2.4.1', ['2.4.0'], set()))
        self.assertFalse(numeric_match('For HTTP 401 errors.', ['401', '503'], {'401', '503'}))

    def test_policy_priority_requires_relevant_reviewed_action_terms(self):
        candidates = [dict(document_id='A-1'), dict(document_id='B-1')]
        args = (candidates, ['signature', 'verify'], [['signature','verify']]*2, [[],[]])
        self.assertEqual(rank(*args, policy_terms=[[], ['signature','verify']])[0], candidates[1])
        self.assertEqual(rank(*args, policy_terms=[[], ['database','delete']])[0], candidates[0])
        self.assertEqual(rank(*args, policy_terms=[[], ['signature']])[0], candidates[0])
        self.assertEqual(rank([candidates[0]], ['signature','verify'], [['database','delete']], [[]],
                              policy_terms=[['signature','verify']]), [])

    def test_complementary_clauses_retain_body_evidence_requirement(self):
        candidates = [dict(document_id='A-1'), dict(document_id='B-1')]
        result = rank(candidates, ['jwks','failure','sign','key','identifier'],
                      [['jwks','failure'],['sign','key','identifier']], [[],[]],
                      query_groups=[['jwks','failure'],['sign','key','identifier']])
        self.assertEqual({p['document_id'] for p in result}, {'A-1','B-1'})
        self.assertEqual(rank([candidates[0]], ['jwks','failure'], [['unrelated']], [['jwks','failure']],
                              query_groups=[['jwks','failure']]), [])
