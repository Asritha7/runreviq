"""Metric definitions and label integrity are independent of ranking quality."""
import copy
import json
import unittest
import tempfile
import io
from pathlib import Path
from unittest.mock import patch
from contextlib import redirect_stdout, redirect_stderr

from runreviq.database import prepare
from runreviq.evaluate_search import aggregate, relevance, suite_data, validate_cases, report_exit_code


def ref(document, line):
    return dict(document_id=document, revision=1, start_line=line, end_line=line)


class EvaluationTests(unittest.TestCase):
    def test_report_only_does_not_suppress_integrity_failure(self):
        report=dict(passed=0,cases=1,results=[dict(integrity_ok=True)])
        self.assertEqual(report_exit_code(report),1)
        self.assertEqual(report_exit_code(report,True),0)
        report['results'][0]['integrity_ok']=False
        self.assertEqual(report_exit_code(report,True),1)
        report['passed']=1
        self.assertEqual(report_exit_code(report,True),1)

    def test_partial_recall_precision_and_rank(self):
        first, second, unrelated = ref('DB-201',11), ref('DB-202',11), ref('NET-601',7)
        metrics = relevance([first, second], [unrelated, second, unrelated])
        self.assertEqual(metrics['recall'], .5)
        self.assertEqual(metrics['precision'], 1/3)
        self.assertEqual(metrics['rank'], 2)
        self.assertEqual(metrics['missing'], [first])
        self.assertEqual(len(metrics['irrelevant']), 2)
        self.assertEqual(relevance([first], [])['precision'], 0)
        self.assertEqual(relevance([first], [])['recall'], 0)

    def test_aggregate_denominators_and_empty_metrics(self):
        positive=dict(withheld=False,passed=True,recall=.5,precision=.25,rank=2,retrieved=[{}],citation_checks=[True],latency_ms=10)
        negative=dict(withheld=True,passed=False,recall=None,precision=0,rank=None,retrieved=[{}],citation_checks=[False],latency_ms=20)
        report=aggregate([positive,negative])
        self.assertEqual(report['recall_at_5'],.5)
        self.assertEqual(report['hit_rate_at_5'],1)
        self.assertEqual(report['mean_reciprocal_rank'],.5)
        self.assertEqual(report['refusal_accuracy'],0)
        self.assertEqual(report['false_positive_rate'],1)
        self.assertEqual(report['citation_accuracy'],.5)
        self.assertEqual(report['median_latency_ms'],15)
        self.assertEqual(report['p95_latency_ms'],20)
        self.assertIsNone(aggregate([])['recall_at_5'])
        self.assertIsNone(aggregate([dict(negative,retrieved=[],citation_checks=[])])['citation_accuracy'])

    def test_realistic_labels_are_exact_and_unique(self):
        docs,root,path=suite_data('realistic')
        records=prepare(docs,root=root)
        cases=json.loads(path.read_text())
        self.assertEqual(len(docs),13)
        self.assertEqual(len(cases),50)
        validate_cases(cases,records)
        for mutate in ('duplicate_case','missing_line','duplicate_label','inaccessible','status'):
            changed=copy.deepcopy(cases)
            if mutate=='duplicate_case': changed.append(changed[0])
            if mutate=='missing_line': changed[0]['expected_passages'][0]['start_line']=999
            if mutate=='duplicate_label': changed[0]['expected_passages']*=2
            if mutate=='inaccessible': changed[0]['context']['role']='intern'
            if mutate=='status': changed[0]['expected_status']='insufficient_evidence'
            with self.subTest(mutate=mutate), self.assertRaises(ValueError):
                validate_cases(changed,records)

    def test_invalid_external_cases_fail_before_database_access(self):
        from runreviq.evaluate_search import main
        docs, root, path = suite_data('realistic')
        original = json.loads(path.read_text())[:1]
        for mutation in ('empty', 'role', 'boolean', 'version', 'question', 'extra', 'revision', 'context'):
            cases = copy.deepcopy(original)
            if mutation == 'empty': cases = []
            if mutation == 'role': cases[0]['context']['role'] = 'admin'
            if mutation == 'boolean': cases[0]['context']['key_rotation_enabled'] = 'false'
            if mutation == 'version': cases[0]['context']['software_version'] = 'latest'
            if mutation == 'question': cases[0]['question'] = ' ' * 10
            if mutation == 'extra': cases[0]['context']['dbname'] = 'opslens'
            if mutation == 'revision': cases[0]['expected_passages'][0]['revision'] = True
            if mutation == 'context': cases[0]['context']['service'] = 'other-service'
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                filename = Path(directory) / 'cases.json'
                filename.write_text(json.dumps(cases))
                with patch('sys.argv', ['evaluate', '--suite', 'realistic', '--questions', str(filename)]), patch('runreviq.evaluate_search.connect') as connect, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
                    main()
                self.assertEqual(error.exception.code, 2)
                connect.assert_not_called()

    def test_validate_only_external_cases_without_database_and_stable_fingerprint(self):
        from runreviq.evaluate_search import main
        _, _, path = suite_data('realistic')
        with tempfile.TemporaryDirectory() as directory:
            filename = Path(directory) / 'cases.json'
            cases = json.loads(path.read_text())[:1]
            reports = []
            for question in (cases[0]['question'], cases[0]['question'], 'A different question'):
                cases[0]['question'] = question
                filename.write_text(json.dumps(cases))
                output = io.StringIO()
                with patch('sys.argv', ['evaluate', '--suite', 'realistic', '--questions', str(filename), '--validate-only']), patch('runreviq.evaluate_search.connect') as connect, redirect_stdout(output):
                    self.assertEqual(main(), 0)
                connect.assert_not_called()
                reports.append(json.loads(output.getvalue()))
            self.assertEqual(reports[0], reports[1])
            self.assertNotEqual(reports[1]['dataset_sha256'], reports[2]['dataset_sha256'])
            self.assertEqual(reports[0]['cases'], 1)
