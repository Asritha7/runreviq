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
