import copy
import unittest

from runreviq.evidence import validate_profile, validate_prohibitions
from runreviq.api import ReviewMetadata

PROFILE = dict(schema_version=1, http_scope=dict(mode='only', codes=[401]),
               units=[dict(role='procedure',start_line=3,end_line=3)])
SOURCE = '# Test\n\nInspect signing keys.\n'


class EvidenceContractTests(unittest.TestCase):
    def test_strict_schema_ranges_and_source_validation(self):
        self.assertEqual(validate_profile(PROFILE, SOURCE), PROFILE)
        mutations = [dict(schema_version=True), dict(http_scope=dict(mode='general',codes=[401])),
                     dict(http_scope=dict(mode='only',codes=[])),
                     dict(http_scope=dict(mode='only',codes=[True])),
                     dict(http_scope=dict(mode='only',codes=[401,401])),
                     dict(units=[dict(role='approved',start_line=3,end_line=3)]),
                     dict(units=[dict(role='procedure',start_line=3,end_line=99)]),
                     dict(units=[dict(role='procedure',start_line=1,end_line=1)]),
                     dict(units=PROFILE['units']*2), dict(authoritative=True)]
        for change in mutations:
            with self.subTest(change=change), self.assertRaises(ValueError):
                validate_profile(dict(PROFILE,**change), SOURCE)
        self.assertIsNone(validate_profile(dict(PROFILE,http_scope=None),SOURCE)['http_scope'])

    def test_prohibition_requires_cited_normative_claim(self):
        profile=copy.deepcopy(PROFILE);profile['units'][0]['role']='prohibition'
        for claims in ([],[dict(rule='must',citation=dict(start_line=3,end_line=3))],
                       [dict(rule='must_not')],[dict(rule='must_not',citation=dict(start_line=4,end_line=4))]):
            with self.assertRaises(ValueError):validate_prohibitions(profile,claims)
        validate_prohibitions(profile,[dict(rule='must_not',citation=dict(start_line=3,end_line=3))])

    def test_api_preserves_unknown_scope_without_granting_authority(self):
        from tests.test_publications import META
        raw=dict(META,evidence_profile=dict(PROFILE,http_scope=None))
        self.assertEqual(ReviewMetadata.model_validate(raw).model_dump(exclude_unset=True),raw)
        with self.assertRaises(ValueError):ReviewMetadata.model_validate(dict(META,evidence_profile=dict(PROFILE,schema_version=True)))

    def test_http_scope_precedes_conflict_and_unknown_context_does_not_guess(self):
        from runreviq.selector import select, load_documents
        base=copy.deepcopy(load_documents()[1]);base['claims']=[dict(action='restart',rule='must')]
        a=dict(base,id='SCOPE-1',evidence_profile=copy.deepcopy(PROFILE))
        b=copy.deepcopy(a);b.update(id='SCOPE-2',claims=[dict(action='restart',rule='must_not')])
        b['evidence_profile']['http_scope']['codes']=[503]
        context=dict(role='platform',service='identity-service',software_version='2.4.0',environment='production',key_rotation_enabled=True)
        self.assertEqual(select([a,b],**context,http_status=401)['recommended_documents'],['SCOPE-1'])
        self.assertEqual(select([a,b],**context,http_status=503)['recommended_documents'],['SCOPE-2'])
        missing=select([a,b],**context)
        self.assertEqual(missing['status'],'needs_context_or_review')
        self.assertEqual(missing['questions'][0]['allowed_answers'],[401,503])
        b['allowed_roles']=['security']
        self.assertEqual(select([a,b],**context)['questions'][0]['allowed_answers'],[401])

    def test_question_context_disagreement_is_invalid_not_a_server_failure(self):
        from runreviq.api import SearchRequest
        body=dict(question='HTTP 503 authentication errors',http_status=401,
                  service='identity-service',software_version='2.4.0',environment='production')
        with self.assertRaises(ValueError):SearchRequest.model_validate(body)
        with self.assertRaises(ValueError):SearchRequest.model_validate(dict(body,http_status=True))

    def test_bundle_deduplicates_without_erasing_negation_and_preserves_roles(self):
        from runreviq.evidence import bundle
        passages=[dict(quote='Inspect signing keys.',evidence_role='procedure',document_id='A'),
                  dict(quote='Inspect   signing keys.',evidence_role='procedure',document_id='B'),
                  dict(quote='Do not inspect signing keys.',evidence_role='prohibition',document_id='C'),
                  dict(quote='Old incident.',evidence_role='historical',document_id='D'),
                  dict(quote='Check metrics.',evidence_role='monitoring',document_id='E')]
        result=bundle(passages)
        self.assertEqual([p['document_id'] for p in result],['A','C','D','E'])
