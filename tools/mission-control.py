#!/usr/bin/env python3
"""Read the public workspace board on its credential-owning host. Never emit keys.
CONFIG is injected over stdin by Foreman. No agent invocation or mutations.
"""
import json, os, re, urllib.request, urllib.error
from pathlib import Path

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

def collect(config):
    base = config['baseUrl'].rstrip('/')
    if not base.startswith('https://'):
        raise ValueError('Mission Control requires HTTPS')
    key = os.environ.get('MC_API_KEY', '')
    if not key and config.get('envFile'):
        content = Path(config['envFile']).expanduser().read_text()
        match = re.search(r'^\s*(?:export\s+)?MC_API_KEY\s*=\s*(.*?)\s*$', content, re.M)
        if match: key = match.group(1).strip('\"\'')
    if not key: raise ValueError('MC_API_KEY is not configured on the board host')
    opener = urllib.request.build_opener(NoRedirect)
    def get(endpoint):
        req = urllib.request.Request(base + '/api/v1/agent/' + endpoint, headers={'Authorization': 'Bearer ' + key})
        with opener.open(req, timeout=10) as response:
            body = response.read(4*1024*1024+1)
        if len(body)>4*1024*1024: raise ValueError('Board response too large')
        data = json.loads(body)
        if data.get('ok') is not True: raise ValueError('Board API did not confirm success')
        return data['data']
    capabilities = get('capabilities')
    rows=[]
    if capabilities.get('taskPagination'):
        from urllib.parse import urlencode
        cursor=''
        for _ in range(20):
            page=get('tasks?'+urlencode({'paginate':'true','limit':500,'cursor':cursor}))
            if not isinstance(page,dict) or not isinstance(page.get('page'),list): raise ValueError('Invalid task pagination')
            rows.extend(page['page'])
            if page.get('isDone') is True: break
            next_cursor=page.get('continueCursor')
            if not next_cursor or next_cursor==cursor: raise ValueError('Board pagination did not advance')
            cursor=next_cursor
        else: raise ValueError('Board exceeds collection limit; task list is incomplete')
    else:
        rows=get('tasks')
        if not isinstance(rows,list): raise ValueError('Invalid task response')
    projects={}
    if capabilities.get('projectList'):
        try:
            projects={p['_id']:p['name'] for p in get('projects')['projects']}
        except Exception: pass  # Project labels are optional; task availability is independent.
    fields=['_id','title','description','status','priority','assignee','projectId','createdAt','updatedAt']
    return {'ok':True,'tasks':[{**{k:r[k] for k in fields if k in r}, **({'projectName':projects[r['projectId']]} if r.get('projectId') in projects else {})} for r in rows]}

if __name__=='__main__':
    try: print(json.dumps(collect(CONFIG)))
    except urllib.error.HTTPError as error: print(json.dumps({'ok':False,'error':'Mission Control HTTP '+str(error.code)}))
    except Exception: print(json.dumps({'ok':False,'error':'Mission Control unavailable; verify the host connection, workspace key and public API'}))
