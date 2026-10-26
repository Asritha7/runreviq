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
