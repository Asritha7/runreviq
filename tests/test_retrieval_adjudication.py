import copy
import json
import stat
import tempfile
import unittest
from pathlib import Path
from scripts.adjudicate_retrieval import MODES, build, canonical, digest, prepare, score


class RetrievalAdjudicationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.gold = dict(cases=[dict(id=role, question='Inspect synthetic queues?', context=dict(role=role))
                               for role in ('platform', 'security')])
        self.gold_path = self.root/'gold.json'
        self.gold_path.write_bytes(canonical(self.gold))
        self.gold_hash = digest(self.gold_path.read_bytes())
        rows = []
        for mode in MODES:
            for role in ('platform', 'security'):
                p = dict(document_id=role+'-source', revision=1, start_line=2, end_line=2,
                         source_sha256='a'*64, quote=role+' private passage', evidence_role='procedure')
                rows.append(dict(mode=mode, id=role, events=[dict(stage='authorized_snapshot', documents=[p])],
                                 response=dict(passages=[p], applicable_documents=[p['document_id']])))
        self.report = dict(frozen_file_hashes={'gold':self.gold_hash}, dataset_use='development_regression_not_unseen_validation',
                           git_dirty=False, code_revision='test-revision', guarantees=['reader_snapshot_only',
                           'source_publication_state_unchanged','frozen_inputs_unchanged'], traces=rows)
        self.report_path = self.root/'report.json'
        self.report_path.write_bytes(canonical(self.report))
        self.directory = self.root/'packet'
        prepare(self.report_path, self.gold_path, 'platform', self.directory)
        self.labels_path = self.directory/'labels-template.json'
        self.output = self.root/'result.json'

    def labels(self, label='relevant'):
        labels = json.loads(self.labels_path.read_text())
        labels.update(reviewer='Synthetic reviewer', independence_attestation='Synthetic unit-test fixture, not validation')
        for j in labels['judgments']:
            j.update(label=label, rationale='Synthetic reasoning')
        self.labels_path.write_bytes(canonical(labels))
        return labels

    def score(self):
        return score(self.report_path, self.gold_path, self.directory, self.labels_path, self.output)

    def test_packet_is_blinded_deduplicated_and_role_scoped(self):
        packet = json.loads((self.directory/'packet.json').read_text())
        self.assertEqual(len(packet['items']), 1)
        raw = canonical(packet).decode()
        for secret in ('security-source', 'security private passage', *MODES, 'frozen_file_hashes', 'score', 'required_passages'):
            self.assertNotIn(secret, raw)
        self.assertEqual(stat.S_IMODE(self.directory.stat().st_mode), 0o700)
        for p in self.directory.iterdir():
            self.assertEqual(stat.S_IMODE(p.stat().st_mode), 0o600)

    def test_pending_review_never_produces_score(self):
        with self.assertRaises(ValueError): self.score()
        self.assertFalse(self.output.exists())
        self.labels('pending')
        with self.assertRaises(ValueError): self.score()
        self.assertFalse(self.output.exists())

    def test_uncertainty_is_not_counted_as_correct(self):
        self.labels('uncertain')
        r = self.score()
        for m in r['metrics'].values():
            self.assertIsNone(m['decisive_precision'])
            self.assertEqual(m['relevance_lower_bound'], 0)
            self.assertEqual(m['relevance_upper_bound'], 1)
        self.assertEqual(r['independence_status'], 'reviewer_attested_not_verified')

    def test_wrong_procedure_is_counted_for_each_returning_arm(self):
        self.labels('wrong_procedure')
        r = self.score()
        for m in r['metrics'].values():
            self.assertEqual(m['observed_wrong_procedure_rate'], 1)
            self.assertEqual(m['decisive_precision'], 0)

    def test_mutated_packet_or_manifest_rejected(self):
        self.labels()
        for name in ('packet.json', 'operator-manifest.json'):
            p = self.directory/name
            original = p.read_bytes()
            value = json.loads(original)
            value['role'] = 'security'
            p.write_bytes(canonical(value))
            with self.assertRaises(ValueError): self.score()
            p.write_bytes(original)
        self.assertFalse(self.output.exists())

    def test_changed_frozen_report_rejected(self):
        self.labels()
        self.report['code_revision'] = 'another-revision'
        self.report_path.write_bytes(canonical(self.report))
        with self.assertRaises(ValueError): self.score()
        self.assertFalse(self.output.exists())

    def test_malformed_labels_missing_duplicate_and_unknown(self):
        good = self.labels()
        variants = []
        for field, value in [('reviewer', ''), ('independence_attestation', ''), ('packet_sha256', 'wrong')]:
            v = copy.deepcopy(good); v[field] = value; variants.append(v)
        for entries in ([], good['judgments']*2, [dict(item_id='unknown',label='relevant',rationale='test')]):
            v = copy.deepcopy(good); v['judgments'] = entries; variants.append(v)
        v = copy.deepcopy(good); v['judgments'][0]['rationale'] = ''; variants.append(v)
        for v in variants:
            with self.subTest(v=v):
                self.labels_path.write_bytes(canonical(v))
                with self.assertRaises(ValueError): self.score()
        self.assertFalse(self.output.exists())

    def test_snapshot_eligibility_and_complete_arms_required(self):
        for change in ('snapshot', 'applicability', 'missing_arm', 'dirty', 'gold'):
            r = copy.deepcopy(self.report)
            if change == 'snapshot': r['traces'][0]['events'][0]['documents'] = []
            if change == 'applicability': r['traces'][0]['response']['applicable_documents'] = []
            if change == 'missing_arm': r['traces'].pop()
            if change == 'dirty': r['git_dirty'] = True
            if change == 'gold': r['frozen_file_hashes'] = {}
            with self.subTest(change=change), self.assertRaises(ValueError):
                build(r, self.gold, 'hash', self.gold_hash, 'platform')

    def test_no_overwrite_and_frozen_inputs_unchanged(self):
        originals = {p:p.read_bytes() for p in (self.report_path,self.gold_path)}
        with self.assertRaises(FileExistsError):
            prepare(self.report_path,self.gold_path,'platform',self.directory)
        self.labels(); self.score()
        first = self.output.read_bytes()
        with self.assertRaises(FileExistsError): self.score()
        self.assertEqual(self.output.read_bytes(),first)
        for p, raw in originals.items(): self.assertEqual(p.read_bytes(),raw)

    def test_symlink_output_does_not_overwrite_target(self):
        self.labels()
        self.output.symlink_to(self.gold_path)
        before = self.gold_path.read_bytes()
        with self.assertRaises(FileExistsError): self.score()
        self.assertEqual(self.gold_path.read_bytes(),before)

    def test_mixed_labels_have_explicit_denominators_and_empty_arms_are_null(self):
        for row in self.report['traces']:
            if row['id'] != 'platform':
                continue
            if row['mode'] == 'focused-v6':
                row['response']['passages'] = []
            else:
                original = row['response']['passages'][0]
                row['response']['passages'] = [dict(original, start_line=n, end_line=n) for n in (2,3,4)]
        self.report_path.write_bytes(canonical(self.report))
        self.directory = self.root/'mixed'
        prepare(self.report_path,self.gold_path,'platform',self.directory)
        self.labels_path = self.directory/'labels-template.json'
        labels = self.labels()
        for j,label in zip(labels['judgments'], ('relevant','irrelevant','uncertain')):
            j['label'] = label
        self.labels_path.write_bytes(canonical(labels))
        metrics = self.score()['metrics']
        self.assertIsNone(metrics['focused-v6']['decisive_precision'])
        self.assertIsNone(metrics['focused-v6']['observed_wrong_procedure_rate'])
        self.assertEqual(metrics['reviewed-v5']['decisive_precision'],.5)
        self.assertEqual(metrics['reviewed-v5']['relevance_lower_bound'],1/3)
        self.assertEqual(metrics['reviewed-v5']['relevance_upper_bound'],2/3)
