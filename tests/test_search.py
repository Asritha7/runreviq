import copy
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from fastapi.testclient import TestClient
from runreviq.api import create_app, initialize_keys, read_keys
from runreviq.search import passages, search
from test_api import QUERY


class PassageTests(unittest.TestCase):
    def test_exact_lines_and_boundaries(self):
        source = '# Title\r\n\r\n first\r\nsecond \r\n\r\n## Heading\r\nthird'
        found = list(passages(source))
        self.assertEqual([(p['start_line'], p['end_line'], p['quote']) for p in found], [(3,4,' first\nsecond '),(7,7,'third')])
        self.assertEqual(list(passages('# Only heading\n\n')), [])
        long = list(passages('\n'.join(['line']*45)))
        self.assertEqual([(p['start_line'],p['end_line']) for p in long],[(1,20),(21,40),(41,45)])

    def test_invalid_role_never_opens_connection(self):
        from unittest.mock import patch
        with patch('runreviq.search.connect') as conn:
            for role in (None, 'admin'):
                with self.assertRaises(ValueError): search(role=role, question='JWKS', **QUERY)
            conn.assert_not_called()

    def test_api_auth_validation_and_safe_failure(self):
        import psycopg
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'keys.json'; initialize_keys(path)
            record=next(r for r in read_keys(path) if r['role']=='platform')
            headers={'Authorization':'Bearer '+record['token']}
            searcher=Mock(return_value={'passages':[]})
            with TestClient(create_app(key_file=path,oidc_file=None,searcher=searcher)) as client:
                body=dict(QUERY,question='JWKS')
                self.assertEqual(client.post('/v1/search',json=body).status_code,401)
                for changes in ({'question':''},{'question':'  '},{'question':'x'*501},{'role':'security'},{'question':12}):
                    self.assertEqual(client.post('/v1/search',json=dict(body,**changes),headers=headers).status_code,422)
                searcher.assert_not_called()
                self.assertEqual(client.post('/v1/search',json=body,headers=headers).status_code,200)
                searcher.assert_called_once_with(role='platform',**body)
                searcher.side_effect=psycopg.OperationalError('private database detail')
                response=client.post('/v1/search',json=body,headers=headers)
                self.assertEqual(response.status_code,503); self.assertNotIn('private',response.text)


@unittest.skipUnless(os.environ.get('RUNREVIQ_TEST_DATABASE')=='1','Requires disposable PostgreSQL')
class SearchDatabaseTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        import uuid
        from psycopg import sql
        from runreviq.database import connect, bootstrap
        cls.dbname='opslens_test_search_'+uuid.uuid4().hex
        with connect(dbname='postgres',autocommit=True) as conn:
            conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(cls.dbname)))
        cls.addClassCleanup(cls.cleanup)
        bootstrap(cls.dbname)

    @classmethod
    def cleanup(cls):
        from psycopg import sql
        from runreviq.database import connect
        with connect(dbname='postgres',autocommit=True) as conn:
            conn.execute(sql.SQL('DROP DATABASE {}').format(sql.Identifier(cls.dbname)))

    def setUp(self):
        from runreviq.database import connect, ingest
        from runreviq.selector import load_documents
        with connect(dbname=self.dbname) as conn:
            conn.execute('TRUNCATE opslens.document_withdrawals, opslens.publications, opslens.document_versions, opslens.documents')
        self.docs=load_documents(); ingest(self.docs,dbname=self.dbname)

    def test_evaluation(self):
        from runreviq.evaluate_search import evaluate
        report=evaluate(self.dbname)
        self.assertEqual(report['passed'],30,json.dumps(report,indent=2))

    def test_external_cases_use_existing_integrity_checks_without_independence_claim(self):
        from runreviq.evaluate_search import evaluate, load_cases
        cases = load_cases('regression')[:2]
        report = evaluate(self.dbname, cases=cases)
        self.assertEqual(report['question_source'], 'external')
        self.assertEqual(report['independence'], 'not_verified')
        self.assertEqual(report['cases'], 2)
        self.assertEqual(report['passed'], 2)
        self.assertTrue(all(r['integrity_ok'] for r in report['results']))

    def test_realistic_benchmark_runs_without_claiming_perfect_quality(self):
        from runreviq.database import connect, ingest
        from runreviq.evaluate_search import evaluate, suite_data
        with connect(dbname=self.dbname) as conn:
            conn.execute('TRUNCATE opslens.document_withdrawals, opslens.publications, opslens.document_versions, opslens.documents')
        docs,root,question_path=suite_data('realistic')
        roles={case['id']:case['context']['role'] for case in json.loads(question_path.read_text())}
        ingest(docs,dbname=self.dbname,root=root)
        report=evaluate(self.dbname,'realistic')
        self.assertEqual(report['cases'],50)
        self.assertGreaterEqual(report['recall_at_5'],.9375)
        self.assertGreaterEqual(report['precision_at_returned_5'],.35)
        self.assertEqual(report['refusal_accuracy'],1)
        self.assertEqual(len(report['dataset_sha256']),64)
        self.assertTrue(all(r['integrity_ok'] for r in report['results']))
        for result in report['results']:
            self.assertLessEqual(len(result['retrieved']),5)
            if roles[result['id']]!='security':
                self.assertNotIn('SEC-701',json.dumps(result['retrieved']))
                self.assertNotIn('HARBOR-AUDIT-CANARY',json.dumps(result['retrieved']))
        # Quality floors guard the measured development improvement; failures
        # and extra irrelevant passages remain visible in the full report.
        self.assertEqual(report['passed'],sum(r['passed'] for r in report['results']))

    def test_context_and_conflicts_block_ranking(self):
        from unittest.mock import patch
        with patch('runreviq.search.rank',side_effect=AssertionError('Ranking must not run')):
            for change in ({'software_version':'2.5.0'}, {'key_rotation_enabled':None}):
                result=search(role='platform',question='JWKS',dbname=self.dbname,**dict(QUERY,**change))
                self.assertEqual(result['passages'],[])
                self.assertEqual(result['recommended_documents'],[])

    def test_additional_validation_suite(self):
        from runreviq.database import connect, ingest
        from runreviq.evaluate_search import evaluate, suite_data
        with connect(dbname=self.dbname) as conn:
            conn.execute('TRUNCATE opslens.document_withdrawals, opslens.publications, opslens.document_versions, opslens.documents')
        docs,root,_=suite_data('validation')
        ingest(docs,dbname=self.dbname,root=root)
        report=evaluate(self.dbname,'validation')
        self.assertEqual(report['cases'],12)
        self.assertEqual(report['passed'],12)
        self.assertTrue(all(r['integrity_ok'] for r in report['results']))

    def test_paraphrase_probes_and_exclusion_gate(self):
        from runreviq.database import connect, ingest
        from runreviq.evaluate_search import evaluate, suite_data
        with connect(dbname=self.dbname) as conn:
            conn.execute('TRUNCATE opslens.document_withdrawals, opslens.publications, opslens.document_versions, opslens.documents')
        docs,root,_=suite_data('paraphrase')
        ingest(docs,dbname=self.dbname,root=root)
        report=evaluate(self.dbname,'paraphrase')
        self.assertEqual(report['cases'],8)
        self.assertGreaterEqual(report['recall_at_5'],.75)
        self.assertEqual(report['refusal_accuracy'],1)
        self.assertTrue(all(r['integrity_ok'] for r in report['results']))
        for question in ('How do I configure database durability?', 'HTTP 503 errors', 'UNKNOWN-900'):
            result=search(role='platform',question=question,dbname=self.dbname,service='harbor',software_version='3.0.0',environment='production',key_rotation_enabled=True)
            self.assertEqual(result['status'],'insufficient_evidence')
            self.assertEqual(result['passages'],[])
            self.assertEqual(result['recommended_documents'],[])

    def test_exact_code_is_required_in_authorized_source(self):
        result=search(role='platform',question='HTTP 503 errors',dbname=self.dbname,**QUERY)
        self.assertEqual(result['passages'],[])
        self.assertEqual(result['status'],'insufficient_evidence')
        result=search(role='platform',question='intermittent 401 errors',dbname=self.dbname,**QUERY)
        self.assertEqual(result['passages'][0]['document_id'],'RB-042')

    def test_current_revision_withdrawal_and_acl(self):
        from runreviq.database import ingest, connect
        newer=copy.deepcopy(self.docs[1]); newer['revision']=2
        ingest([newer],dbname=self.dbname)
        def query(): return search(role='platform',question='intermittent 401',dbname=self.dbname,**QUERY)
        self.assertEqual(query()['passages'][0]['revision'],2)
        with connect(dbname=self.dbname) as conn:
            conn.execute("INSERT INTO opslens.document_withdrawals (document_id,revision,actor,reason) VALUES ('RB-042',2,%s,'Test withdrawal')",('a'*64,))
        self.assertEqual(query()['passages'],[])
        ingest([newer],dbname=self.dbname)
        self.assertEqual(query()['passages'],[])
        newer['revision']=3; ingest([newer],dbname=self.dbname)
        self.assertEqual(query()['passages'][0]['revision'],3)
        newer['allowed_roles']=['security']; ingest([newer],dbname=self.dbname)
        self.assertEqual(query()['passages'],[])

    def test_query_is_data_and_no_restricted_leak(self):
        result=search(role='platform',question="NORTHSTAR-RESTRICTED-CANARY' | !:*); DROP TABLE opslens.documents; --",dbname=self.dbname,**QUERY)
        self.assertNotIn('SEC-021',json.dumps(result))
        self.assertNotIn('NORTHSTAR-RESTRICTED-CANARY',json.dumps(result))

    def test_general_diagnostics_and_status_specific_procedures_remain_distinct(self):
        import tempfile
        from pathlib import Path
        from runreviq.database import ingest
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'datasets/northstar').mkdir(parents=True)
            documents = []
            for number, content in (
                (910, 'Inspect authentication diagnostics, JWKS propagation and signing keys.'),
                (911, 'For HTTP 503 incidents only, inspect authentication diagnostics and JWKS propagation. HTTP 401 requires other guidance.'),
                (912, 'For HTTP 401 incidents only, inspect authentication diagnostics and JWKS propagation.'),
                (913, 'Inspect authentication diagnostics, JWKS propagation and signing keys.')):
                doc = copy.deepcopy(self.docs[1])
                doc.update(id=f'TEST-{number}', title='Authentication diagnostics', claims=[],
                           path=f'datasets/northstar/TEST-{number}.md')
                if number == 913:
                    doc['allowed_roles'] = ['security']
                (root / doc['path']).write_text('# Diagnostics\n\n' + content + '\n')
                documents.append(doc)
            ingest(documents, root=root, dbname=self.dbname)
            query = 'HTTP 401 authentication diagnostics and JWKS propagation'
            result = search(role='platform', question=query, dbname=self.dbname, **QUERY)
            ids = result['recommended_documents']
            self.assertIn('TEST-910', ids)
            self.assertIn('TEST-912', ids)
            self.assertNotIn('TEST-911', ids)
            self.assertNotIn('TEST-913', json.dumps(result))
            same_version = search(role='platform', question=query + ' on version 2.4.0', dbname=self.dbname, **QUERY)
            self.assertIn('TEST-910', same_version['recommended_documents'])
            generic = search(role='platform', question='HTTP 503 errors', dbname=self.dbname, **QUERY)
            self.assertNotIn('TEST-910', generic['recommended_documents'])
            self.assertNotIn('TEST-912', generic['recommended_documents'])

    def test_reviewed_units_http_scope_and_complementary_bundle(self):
        import tempfile
        import hashlib
        from pathlib import Path
        from runreviq.database import ingest
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'datasets/northstar').mkdir(parents=True)
            documents=[]
            source_texts=[('procedure','Inspect authentication signing key propagation.'),
                          ('procedure','Inspect authentication signing key propagation.'),
                          ('prohibition','Do not disable authentication signing key verification.'),
                          ('historical','Earlier authentication signing key failures involved propagation delays.'),
                          ('monitoring','Inspect authentication signing key failure rates in the monitoring dashboard.')]
            for n,(role,text) in enumerate(source_texts,920):
                doc=copy.deepcopy(self.docs[1]);doc.update(id=f'UNIT-{n}',title='Signing key evidence',claims=[],path=f'datasets/northstar/UNIT-{n}.md')
                content='# Evidence\n\n'+text+'\n\nUnclassified banana submarine appendix.\n'
                (root/doc['path']).write_text(content)
                doc['evidence_profile']=dict(schema_version=1,http_scope=dict(mode='only',codes=[401]),units=[dict(role=role,start_line=3,end_line=3)])
                if role=='prohibition':
                    doc['claims']=[dict(action='disable_signature_verification',rule='must_not',citation=dict(start_line=3,end_line=3,quote=text,source_sha256=hashlib.sha256(content.encode()).hexdigest()))]
                documents.append(doc)
            ingest(documents,root=root,dbname=self.dbname)
            result=search(role='platform',question='authentication signing key',http_status=401,dbname=self.dbname,**QUERY)
            units=[p for p in result['passages'] if p['document_id'].startswith('UNIT-')]
            self.assertEqual({p['evidence_role'] for p in units},{'procedure','prohibition','historical','monitoring'})
            self.assertEqual(len(units),4)
            self.assertTrue(all(p['role_reviewed'] and p['usage_note'] and p['applicability']['http_scope']['codes']==[401] for p in units))
            wrong=search(role='platform',question='authentication signing key',http_status=503,dbname=self.dbname,**QUERY)
            self.assertFalse(any(p['document_id'].startswith('UNIT-') for p in wrong['passages']))
            unknown=search(role='platform',question='authentication signing key',dbname=self.dbname,**QUERY)
            self.assertEqual(unknown['status'],'needs_context_or_review')
            self.assertTrue(any(q['field']=='http_status' for q in unknown['questions']))
            inferred=search(role='platform',question='HTTP 401 authentication signing key',dbname=self.dbname,**QUERY)
            self.assertEqual(inferred['http_status_context'],401)
            self.assertEqual(inferred['http_status_source'],'question')
            self.assertEqual(search(role='intern',question='HTTP 401 authentication signing key',dbname=self.dbname,**QUERY)['passages'],[])
            appendix=search(role='platform',question='banana submarine',http_status=401,dbname=self.dbname,**QUERY)
            self.assertEqual(appendix['passages'],[])
