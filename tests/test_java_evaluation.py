"""Java quality metrics use labeled evidence, not Python response equality."""
import copy
from contextlib import redirect_stdout, redirect_stderr
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch, Mock

from runreviq import evaluate_java as evaluation

CONTENT = '# Example\nInspect signing keys.\n\nObserve authentication logs.\n'
DOCUMENT = dict(id='TEST-1',title='Example',revision=1,path='test',service='identity-service',environment='production',version_min='2.4.0',version_max_exclusive='2.5.0',key_rotation_enabled=True,allowed_roles=['platform'],status='approved',metadata_verified=True,claims=[])
SOURCES = {'TEST-1': (DOCUMENT, CONTENT)}
CONTEXT = dict(role='platform',service='identity-service',software_version='2.4.0',environment='production',key_rotation_enabled=True)


def hit(line=2):
    return dict(document_id='TEST-1',revision=1,start_line=line,end_line=line,quote=CONTENT.splitlines()[line-1],source_sha256=hashlib.sha256(CONTENT.encode()).hexdigest())


def case():
    return dict(id='Q-1',question='Signing keys',category='test',context=CONTEXT,expected_status='selected',expected_passages=[{k: hit()[k] for k in ('document_id','revision','start_line','end_line')}])


def response():
    return dict(status='selected',retrieval_method=evaluation.METHOD,passages=[hit()],recommended_documents=['TEST-1'],applicable_documents=['TEST-1'],uncertain_documents=[],conflicts=[],evidence=[dict(document_id='TEST-1',revision=1)])


class JavaEvaluationTests(unittest.TestCase):
    def test_precision_denominators_and_exact_set_distinguish_noise(self):
        r = response(); r['passages'].append(hit(4))
        scored = evaluation.assess_response(case(), r, SOURCES)
        self.assertTrue(scored['passed'])
        self.assertFalse(scored['exact_evidence_set'])
        self.assertEqual(scored['precision'], .5)
        self.assertEqual(scored['precision_fixed_at_5'], .2)
        scored['latency_ms'] = 1
        summary = evaluation.summarize([scored])
        self.assertEqual(summary['precision_at_returned_5'], .5)
        self.assertEqual(summary['precision_fixed_at_5'], .2)
        self.assertEqual(summary['exact_evidence_set_passed'], 0)
        self.assertEqual(summary['integrity_failures'], 0)
        self.assertEqual(evaluation.exit_code(summary), 1)
        self.assertEqual(evaluation.exit_code(summary, True), 0)

    def test_missing_evidence_and_false_refusal_fail_quality_not_integrity(self):
        r = response(); r.update(status='insufficient_evidence',passages=[],recommended_documents=[])
        scored = evaluation.assess_response(case(), r, SOURCES)
        self.assertTrue(scored['integrity_ok']); self.assertFalse(scored['passed'])
        self.assertEqual(scored['recall'], 0); self.assertEqual(scored['precision_fixed_at_5'], 0)
        scored['latency_ms'] = 1
        report = dict(evaluation.summarize([scored]),results=[scored])
        self.assertEqual(evaluation.exit_code(report), 1)
        self.assertEqual(evaluation.exit_code(report, True), 0)

    def test_corruption_hidden_stale_and_malformed_responses_fail_closed(self):
        for change in ('quote','hash','range','boolean','revision','restricted','count','duplicate','recommendation','context','missing_quote','status','method','hidden_explanation'):
            r = response(); sources = copy.deepcopy(SOURCES)
            if change == 'quote': r['passages'][0]['quote'] = 'Private unauthorized text'
            if change == 'hash': r['passages'][0]['source_sha256'] = 'invalid'
            if change == 'range': r['passages'][0]['start_line'] = 0
            if change == 'boolean': r['passages'][0]['end_line'] = True
            if change == 'revision': r['passages'][0]['revision'] = 2
            if change == 'restricted': sources = {}
            if change == 'count': r['passages'] *= 6
            if change == 'duplicate': r['passages'] *= 2
            if change == 'recommendation': r['recommended_documents'] = []
            if change == 'context': sources['TEST-1'][0]['environment'] = 'staging'
            if change == 'missing_quote': del r['passages'][0]['quote']
            if change == 'status': r['status'] = []
            if change == 'method': r['retrieval_method'] = 'unknown'
            if change == 'hidden_explanation': r['review_items'] = [dict(document_id='SECRET-9',title='Private unauthorized text')]
            with self.subTest(change=change):
                scored = evaluation.assess_response(case(), r, sources)
                self.assertFalse(scored['integrity_ok']); self.assertEqual(scored['retrieved'], [])
                self.assertNotIn('Private unauthorized text', json.dumps(scored))
                self.assertFalse(scored['passed']); scored['latency_ms'] = 1
                self.assertEqual(evaluation.exit_code(evaluation.summarize([scored]), True), 1)

    def test_conflict_citations_also_require_authorized_exact_sources(self):
        c = case(); c.update(expected_status='conflict',expected_passages=[])
        r = response(); r.update(status='conflict',passages=[],recommended_documents=[],conflicts=[['TEST-1','TEST-1']],conflict_details=[dict(citations=[hit()])])
        self.assertTrue(evaluation.assess_response(c,r,SOURCES)['integrity_ok'])
        r['conflict_details'][0]['citations'][0]['quote'] = 'Invented quote'
        self.assertFalse(evaluation.assess_response(c,r,SOURCES)['integrity_ok'])

    def test_only_question_and_context_are_sent_to_java(self):
        tokens = {'platform':'test-token'}
        with patch.object(evaluation,'http_json',return_value=response()) as http, patch.object(evaluation,'authorized_sources',return_value=SOURCES):
            report = evaluation.evaluate_http(1234,tokens,'test',[case()],'realistic','fingerprint',True)
        self.assertEqual(http.call_args.kwargs['body'],dict(question='Signing keys',**{k:v for k,v in CONTEXT.items() if k!='role'}))
        self.assertEqual(http.call_args.kwargs['token'],'test-token')
        self.assertEqual(report['independence'],'not_verified')
        self.assertEqual(report['question_source'],'external')
        self.assertEqual(report['passed'],1)

    def test_invalid_input_and_validate_only_do_not_start_runtime_or_database(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'questions.json';path.write_text('[]')
            with patch.object(evaluation,'connect') as connect, patch.object(evaluation.subprocess,'run') as run, redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                evaluation.main(['--questions',str(path)])
            connect.assert_not_called();run.assert_not_called()
        out=io.StringIO()
        with patch.object(evaluation,'connect') as connect, patch.object(evaluation.subprocess,'run') as run, redirect_stdout(out):
            self.assertEqual(evaluation.main(['--validate-only']),0)
        connect.assert_not_called();run.assert_not_called()
        result=json.loads(out.getvalue());self.assertTrue(result['validated']);self.assertEqual(result['cases'],50)

    def test_http_transport_uses_utf8_and_never_follows_redirects_or_echoes_errors(self):
        conn=Mock();reply=conn.getresponse.return_value
        reply.status=200;reply.read.return_value=b'{"status":"ok"}'
        with patch.object(evaluation.http.client,'HTTPConnection',return_value=conn):
            evaluation.http_json(1234,'/v1/search',body={'question':'café 🔑'},token='test-token')
        self.assertIn('🔑'.encode(),conn.request.call_args.args[2]);conn.close.assert_called_once()
        for status in (302,401,503):
            conn=Mock();conn.getresponse.return_value.status=status
            conn.getresponse.return_value.read.return_value=b'private-error-body'
            with patch.object(evaluation.http.client,'HTTPConnection',return_value=conn),self.assertRaises(evaluation.EvaluationFailure) as failure:
                evaluation.http_json(1234,'/v1/search',body={})
            self.assertNotIn('private-error-body',str(failure.exception));conn.request.assert_called_once();conn.close.assert_called_once()

    def test_disposable_database_cleanup_on_bootstrap_failure(self):
        conn=Mock();conn.__enter__=Mock(return_value=conn);conn.__exit__=Mock(return_value=False)
        with patch.object(evaluation,'connect',return_value=conn),patch.object(evaluation,'bootstrap',side_effect=evaluation.EvaluationFailure('test failure')):
            with self.assertRaises(evaluation.EvaluationFailure):
                with evaluation.evaluation_database(): pass
        queries=[call.args[0].as_string() for call in conn.execute.call_args_list]
        self.assertEqual(len(queries),2);self.assertTrue(queries[0].startswith('CREATE DATABASE "opslens_java_eval_'))
        self.assertTrue(queries[1].startswith('DROP DATABASE "opslens_java_eval_'))
        self.assertEqual(queries[0].split('"')[1],queries[1].split('"')[1])

    def test_changed_ingested_corpus_cannot_use_old_fingerprint(self):
        conn=Mock();conn.__enter__=Mock(return_value=conn);conn.__exit__=Mock(return_value=False)
        records=[(DOCUMENT,DOCUMENT,CONTENT,'hash')]
        conn.execute.return_value.fetchall.return_value=[('TEST-1',1,DOCUMENT,CONTENT,'hash',['platform'])]
        with patch.object(evaluation,'connect',return_value=conn):
            evaluation.verify_corpus('test',records)
            conn.execute.return_value.fetchall.return_value=[('TEST-1',1,DOCUMENT,'changed','hash',['platform'])]
            with self.assertRaises(evaluation.EvaluationFailure): evaluation.verify_corpus('test',records)

    def test_corpus_verification_preserves_revoked_acl_and_role_order_semantics(self):
        conn=Mock();conn.__enter__=Mock(return_value=conn);conn.__exit__=Mock(return_value=False)
        doc=dict(DOCUMENT,status='revoked')
        conn.execute.return_value.fetchall.return_value=[('TEST-1',1,doc,CONTENT,'hash',[])]
        with patch.object(evaluation,'connect',return_value=conn):
            evaluation.verify_corpus('test',[(doc,doc,CONTENT,'hash')])
        doc=dict(DOCUMENT,allowed_roles=['security','platform'])
        conn.execute.return_value.fetchall.return_value=[('TEST-1',1,doc,CONTENT,'hash',['security','platform'])]
        with patch.object(evaluation,'connect',return_value=conn):
            evaluation.verify_corpus('test',[(doc,doc,CONTENT,'hash')])

    def test_runtime_rejects_real_database_before_reading_credentials(self):
        with patch.object(evaluation,'credentials') as secrets, tempfile.TemporaryDirectory() as folder:
            with self.assertRaises(evaluation.EvaluationFailure):
                with evaluation.java_runtime('opslens',Path(folder)): pass
            secrets.assert_not_called()

    def test_runtime_stops_only_owned_process_after_startup_failure(self):
        process=Mock();process.poll.return_value=None
        with tempfile.TemporaryDirectory() as folder, patch.object(evaluation,'credentials',return_value={f'READER_{r}_PASSWORD':'test' for r in ('PLATFORM','SECURITY','INTERN')}), patch.object(evaluation.subprocess,'Popen',return_value=process), patch.object(evaluation.time,'monotonic',side_effect=[0,46]):
            with self.assertRaises(evaluation.EvaluationFailure):
                with evaluation.java_runtime('opslens_java_eval_'+'a'*32,Path(folder)): pass
            process.terminate.assert_called_once();process.wait.assert_called_once_with(timeout=10)
            for path in Path(folder).glob('*.json'): self.assertEqual(path.stat().st_mode & 0o777,0o600)


@unittest.skipUnless(os.environ.get('RUNREVIQ_TEST_JAVA_EVALUATION')=='1','Requires Java and disposable PostgreSQL; enabled separately after Java setup in CI')
class JavaHttpEvaluationIntegrationTests(unittest.TestCase):
    def test_real_java_quality_report_and_cleanup_without_imported_labels(self):
        original=evaluation.evaluate_http; observed={}
        def audited(port,tokens,dbname,cases,suite,fingerprint,external):
            observed.update(port=port,dbname=dbname)
            with evaluation.connect(dbname=dbname) as conn:
                self.assertEqual(conn.execute('SELECT count(*) FROM opslens.documents').fetchone()[0],13)
                stored=conn.execute('SELECT content FROM opslens.document_versions').fetchall()
                docs, root, _ = evaluation.suite_data(suite)
                self.assertEqual(sorted(text for (text,) in stored), sorted(record[2] for record in evaluation.prepare(docs,root=root)))
            return original(port,tokens,dbname,cases,suite,fingerprint,external)
        out=io.StringIO()
        with patch.object(evaluation,'evaluate_http',side_effect=audited),redirect_stdout(out):
            self.assertEqual(evaluation.main(['--suite','realistic','--report-only']),0)
        report=json.loads(out.getvalue())
        self.assertEqual(report['cases'],50);self.assertEqual(report['runtime'],'java_http');self.assertEqual(report['integrity_failures'],0)
        self.assertEqual(report['quality_gate'],'failed')
        self.assertLess(report['precision_at_returned_5'],1);self.assertLess(report['exact_evidence_set_passed'],50)
        with evaluation.connect(dbname='postgres') as conn:
            self.assertIsNone(conn.execute('SELECT 1 FROM pg_database WHERE datname=%s',(observed['dbname'],)).fetchone())
        with self.assertRaises(evaluation.EvaluationFailure): evaluation.http_json(observed['port'],'/health')
