import copy
import hashlib
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
from scripts.freeze_evaluation import CATEGORIES, METRICS, GUARDS, freeze, verify


class EvaluationFreezeTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root=Path(self.tmp.name);self.sources=self.root/'sources';self.sources.mkdir()
        text='# Synthetic freeze test\n\nInspect synthetic queue counters.\n'
        (self.sources/'TEST-1.md').write_text(text)
        doc=dict(file='TEST-1.md',document_id='TEST-1',revision=1,service='synthetic-freeze',
            software_version_range='>=1.0.0,<2.0.0',environment='staging',allowed_roles=['platform'],
            evidence_role='procedure',precondition='any',http_scope='any',approval_status='approved',
            source_line_start=3,source_line_end=3,sha256=hashlib.sha256(text.encode()).hexdigest())
        self.write(self.sources/'manifest.json',dict(documents=[doc]))
        self.gold=self.root/'gold.json'
        self.key=dict(cases=[dict(id='synthetic',expected_behavior='selected',forbidden_passage_ids=[],question='Synthetic freeze-only query?',
            context=dict(role='platform',service='synthetic-freeze',software_version='1.1.0',environment='staging'),
            required_passages=[dict(document_id='TEST-1',revision=1,start_line=3,end_line=3,exact_text='Inspect synthetic queue counters.')])])
        self.write(self.gold,self.key)
        self.known=self.root/'known.json';self.write(self.known,dict(cases=[dict(question='Known synthetic question')]))
        self.protocol=self.root/'protocol.json'
        self.plan=dict(schema_version=1,dataset_id='synthetic_test_not_validation',
            provenance=dict(author_type='ai',author='Synthetic fixture',prior_exposure='inspected',
                independence_declaration='Synthetic unit test, not independent',source_metadata_review='pending'),
            coverage={'synthetic':sorted(CATEGORIES)},
            acceptance=dict({m: 0 if m.endswith('_max') else 1 for m in METRICS},**GUARDS,
                human_review_required=True,unresolved_judgments_max=0,rationale='Synthetic parser checks only'))
        self.write(self.protocol,self.plan)
        self.output=self.root/'freeze.json'

    def write(self,path,value): path.write_text(json.dumps(value))
    def run_freeze(self): return freeze(self.sources,self.gold,self.protocol,[self.known],self.output)

    def test_freeze_verify_preserves_inputs_and_no_independence_claim(self):
        before={p:p.read_bytes() for p in self.root.rglob('*') if p.is_file()}
        result=self.run_freeze()
        self.assertEqual(result['holdout_status'],'development_only')
        self.assertEqual(result['independence'],'not_verified')
        self.assertEqual(result,verify(self.output))
        self.assertEqual(stat.S_IMODE(self.output.stat().st_mode),0o600)
        for p,data in before.items():self.assertEqual(p.read_bytes(),data)
        with self.assertRaises(ValueError):self.run_freeze()

    def test_declared_human_no_exposure_still_not_verified(self):
        self.plan['provenance'].update(author_type='human',prior_exposure='none_declared')
        self.write(self.protocol,self.plan)
        r=self.run_freeze()
        self.assertEqual(r['holdout_status'],'reserved_claim_unverified')
        self.assertEqual(r['readiness'],'blocked_pending_authorized_independent_human_review')

    def test_exact_known_overlap_downgrades_reserved_claim(self):
        self.plan['provenance'].update(author_type='human',prior_exposure='none_declared')
        self.write(self.protocol,self.plan)
        self.write(self.known,dict(cases=[dict(question='  SYNTHETIC   freeze-only QUERY? ')]))
        r=self.run_freeze()
        self.assertEqual(r['holdout_status'],'development_only')
        self.assertIn('known_question_overlap',r['holdout_reasons'])
        self.assertEqual(len(r['exact_known_question_overlap_hashes']),1)

    def test_input_mutations_rejected_after_freeze(self):
        self.run_freeze()
        for p in (self.protocol,self.gold,self.known,self.sources/'TEST-1.md'):
            original=p.read_bytes();p.write_bytes(original+b' ')
            with self.subTest(path=p),self.assertRaises(ValueError):verify(self.output)
            p.write_bytes(original)

    def test_receipt_cannot_change_criteria_while_inputs_stay_frozen(self):
        r=self.run_freeze();r['protocol']['acceptance']['task_mismatch_rate_max']=1
        self.write(self.output,r)
        with self.assertRaises(ValueError):verify(self.output)
        payload={k:v for k,v in r.items() if k!='receipt_sha256'}
        r['receipt_sha256']=hashlib.sha256(json.dumps(payload,sort_keys=True,allow_nan=False).encode()).hexdigest()
        self.write(self.output,r)
        with self.assertRaises(ValueError):verify(self.output)

    def test_implementation_change_rejected(self):
        self.run_freeze()
        with patch('scripts.freeze_evaluation.code_hashes',return_value={'changed':'hash'}):
            with self.assertRaises(ValueError):verify(self.output)

    def test_invalid_safety_thresholds_and_coverage_rejected(self):
        variants=[]
        for key,value in [('human_review_required',False),('unauthorized_disclosures_max',1),
                          ('unresolved_judgments_max',1),('task_mismatch_rate_max',float('nan')),
                          ('required_evidence_recall_min',True),('required_evidence_recall_min',1.1)]:
            p=copy.deepcopy(self.plan);p['acceptance'][key]=value;variants.append(p)
        p=copy.deepcopy(self.plan);p['coverage']['synthetic'].pop();variants.append(p)
        p=copy.deepcopy(self.plan);p['coverage']['unknown']=p['coverage'].pop('synthetic');variants.append(p)
        for p in variants:
            self.write(self.protocol,p)
            with self.assertRaises(ValueError):self.run_freeze()
            self.assertFalse(self.output.exists())

    def test_hash_mismatch_or_no_known_sets_rejected(self):
        with self.assertRaises(ValueError):freeze(self.sources,self.gold,self.protocol,[],self.output)
        (self.sources/'TEST-1.md').write_text('changed source')
        with self.assertRaises(ValueError):self.run_freeze()
        self.assertFalse(self.output.exists())

    def test_symlink_inputs_and_output_are_rejected(self):
        original=self.known.read_bytes();self.known.unlink();self.known.symlink_to(self.gold)
        with self.assertRaises(ValueError):self.run_freeze()
        self.known.unlink();self.known.write_bytes(original)
        self.output.symlink_to(self.root/'not-yet-created')
        with self.assertRaises(FileExistsError):self.run_freeze()
        self.assertFalse((self.root/'not-yet-created').exists())

    def test_receipt_stays_outside_ingested_corpus(self):
        with self.assertRaises(ValueError):freeze(self.sources,self.gold,self.protocol,[self.known],self.sources/'freeze.json')

    def test_duplicate_normalized_questions_rejected(self):
        duplicate=copy.deepcopy(self.key['cases'][0]);duplicate['id']='second'
        duplicate['question']='  SYNTHETIC FREEZE-ONLY QUERY? '
        self.key['cases'].append(duplicate);self.write(self.gold,self.key)
        self.plan['coverage']['second']=sorted(CATEGORIES);self.write(self.protocol,self.plan)
        with self.assertRaises(ValueError):self.run_freeze()
        self.assertFalse(self.output.exists())

    def test_inaccessible_and_forbidden_positive_labels_rejected(self):
        for field in ('role','forbidden'):
            key=copy.deepcopy(self.key)
            if field=='role': key['cases'][0]['context']['role']='security'
            else: key['cases'][0]['forbidden_passage_ids']=['TEST-1']
            self.write(self.gold,key)
            with self.subTest(field=field),self.assertRaises(ValueError):self.run_freeze()
            self.assertFalse(self.output.exists())

    def test_builtin_overlap_corpus_change_invalidates_receipt(self):
        (self.root/'pyproject.toml').write_text('# synthetic project configuration')
        datasets=self.root/'datasets';datasets.mkdir()
        source=datasets/'prior.md';source.write_text('Prior synthetic passage')
        with patch('scripts.freeze_evaluation.ROOT',self.root), patch('scripts.evaluate_external_pack.ROOT',self.root):
            self.run_freeze()
            verify(self.output)
            source.write_text('Modified prior synthetic passage')
            with self.assertRaisesRegex(ValueError,'overlap corpus changed'):verify(self.output)
            source.write_text('Prior synthetic passage')
            self.write(datasets/'new.json',{'question':'Synthetic freeze-only query?'})
            with self.assertRaisesRegex(ValueError,'overlap corpus changed'):verify(self.output)
