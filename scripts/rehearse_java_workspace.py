"""Serve a temporary Java workspace against disposable data and demo identities only."""
import argparse
from contextlib import contextmanager
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from runreviq.database import credentials, ingest
from runreviq.selector import load_documents
from runreviq.evaluate_java import JAR, evaluation_database, http_json, private_json


@contextmanager
def workspace(database, folder):
    if not re.fullmatch(r'opslens_java_eval_[0-9a-f]{32}', database):
        raise ValueError('Only a newly generated evaluation database is allowed')
    passwords = credentials()
    identities = [dict(subject='rehearsal-'+role, role=role, token=secrets.token_urlsafe(40))
                  for role in ('platform', 'security', 'intern')]
    files = {
        'auth.json': identities,
        'readers.json': dict(database=database, reader_passwords={r: passwords[f'READER_{r.upper()}_PASSWORD'] for r in ('platform','security','intern')}),
        'drafts.json': dict(database=database, draft_password=passwords['DRAFTS_PASSWORD']),
        'review-db.json': dict(database=database, password=passwords['REVIEW_PASSWORD']),
        'publish-db.json': dict(database=database, password=passwords['PUBLISH_PASSWORD']),
        'reviewers.json': dict(demo=['rehearsal-security'], google=[]),
        'publishers.json': dict(demo=['rehearsal-intern'], google=[]),
    }
    for name, value in files.items():
        private_json(folder/name, value)
    jar = folder/'workspace.jar'
    shutil.copyfile(JAR, jar)
    options = {'auth.config-path':'auth.json', 'database.config-path':'readers.json', 'drafts.config-path':'drafts.json',
               'workflows.reviewer-config-path':'review-db.json', 'workflows.publisher-config-path':'publish-db.json',
               'workflows.reviewers-path':'reviewers.json', 'workflows.publishers-path':'publishers.json'}
    command = ['java','-jar',str(jar),'--server.address=127.0.0.1','--server.port=0',
               '--runreviq.auth.mode=demo','--runreviq.search.mode=reviewed-v5']
    command += ['--runreviq.'+name+'.enabled=true' for name in ('web','database','drafts','workflows')]
    command += ['--runreviq.'+name+'='+str(folder/file) for name,file in options.items()]
    process = None
    try:
        with (folder/'java.log').open('w') as log:
            os.chmod(folder/'java.log', 0o600)
            process = subprocess.Popen(command, cwd=folder, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic()+45
            while time.monotonic()<deadline:
                if process.poll() is not None:
                    raise RuntimeError('Rehearsal server exited during startup')
                match = re.search(r'Tomcat started on port ([0-9]+) ', (folder/'java.log').read_text())
                if match:
                    port = int(match.group(1))
                    if http_json(port,'/health').get('runtime') != 'java':
                        raise RuntimeError('Unexpected runtime')
                    yield port, identities
                    return
                time.sleep(.1)
            raise RuntimeError('Rehearsal startup timed out')
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)


def verify(port, identities):
    # Check packaged bytes from a working directory outside the source checkout.
    for route, name in {'/':'index.html','/assets/app.js':'app.js','/assets/app.css':'app.css','/assets/theme.js':'theme.js'}.items():
        connection = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
        try:
            connection.request('GET',route)
            response = connection.getresponse()
            if response.status != 200 or response.read(2_000_000) != (ROOT/'runreviq/web'/name).read_bytes():
                raise RuntimeError('Packaged workspace differs from shared assets')
            if response.getheader('Cache-Control') != 'no-store':
                raise RuntimeError('Unexpected caching policy')
        finally:
            connection.close()
    for identity in identities:
        result = http_json(port,'/v1/me',token=identity['token'])
        if result['role'] != identity['role']:
            raise RuntimeError('Unexpected identity')


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--verify-only', action='store_true', help='Check the packaged server and immediately clean up')
    parser.add_argument('--minutes', type=int, default=15, choices=range(1,61), metavar='1..60')
    args = parser.parse_args(argv)
    # Build before creating data. Never accept an existing database or private config path.
    result = subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','-ntp','package','-DskipTests'], stdout=subprocess.DEVNULL)
    if result.returncode:
        raise SystemExit('Java build failed')
    try:
        with tempfile.TemporaryDirectory(prefix='runreviq-workspace-') as directory, evaluation_database() as database:
            ingest(load_documents(), dbname=database)
            folder = Path(directory)
            with workspace(database, folder) as (port, identities):
                verify(port, identities)
                print('Packaged Java workspace and demo identities verified.', flush=True)
                if not args.verify_only:
                    print(f'Isolated rehearsal: http://127.0.0.1:{port}/', flush=True)
                    print(f'Temporary demo keys: {folder / "auth.json"}', flush=True)
                    print('platform = author/reader; security = reviewer; intern = publisher (not unrestricted reader).', flush=True)
                    print(f'Use synthetic data only. Stops in {args.minutes} minutes or on Ctrl-C; all rehearsal changes are deleted.', flush=True)
                    time.sleep(args.minutes*60)
    except KeyboardInterrupt:
        print('Stopping rehearsal.')
    print('Owned Java process stopped; disposable database and private temporary files removed.')


if __name__ == '__main__':
    main()
