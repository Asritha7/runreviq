import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from scripts.evaluate_external_pack import load_pack


class ExternalPackTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.sources=self.root/'sources';self.sources.mkdir()
        self.text='# Synthetic adapter test\n\nInspect synthetic queue counters.\n'
        (self.sources/'TEST-1.md').write_text(self.text)
        self.doc=dict(file='TEST-1.md',document_id='TEST-1',revision=1,service='synthetic-adapter',
            software_version_range='>=1.0.0,<2.0.0',environment='staging',allowed_roles=['platform'],
            evidence_role='procedure',precondition='any',http_scope='any',approval_status='approved',
            source_line_start=3,source_line_end=3,sha256=hashlib.sha256(self.text.encode()).hexdigest())
        self.key=dict(cases=[dict(id='adapter-1',question='Synthetic adapter-only question',
            required_passages=[dict(document_id='TEST-1',revision=1,start_line=3,end_line=3,exact_text='Inspect synthetic queue counters.')])])
    def load(self):
        (self.sources/'manifest.json').write_text(json.dumps(dict(documents=[self.doc])))
        gold=self.root/'gold.json';gold.write_text(json.dumps(self.key))
        return load_pack(self.sources,gold,self.root/'stage')
    def test_supported_fixture_and_gold_remain_separate(self):
        docs,key,provenance=self.load()
        self.assertEqual(docs[0]['claims'],[])
        self.assertTrue(docs[0]['metadata_verified'])
        self.assertEqual(key,self.key)
        self.assertEqual(list((self.root/'stage').rglob('*.json')),[])
        self.assertEqual(provenance['approvals'],'simulated_fixture_metadata_only')
    def test_unknown_precondition_is_not_silently_generalized(self):
        self.doc['precondition']='special_mode'
        docs,_,provenance=self.load()
        self.assertFalse(docs[0]['metadata_verified'])
        self.assertIn('unsupported_precondition:special_mode',provenance['adaptation_limits']['TEST-1@1'])
    def test_prohibition_label_does_not_create_authoritative_claim(self):
        self.doc['evidence_role']='prohibition'
        docs,_,provenance=self.load()
        self.assertEqual(docs[0]['claims'],[]);self.assertEqual(docs[0]['evidence_profile']['units'],[])
        self.assertTrue(provenance['adaptation_limits']['TEST-1@1'])
    def test_hash_mismatch_rejected(self):
        self.doc['sha256']='0'*64
        with self.assertRaises(ValueError):self.load()
    def test_gold_quote_mismatch_rejected(self):
        self.key['cases'][0]['required_passages'][0]['exact_text']='Not in the source'
        with self.assertRaises(ValueError):self.load()
