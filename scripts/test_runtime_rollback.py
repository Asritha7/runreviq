"""Exercise Java -> Python -> Java with disposable data, never the working runtime."""
import argparse
from contextlib import contextmanager
from functools import partial
import http.client
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from scripts.rehearse_java_workspace import workspace, runtime_environment
from runreviq.evaluate_java import evaluation_database, private_json
from runreviq.database import credentials, ingest
from runreviq.selector import load_documents


def request(port, path, token=None, body=None, expected=200, markdown=False):
    conn = http.client.HTTPConnection('127.0.0.1', port, timeout=10)
    headers = {'Content-Type': 'text/markdown' if markdown else 'application/json'}
    if token: headers['Authorization'] = 'Bearer '+token
    try:
        conn.request('GET' if body is None else 'POST',path,
                     body.encode() if markdown else None if body is None else json.dumps(body).encode(),headers)
        response=conn.getresponse(); raw=response.read(4_000_001)
        if response.status != expected or len(raw)>4_000_000:
            raise RuntimeError(f'HTTP contract failed: {path}, expected {expected}, got {response.status}')
        return json.loads(raw) if raw else None
    finally: conn.close()


def stopped(port):
    try: request(port,'/health')
    except OSError: return
    raise RuntimeError('Previous process is still serving the rollback port')


def python_child(config_path, port):
    from runreviq import api, database, drafts, submissions, publications, lifecycle
    import uvicorn
    config=json.loads(config_path.read_text()); db=config['database']
    if not re.fullmatch(r'opslens_java_eval_[0-9a-f]{32}',db) or port==8000:
        raise ValueError('Not an isolated rollback target')
    # Test process only: bind every API data operation explicitly to the generated DB.
    for module in (database,drafts,submissions,publications):
        module.credentials=lambda:config['credentials']
    for module,names in ((submissions,('submit','own_submissions','review_queue','submission_detail','decide')),
                         (publications,('queue','detail','publish')),
                         (lifecycle,('documents','history','revision','withdraw'))):
        for name in names:setattr(module,name,partial(getattr(module,name),dbname=db))
    folder=config_path.parent
    app=api.create_app(key_file=folder/'auth.json',oidc_file=None,
        document_loader=partial(database.load_authorized_documents,dbname=db),
        revision_loader=partial(database.load_authorized_revision,dbname=db),
        searcher=partial(api.search,dbname=db),
        draft_writer=partial(drafts.create_draft,dbname=db),draft_lister=partial(drafts.list_drafts,dbname=db),
        draft_reader=partial(drafts.get_draft,dbname=db),review_writer=partial(drafts.save_review,dbname=db),
        reviewers_file=folder/'reviewers.json',publishers_file=folder/'publishers.json')
    uvicorn.run(app,host='127.0.0.1',port=port,access_log=False)


@contextmanager
def python_runtime(db, folder, port):
    values={k:v for k,v in credentials().items() if k in ('DRAFTS_PASSWORD','REVIEW_PASSWORD','PUBLISH_PASSWORD',
            'READER_PLATFORM_PASSWORD','READER_SECURITY_PASSWORD','READER_INTERN_PASSWORD')}
    private_json(folder/'python.json',dict(database=db,credentials=values))
    with (folder/'python.log').open('w') as log:
        process=subprocess.Popen([sys.executable,str(Path(__file__).resolve()),'--child',str(folder/'python.json'),'--port',str(port)],
            cwd=folder,env=runtime_environment(),stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+20
            while time.monotonic()<deadline:
                if process.poll() is not None:raise RuntimeError('Python rollback process exited')
                try:
                    if request(port,'/health')=={'status':'ok'}:break
                except OSError:time.sleep(.1)
            else:raise RuntimeError('Python rollback startup timed out')
            yield
        finally:
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)


def run():
    subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','-ntp','package','-DskipTests'],stdout=subprocess.DEVNULL,check=True)
    with tempfile.TemporaryDirectory(prefix='runreviq-rollback-') as temporary,evaluation_database() as db:
        root=Path(temporary); first=root/'first';first.mkdir(mode=0o700); recovery=root/'recovery';recovery.mkdir(mode=0o700)
        ingest(load_documents(),dbname=db)
        query=dict(question='Inspect queue leases before restarting',service='rollback-probe',software_version='1.0.0',environment='staging')
        source='# Rollback probe\nInspect queue leases before restarting.\n'
        with workspace(db,first) as (port,identities):
            tokens={r['role']:r['token'] for r in identities}
            author,reviewer,publisher=(tokens[r] for r in ('platform','security','intern'))
            draft=request(port,'/v1/drafts?filename=rollback.md',author,source,201,True)
            path='/v1/drafts/'+draft['id']
            metadata=dict(title='Rollback probe',service='rollback-probe',environment='staging',version_min='1.0.0',version_max_exclusive='2.0.0',key_rotation_enabled='none')
            request(port,path+'/review',author,dict(expected_revision=0,metadata=metadata))
            sub=request(port,path+'/submit',author,dict(revision=1))
            request(port,'/v1/submissions/'+sub['id']+'/decision',reviewer,dict(decision='approved',comment='Synthetic rollback check'))
            publication=request(port,'/v1/publications/'+sub['id'],publisher,dict(allowed_roles=['platform'],metadata_verified=True,claims=[dict(action='inspect_queue',rule='must',start_line=2,end_line=2)]))
            doc=publication['document_id']; revision='/v1/documents/'+doc+'/revisions/1'; history='/v1/publication-management/documents/'+doc
            original=request(port,revision,author); search=request(port,'/v1/search',author,query)
            assert search['status']=='selected' and doc in json.dumps(search['passages'])
            request(port,revision,publisher,expected=404)
        stopped(port)
        with python_runtime(db,first,port):
            assert request(port,path,author)['content']==source
            assert request(port,revision,author)==original
            assert request(port,'/v1/search',author,query)==search
            request(port,revision,publisher,expected=404)
            request(port,path+'/review',author,dict(expected_revision=1,metadata=dict(metadata,title='Python rollback edit')))
            request(port,history+'/withdraw',publisher,dict(expected_revision=1,reason='Synthetic rollback withdrawal'))
            assert request(port,'/v1/search',author,query)['passages']==[]
        stopped(port)
        with workspace(db,recovery) as (recovered,identities):
            tokens={r['role']:r['token'] for r in identities}
            assert request(recovered,path,tokens['platform'])['review']['revision']==2
            assert request(recovered,revision,tokens['platform'])['content']==source
            assert request(recovered,history,tokens['intern'])['revisions'][0]['status']=='withdrawn'
            assert request(recovered,'/v1/search',tokens['platform'],query)['passages']==[]
            request(recovered,revision,tokens['intern'],expected=404)
        stopped(recovered)
    print('PASS: Java stopped; Python served the same port/database and preserved citations/access; Java recovery read Python edits and withdrawal. Disposable data cleaned up.')


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--child',type=Path);parser.add_argument('--port',type=int)
    args=parser.parse_args()
    if args.child:
        if not args.port:parser.error('Child requires port')
        python_child(args.child,args.port)
    else:run()
