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
from runreviq import drafts, submissions, publications, lifecycle
from scripts.export_java_authoring_parity import SOURCE


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
            private(directory / 'review-db.json', {'database': name, 'password': secrets['REVIEW_PASSWORD']})
            private(directory / 'publish-db.json', {'database': name, 'password': secrets['PUBLISH_PASSWORD']})
            private(directory / 'reviewers.json', {'demo': ['test-security'], 'google': ['test-subject']})
            private(directory / 'publishers.json', {'demo': ['test-intern'], 'google': ['test-subject']})
            private(directory / 'drafts.json', {'database': name, 'draft_password': secrets['DRAFTS_PASSWORD']})
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
            owner = drafts.owner_key('demo', 'python-java-interop')
            draft = drafts.create_draft(owner, 'python.md', SOURCE, dbname=name)
            metadata = dict(title='Example', service='identity-service', environment='production', version_min='2.4.0', version_max_exclusive='2.5.0', key_rotation_enabled='none')
            drafts.save_review(owner, draft['id'], 0, metadata, dbname=name)
            private(directory / 'draft-interop.json', {'owner': owner, 'draft': drafts.get_draft(owner, draft['id'], dbname=name)})
            workflow_owner = drafts.owner_key('demo', 'workflow-python-owner')
            workflow_reviewer = drafts.owner_key('demo', 'test-security')
            workflow_publisher = drafts.owner_key('demo', 'test-intern')
            workflow_source = '# Python workflow\nInspect JWKS before restart.\nDo not disable verification.\n'
            workflow_draft = drafts.create_draft(workflow_owner, 'workflow.md', workflow_source, dbname=name)
            drafts.save_review(workflow_owner, workflow_draft['id'], 0, metadata, dbname=name)
            workflow_submission = submissions.submit(workflow_owner, workflow_draft['id'], 1, dbname=name)
            submissions.decide(workflow_reviewer, workflow_submission['id'], 'approved', 'Checked source', dbname=name)
            workflow_claims = [{'action': 'inspect_jwks', 'rule':'must', 'start_line':2, 'end_line':2}, {'action':'disable_verification','rule':'must_not','start_line':3,'end_line':3}]
            workflow_publication = publications.publish(workflow_publisher, workflow_submission['id'], ['platform'], workflow_claims, True, dbname=name)
            private(directory / 'workflow-interop.json', dict(owner=workflow_owner, reviewer=workflow_reviewer, publisher=workflow_publisher,
                draft_id=workflow_draft['id'], submission_id=workflow_submission['id'], claims=workflow_claims,
                own=submissions.own_submissions(workflow_owner,workflow_draft['id'],dbname=name),
                detail=submissions.submission_detail(workflow_reviewer,workflow_submission['id'],dbname=name),
                publish_detail=publications.detail(workflow_submission['id'],dbname=name), publication=workflow_publication,
                history=lifecycle.history(workflow_publication['document_id'],dbname=name),
                revision=lifecycle.revision(workflow_publication['document_id'],1,dbname=name)))
            # Refresh read goldens after the synthetic interoperability publication.
            for case in cases:
                case['expected'] = select(load_authorized_documents(case['role'],dbname=name),role=case['role'],**case['context'])
            (directory / 'expected.json').write_text(json.dumps(dict(cases=cases,revisions=revisions,canonical=canonical)))
            environment = dict(os.environ, RUNREVIQ_JDBC_TEST_DIR=str(directory), RUNREVIQ_JDBC_TEST_DATABASE=name)
            result = subprocess.run([str(ROOT/'backend/mvnw'), '-f', str(ROOT/'backend/pom.xml'), '-B', '-ntp', '-Dtest=JdbcIntegrationTest,JdbcGoogleIntegrationTest,DraftIntegrationTest', 'test'],cwd=ROOT,env=environment)
            if result.returncode:
                raise SystemExit(result.returncode)
            # Java appended revision 2 to this Python-authored draft; Python must read it unchanged.
            readback = drafts.get_draft(owner, draft['id'], dbname=name)
            assert readback['content'] == SOURCE
            assert readback['proposal'] == draft['proposal']
            assert readback['review']['revision'] == 2
            assert readback['review']['validated_metadata']['title'] == 'Java reviewed title'
            print('Python read back the Java-authored review with original source/proposal intact.')
            workflow_result = subprocess.run([str(ROOT/'backend/mvnw'), '-f', str(ROOT/'backend/pom.xml'), '-B', '-ntp', '-Dtest=WorkflowIntegrationTest,WorkflowGoogleIntegrationTest', 'test'],cwd=ROOT,env=environment)
            if workflow_result.returncode:
                raise SystemExit(workflow_result.returncode)
            history = lifecycle.history(workflow_publication['document_id'], dbname=name)
            assert history['revisions'][0]['status'] == 'withdrawn'
            assert lifecycle.revision(workflow_publication['document_id'],1,dbname=name)['content'] == workflow_source
            with connect(dbname=name) as conn:
                published_rows = conn.execute("SELECT metadata,content,checksum FROM opslens.document_versions WHERE metadata->>'path' LIKE 'submission:%'").fetchall()
            assert len(published_rows) > 1
            for stored_metadata, stored_content, checksum in published_rows:
                assert hashlib.sha256(json.dumps({'metadata': stored_metadata, 'content': stored_content}, sort_keys=True).encode()).hexdigest() == checksum
                if stored_metadata['claims']:
                    raw_claims = [dict(action=c['action'],rule=c['rule'],start_line=c['citation']['start_line'],end_line=c['citation']['end_line']) for c in stored_metadata['claims']]
                    assert publications.prepare_claims(raw_claims, stored_content) == stored_metadata['claims']
            print('Python verified all synthetic publication checksums/citations and the Java withdrawal.')
    finally:
        if created:
            assert name.startswith('opslens_java_test_')
            with connect(dbname='postgres',autocommit=True) as conn:
                conn.execute(sql.SQL('DROP DATABASE {} WITH (FORCE)').format(sql.Identifier(name)))
            print('Removed the disposable Java test database.')


if __name__ == '__main__':
    main()
