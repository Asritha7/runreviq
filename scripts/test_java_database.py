"""Run Java JDBC tests only in a freshly created disposable database, then remove it."""
import copy
import hashlib
import itertools
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from psycopg import sql
from runreviq.database import bootstrap, connect, credentials, ingest, load_authorized_documents, load_authorized_revision
from runreviq.selector import load_documents, select


def private(path, data):
    with path.open('x') as stream:
        path.chmod(0o600)
        json.dump(data, stream)


def main():
    if os.environ.get('RUNREVIQ_TEST_DATABASE') != '1':
        raise SystemExit('Set RUNREVIQ_TEST_DATABASE=1 to create a disposable database')
    name = 'opslens_java_test_' + uuid.uuid4().hex
    created = False
    try:
        with connect(dbname='postgres', autocommit=True) as conn:
            conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
            created = True
        bootstrap(name)
        docs = load_documents()
        ingest(docs, dbname=name)
        updated = copy.deepcopy(next(d for d in docs if d['id'] == 'RB-042'))
        updated.update(revision=2, title='Reviewed café 🔑 revision')
        ingest([updated], dbname=name)
        # Current withdrawals remain readable as history but never selected.
        with connect(dbname=name) as conn:
            conn.execute("INSERT INTO opslens.document_withdrawals(document_id,revision,actor,reason,created_at) VALUES ('RB-043',1,'test-operator','Synthetic retirement','2026-01-02 03:04:05.123400+00')")
        with tempfile.TemporaryDirectory(prefix='runreviq-java-db-') as folder:
            directory = Path(folder)
            secrets = credentials()
            private(directory / 'readers.json', {'database': name, 'reader_passwords': {r: secrets[f'READER_{r.upper()}_PASSWORD'] for r in ('platform','security','intern')}})
            # Admin credentials are exclusively a test helper, guarded by this exact generated DB name.
            private(directory / 'admin.json', {'database': name, 'password': secrets['POSTGRES_PASSWORD']})
            private(directory / 'google.json', {'client_id':'offline-client', 'client_secret':'offline-test-secret', 'allowed_subjects':{'test-subject':'platform'}})
            private(directory / 'auth.json', [{'subject': f'test-{r}', 'role': r, 'token': r + '-test-' + 'x'*40} for r in ('platform','security','intern')])
            cases = []
            revisions = []
            for role in ('platform','security','intern'):
                for version, environment, rotation in itertools.product(('2.1.0','2.4.0','2.5.0'), ('production','staging'), (None,True,False)):
                    context = dict(service='identity-service', software_version=version, environment=environment, key_rotation_enabled=rotation)
                    cases.append(dict(role=role, context=context, expected=select(load_authorized_documents(role,dbname=name), role=role, **context)))
                for doc, rev in [('RB-042',1),('RB-042',2),('RB-043',1),('SEC-021',1),('RB-999',1)]:
                    revisions.append(dict(role=role, document=doc, revision=rev, expected=load_authorized_revision(role,doc,rev,dbname=name)))
            samples = [{'z': '\\"\n\r\t\b\f\x00\x7f café 🔑\u2028', '\U00010000': [True,None,123456789012345678901], '\ue000': 'unicode ordering'}, {}, [], {'lines': 'a\r\nb\x85c\u2029'}]
            canonical = [{'value': v, 'encoded': json.dumps(v,sort_keys=True), 'sha256': hashlib.sha256(json.dumps(v,sort_keys=True).encode()).hexdigest()} for v in samples]
            private(directory / 'expected.json',dict(cases=cases,revisions=revisions,canonical=canonical))
            environment = dict(os.environ, RUNREVIQ_JDBC_TEST_DIR=str(directory), RUNREVIQ_JDBC_TEST_DATABASE=name)
            result = subprocess.run([str(ROOT/'backend/mvnw'), '-f', str(ROOT/'backend/pom.xml'), '-B', '-ntp', '-Dtest=JdbcIntegrationTest,JdbcGoogleIntegrationTest', 'test'],cwd=ROOT,env=environment)
            if result.returncode:
                raise SystemExit(result.returncode)
    finally:
        if created:
            assert name.startswith('opslens_java_test_')
            with connect(dbname='postgres',autocommit=True) as conn:
                conn.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(name)))
            print('Removed the disposable Java test database.')


if __name__ == '__main__':
    main()
