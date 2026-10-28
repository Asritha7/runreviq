"""Frozen Python/Java HTTP search parity in a new disposable DB; never imports questions."""
import argparse
import copy
import hashlib
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
from runreviq.database import bootstrap, connect, credentials, ingest
from runreviq.evaluate_search import suite_data, load_cases
from runreviq.search import search
from scripts.test_java_database import private

FIXTURE = ROOT / 'backend/src/test/resources/search-parity.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--search-mode', choices=['reviewed-v5','focused-v6'], default='reviewed-v5')
    parser.add_argument('--update-fixture', action='store_true', help='Explicitly replace the frozen Python response fixture')
    args = parser.parse_args()
    if os.environ.get('RUNREVIQ_TEST_DATABASE') != '1':
        raise SystemExit('Set RUNREVIQ_TEST_DATABASE=1 to create a disposable database')
    name = 'opslens_java_test_' + uuid.uuid4().hex
    created = False
    try:
        with connect(dbname='postgres', autocommit=True) as conn:
            conn.execute(sql.SQL('CREATE DATABASE {}').format(sql.Identifier(name)))
            created = True
        bootstrap(name)
        with tempfile.TemporaryDirectory(prefix='runreviq-java-search-') as folder:
            directory = Path(folder)
            for suite in ('regression', 'realistic'):
                documents, root, _ = suite_data(suite)
                ingest(documents, root=root, dbname=name)
            documents, _, _ = suite_data('regression')
            template = copy.deepcopy(next(d for d in documents if d['id'] == 'RB-042'))
            (directory / 'datasets/northstar').mkdir(parents=True)
            units = []
            for number, (role, text) in enumerate([
                ('procedure', 'Inspect authentication signing key propagation.'),
                ('procedure', 'Inspect authentication signing key propagation.'),
                ('prohibition', 'Do not disable authentication signing key verification.'),
                ('historical', 'Earlier authentication signing key failures involved propagation delays.'),
                ('monitoring', 'Inspect authentication signing key failure rates in the monitoring dashboard.'),
            ], 920):
                doc = copy.deepcopy(template)
                doc.update(id=f'UNIT-{number}', service='reviewed-search', title='Signing key evidence', claims=[], path=f'datasets/northstar/UNIT-{number}.md')
                content = '# Evidence café 🔑\r\n\r\n' + text + '\r\n\r\nUnclassified banana submarine appendix.\r\n'
                # Path.read_text normalizes CRLF in the Python ingestion boundary.
                (directory / doc['path']).write_text(content)
                content = (directory / doc['path']).read_text()
                doc['evidence_profile'] = dict(schema_version=1, http_scope=dict(mode='only', codes=[401]), units=[dict(role=role,start_line=3,end_line=3)])
                if role == 'prohibition':
                    doc['claims'] = [dict(action='disable_signature_verification', rule='must_not', citation=dict(start_line=3,end_line=3,quote=text,source_sha256=hashlib.sha256(content.encode()).hexdigest()))]
                units.append(doc)
            ingest(units, root=directory, dbname=name)
            # Stable current revision and withdrawn source cases, distinct from historical revision 1.
            current = copy.deepcopy(template)
            current.update(revision=2, title='Current café 🔑 diagnostic revision')
            ingest([current], dbname=name)
            with connect(dbname=name) as conn:
                conn.execute("INSERT INTO opslens.document_withdrawals(document_id,revision,actor,reason) VALUES ('RB-043',1,'parity-test','Synthetic retirement')")
            cases = []
            for suite in ('regression','realistic','validation','paraphrase'):
                for case in load_cases(suite):
                    cases.append(dict(id=suite + '-' + case['id'], context=case['context'], question=case['question']))
            context = dict(role='platform',service='reviewed-search',software_version='2.4.0',environment='production',key_rotation_enabled=True)
            for number, (question, changes) in enumerate([
                ('authentication signing key', {'http_status':401}),
                ('authentication signing key', {'http_status':503}),
                ('authentication signing key', {}),
                ('HTTP 401 authentication signing key', {}),
                ('HTTP 401 authentication signing key', {'role':'intern'}),
                ('banana submarine', {'http_status':401}),
                ('authentication signing key', {'http_status':401,'key_rotation_enabled':None}),
                ('authentication signing key', {'http_status':401,'environment':'staging'}),
            ]):
                cases.append(dict(id=f'reviewed-{number}',context=dict(context,**changes),question=question))
            for case in cases:
                case['expected'] = search(question=case['question'],dbname=name,**case['context'])
            with connect(dbname=name) as conn:
                corpus = conn.execute('SELECT d.id,d.current_revision,d.allowed_roles,v.checksum FROM opslens.documents d JOIN opslens.document_versions v ON v.document_id=d.id AND v.revision=d.current_revision ORDER BY d.id').fetchall()
            fixture = dict(method='postgres_english_reviewed_v5', purpose='Behavioral parity, not independent relevance validation', corpus_sha256=hashlib.sha256(json.dumps(corpus,sort_keys=True).encode()).hexdigest(), cases=cases)
            if args.update_fixture:
                FIXTURE.write_text(json.dumps(fixture,ensure_ascii=False,indent=2) + '\n')
            elif json.loads(FIXTURE.read_text()) != fixture:
                raise SystemExit('Frozen search parity fixture changed; inspect Python behavior before explicitly regenerating it')
            print(f'Frozen full-response parity fixture verified: {len(cases)} cases.', flush=True)
            secrets = credentials()
            private(directory/'readers.json', dict(database=name,reader_passwords={r:secrets[f'READER_{r.upper()}_PASSWORD'] for r in ('platform','security','intern')}))
            private(directory/'admin.json', dict(database=name,password=secrets['POSTGRES_PASSWORD']))
            private(directory/'auth.json', [dict(subject=f'test-{r}',role=r,token=r+'-test-'+'x'*40) for r in ('platform','security','intern')])
            private(directory/'google.json',dict(client_id='offline-client',client_secret='offline-test-secret',allowed_subjects={'test-subject':'platform'}))
            environment = dict(os.environ,RUNREVIQ_JDBC_TEST_DIR=str(directory),RUNREVIQ_JDBC_TEST_DATABASE=name,RUNREVIQ_JDBC_SEARCH_MODE=args.search_mode)
            result = subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','-ntp','-Dtest=SearchIntegrationTest,SearchGoogleIntegrationTest','test'],cwd=ROOT,env=environment)
            if result.returncode:
                raise SystemExit(result.returncode)
    finally:
        if created:
            assert name.startswith('opslens_java_test_')
            with connect(dbname='postgres',autocommit=True) as conn:
                conn.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(name)))
            print('Removed the disposable Java search database.')


if __name__ == '__main__':
    main()
