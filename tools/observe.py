#!/usr/bin/env python3
"""Read metadata and optional bounded assistant output for in-world computers. Never launch agents or write source stores."""
import json, os, sqlite3, time, hashlib, tempfile, shutil, re
from contextlib import contextmanager, ExitStack
from pathlib import Path
from datetime import datetime


@contextmanager
def metadata_database(file):
    """Read-only DB, with a stable private snapshot for older SQLite/WAL readers."""
    with ExitStack() as stack:
        try:
            db = sqlite3.connect(file.as_uri() + '?mode=ro', uri=True, timeout=2)
            stack.callback(db.close)
            db.execute('PRAGMA schema_version').fetchone()
        except sqlite3.OperationalError:
            # Never ignore a live WAL: it may contain committed metadata absent from the main DB.
            wal = Path(str(file) + '-wal')
            if wal.exists():
                raise
            before = file.stat()
            tmp = stack.enter_context(tempfile.TemporaryDirectory(prefix='hq-read-'))
            snapshot = Path(tmp) / 'snapshot.sqlite'
            shutil.copyfile(file, snapshot)
            snapshot.chmod(0o600)
            after = file.stat()
            if wal.exists() or (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise sqlite3.OperationalError('Database changed during read; retry next poll')
            db = sqlite3.connect(snapshot.as_uri() + '?mode=ro&immutable=1', uri=True)
            stack.callback(db.close)
        yield db


def stamp(value):
    if isinstance(value, (int, float)):
        return int(value if value > 1e11 else value * 1000)
    try:
        return int(datetime.fromisoformat(str(value).replace('Z', '+00:00')).timestamp() * 1000)
    except (ValueError, TypeError):
        return 0


def ident(value):
    return hashlib.sha256(str(value).encode()).hexdigest()[:16]


def tail_events(file):
    try:
        with Path(file).open('rb') as stream:
            size = stream.seek(0, 2)
            start = max(0, size - 262144)
            stream.seek(start)
            lines = stream.read().splitlines()
        if start:
            lines = lines[1:]
        result = []
        for line in lines:
            try:
                result.append(json.loads(line))
            except (ValueError, UnicodeDecodeError):
                pass
        return result
    except (OSError, TypeError):
        return []


def codex_state(events, updated, now):
    state, observed = 'idle', updated
    for event in events:
        payload = event.get('payload', {})
        if not isinstance(payload, dict):
            continue
        ts = stamp(event.get('timestamp'))
        kind = payload.get('type')
        if event.get('type') == 'event_msg':
            if kind in ('task_complete', 'turn_complete'):
                state, observed = 'done', ts or observed
            elif kind in ('turn_aborted', 'task_interrupted'):
                state, observed = 'blocked', ts or observed
            elif kind in ('task_started', 'user_message'):
                state, observed = 'thinking', ts or observed
        elif event.get('type') == 'response_item':
            if kind in ('function_call', 'custom_tool_call', 'function_call_output', 'custom_tool_call_output'):
                state, observed = 'running', ts or observed
            elif kind == 'reasoning':
                state, observed = 'thinking', ts or observed
            elif kind == 'message' and payload.get('role') == 'assistant' and payload.get('phase') == 'final_answer':
                state, observed = 'done', ts or observed
    if state in ('running', 'thinking') and now - observed > 180000:
        return 'idle', 'No recent event; execution status unknown', observed
    labels = {'idle': 'Execution status unknown', 'done': 'Last turn completed', 'blocked': 'Last turn interrupted', 'running': 'Recent tool activity', 'thinking': 'Recent reasoning activity'}
    return state, labels[state], observed


def screen_text(content):
    """Only visible assistant text, never hidden reasoning, tool arguments or tool results."""
    if isinstance(content, list):
        text = '\n'.join(str(p.get('text','')) for p in content if isinstance(p,dict) and p.get('type') in ('text','output_text'))
    elif isinstance(content,str):
        text=content
    else:
        return ''
    text=re.sub(r'(?i)(bearer\s+|(?:api[_-]?key|access[_-]?token|password|secret)\s*[:=]\s*)[^\s,;]+',r'\1[redacted]',text)
    text=re.sub(r'\b(?:sk-(?:ant-|proj-)?|gh[pousr]_)[A-Za-z0-9_-]{12,}', '[redacted]',text)
    return text[:1600]


def collect_screens(home):
    result=[]
    def add(source,agent,key,when,text):
        text=screen_text(text)
        if text.strip():result.append({'source':source,'agent':agent,'id':ident(key),'at':stamp(when),'text':text})
    sources=os.environ.get('HQ_SOURCES','Codex,OpenClaw,Hermes').split(',')
    if 'Hermes' in sources:
        root=Path(os.environ.get('HERMES_HOME',home/'.hermes')).expanduser()
        files=[root/'state.db']+sorted((root/'profiles').glob('*/state.db'))
        for file in files:
            if not file.is_file():continue
            try:
                with metadata_database(file) as db:
                    db.row_factory=sqlite3.Row
                    cols={r[1] for r in db.execute('pragma table_info(sessions)')}
                    rows=db.execute('SELECT m.id,m.timestamp,m.content,m.tool_calls,'+('s.profile_name' if 'profile_name' in cols else 'NULL AS profile_name')+" FROM messages m JOIN sessions s ON s.id=m.session_id WHERE m.role='assistant' ORDER BY m.timestamp DESC LIMIT 6").fetchall()
                    for r in reversed(rows):
                        agent=(r['profile_name'] or 'main') if file==root/'state.db' else file.parent.name
                        if agent == 'default':agent='main'
                        content=r['content'] or ''
                        if content.startswith('['):
                            try:content=json.loads(content)
                            except ValueError:pass
                        add('Hermes',agent,str(file)+':'+str(r['id']),r['timestamp'],content)
                        if r['tool_calls']:
                            try:
                                names=[t.get('function',{}).get('name','') for t in json.loads(r['tool_calls']) if isinstance(t,dict)]
                                if names:add('Hermes',agent,str(file)+':tool:'+str(r['id']),r['timestamp'],'Tools: '+', '.join(names))
                            except (ValueError,TypeError):pass
            except (OSError,sqlite3.Error):pass
    if 'OpenClaw' in sources:
        root=Path(os.environ.get('OPENCLAW_STATE_DIR',home/'.openclaw')).expanduser()
        for file in sorted((root/'agents').glob('*/agent/openclaw-agent.sqlite')):
            try:
                with metadata_database(file) as db:
                    rows=db.execute('SELECT session_id,seq,event_json,created_at FROM transcript_events ORDER BY created_at DESC LIMIT 24').fetchall()
                for sid,seq,raw,when in reversed(rows):
                    e=json.loads(raw);m=e.get('message',{})
                    if m.get('role')!='assistant':continue
                    add('OpenClaw',file.parent.parent.name,str(file)+str(sid)+str(seq),when,m.get('content',[]))
                    names=[c.get('name','') for c in m.get('content',[]) if isinstance(c,dict) and c.get('type') in ('toolCall','tool_use')]
                    if names:add('OpenClaw',file.parent.parent.name,str(file)+str(sid)+str(seq)+'tools',when,'Tools: '+', '.join(names))
            except (OSError,sqlite3.Error,ValueError,TypeError,AttributeError):pass
    return sorted(result,key=lambda r:r['at'])[-180:]


def collect():
    now = int(time.time() * 1000)
    home = Path.home()
    sessions, projects, sources = [], {}, []

    def project(raw):
        if not raw:
            return None
        p = Path(raw).expanduser()
        if not p.is_dir() or p in (home, Path('/'), Path('/tmp')):
            return None
        key = ident(p)
        projects[key] = {'id': key, 'name': p.name, 'path': str(p)}
        return key

    codex = Path(os.environ.get('CODEX_HOME', home / '.codex')).expanduser()
    dbs = sorted(codex.glob('state_*.sqlite'), key=lambda p: int(p.stem.split('_')[-1]), reverse=True)
    try:
        if not dbs:
            raise FileNotFoundError('Local Codex database not found')
        with metadata_database(dbs[0]) as db:
            db.row_factory = sqlite3.Row
            total = db.execute('SELECT count(*) FROM threads WHERE archived=0').fetchone()[0]
            for row in db.execute('SELECT DISTINCT cwd FROM threads WHERE archived=0'):
                project(row['cwd'])
            cols = {r[1] for r in db.execute('PRAGMA table_info(threads)')}
            title = "COALESCE(NULLIF(name,''),title)" if 'name' in cols else 'title'
            rows = db.execute('SELECT id,' + title + ' AS title,cwd,updated_at,rollout_path FROM threads WHERE archived=0 ORDER BY (id=?) DESC, updated_at DESC LIMIT 60', (os.environ.get('HQ_CODEX_THREAD_ID',''),)).fetchall()
        for row in rows:
            state, activity, observed = codex_state(tail_events(row['rollout_path']), stamp(row['updated_at']), now)
            sessions.append({'id': 'codex-' + ident(row['id']), 'source': 'Codex', 'title': (row['title'] or 'Untitled chat')[:160], 'projectId': project(row['cwd']), 'state': state, 'activity': activity, 'updatedAt': observed, 'reference': 'codex://threads/' + row['id']})
        sources.append({'name': 'Codex', 'status': 'connected', 'detail': f'{total} unarchived chats; latest {len(rows)} displayed. Local metadata, not a live control connection.'})
    except (OSError, sqlite3.Error) as error:
        sources.append({'name': 'Codex', 'status': 'unavailable', 'detail': str(error)})

    if 'OpenClaw' in os.environ.get('HQ_SOURCES','Codex,OpenClaw,Hermes').split(','):
        claw = Path(os.environ.get('OPENCLAW_STATE_DIR', home / '.openclaw')).expanduser()
        try:
            files = list((claw / 'agents').glob('*/sessions/sessions.json'))
            databases = list((claw / 'agents').glob('*/agent/openclaw-agent.sqlite'))
            if not files and not databases:
                raise FileNotFoundError('Local OpenClaw session indexes not found')
            records, failures, total = {}, [], 0

            def add_claw(row, agent, reference):
                if not isinstance(row, dict) or not row.get('sessionId'):
                    return
                updated = stamp(row.get('lastActivityAt') or row.get('updatedAt'))
                status = row.get('status', '')
                state = 'done' if status in ('done', 'completed') else 'blocked' if row.get('abortedLastRun') or status in ('failed', 'error', 'aborted', 'killed') else 'running' if status == 'running' and 0 <= now - updated < 180000 else 'idle'
                key = 'openclaw-' + ident(agent + row['sessionId'])
                item = {'id': key, 'source': 'OpenClaw', 'runtimeAgent':agent, 'title': f"{agent} / {row.get('label') or row.get('displayName') or row['sessionId'][:8]}", 'projectId': project(row.get('cwd') or row.get('workspace')), 'state': state, 'activity': f'Session index: {status or "unknown"}' + (' (stale)' if now - updated > 180000 else ''), 'updatedAt': updated, 'reference': str(reference)}
                if key not in records or records[key]['updatedAt'] <= updated:
                    records[key] = item

            for file in files:
                try:
                    rows = json.loads(file.read_text())
                    total += len(rows)
                    for row in rows.values():
                        add_claw(row, file.parent.parent.name, file)
                except (OSError, ValueError, AttributeError, TypeError):
                    failures.append(file.parent.parent.name)
            for file in databases:
                try:
                    with sqlite3.connect(file.as_uri() + '?mode=ro', uri=True, timeout=0.2) as db:
                        db.row_factory = sqlite3.Row
                        cols = {r[1] for r in db.execute('PRAGMA table_info(session_nodes)')}
                        if not cols:
                            continue  # Empty agent DB: legacy JSON may still own this store.
                        optional = ','.join(k if k in cols else 'NULL AS ' + k for k in ('label','display_name','last_activity_at'))
                        where = ' WHERE archived_at IS NULL' if 'archived_at' in cols else ''
                        total += db.execute('SELECT count(*) FROM session_nodes' + where).fetchone()[0]
                        rows = db.execute('SELECT current_session_id,updated_at,status,' + optional + ' FROM session_nodes' + where + ' ORDER BY updated_at DESC LIMIT 60').fetchall()
                    for row in rows:
                        add_claw({'sessionId':row['current_session_id'],'updatedAt':row['updated_at'],'lastActivityAt':row['last_activity_at'],'status':row['status'],'label':row['label'],'displayName':row['display_name']}, file.parent.parent.name, file)
                except (OSError, sqlite3.Error):
                    failures.append(file.parent.parent.name)
            recent = sorted(records.values(), key=lambda s: (s['state']=='running',s['updatedAt']), reverse=True)[:60]
            sessions.extend(recent)
            status = 'partial' if failures and recent else 'unavailable' if failures else 'connected'
            sources.append({'name':'OpenClaw','status':status,'detail':f'{total} indexed records across {len(files)+len(databases)} agent stores; latest {len(recent)} displayed.' + (f' Unreadable stores: {", ".join(failures)}.' if failures else '') + ' Activity is inferred from session metadata.'})
        except (OSError, ValueError) as error:
            sources.append({'name': 'OpenClaw', 'status': 'unavailable', 'detail': str(error)})


    # Explicit external snapshot is the adapter boundary for remote Hermes installations.
    hermes = os.environ.get('HQ_HERMES_SNAPSHOT')
    if hermes:
        try:
            file = Path(hermes).expanduser()
            data = json.loads(file.read_text())
            for row in data['sessions'][:60]:
                updated = stamp(row['updatedAt'])
                state = row.get('state', 'idle')
                if state not in ('idle', 'thinking', 'reading', 'editing', 'running', 'testing', 'waiting_user', 'blocked', 'done', 'error'):
                    state = 'idle'
                stale = now - updated > 180000
                if stale and state not in ('done', 'error', 'blocked'):
                    state = 'idle'
                sessions.append({'id': 'hermes-' + ident(row['id']), 'source': 'Hermes', 'title': str(row.get('title', row['id']))[:160], 'projectId': project(row.get('cwd')), 'state': state, 'activity': 'Stale snapshot; current status unknown' if stale else 'External snapshot: ' + state, 'updatedAt': updated, 'reference': str(file)})
            sources.append({'name': 'Hermes', 'status': 'connected', 'detail': 'External snapshot; freshness shown per session.'})
        except (OSError, ValueError, KeyError, TypeError) as error:
            sources.append({'name': 'Hermes', 'status': 'unavailable', 'detail': str(error)})
    else:
        try:
            dbfile = Path(os.environ.get('HERMES_HOME', home / '.hermes')).expanduser() / 'state.db'
            if not dbfile.is_file():
                raise FileNotFoundError('Hermes state.db not found on this machine')
            with sqlite3.connect(dbfile.as_uri() + '?mode=ro', uri=True, timeout=2) as db:
                db.row_factory = sqlite3.Row
                columns = {r[1] for r in db.execute('PRAGMA table_info(sessions)')}
                fields = ['id', 'started_at', 'ended_at'] + [k for k in ('title', 'last_activity_at', 'end_reason', 'cwd', 'git_repo_root', 'parent_session_id', 'profile_name') if k in columns]
                rows = db.execute('SELECT ' + ','.join(fields) + ' FROM sessions ORDER BY started_at DESC LIMIT 60').fetchall()
            for raw in rows:
                row = dict(raw)
                updated = stamp(row.get('last_activity_at') or row.get('ended_at') or row['started_at'])
                ended = row['ended_at'] is not None
                state = 'done' if ended else 'idle'
                activity = 'Session ended: ' + str(row.get('end_reason') or 'unspecified') if ended else 'Open session; execution status unknown'
                sessions.append({'id': 'hermes-' + ident(row['id']), 'source': 'Hermes', 'runtimeAgent':('subagent-' + str(row['id'])) if row.get('parent_session_id') else (row.get('profile_name') if row.get('profile_name') not in (None,'default','main') else 'main'), 'title': str(row.get('title') or row['id'])[:160], 'projectId': project(row.get('git_repo_root') or row.get('cwd')), 'state': state, 'activity': activity, 'updatedAt': updated, 'reference': str(dbfile)})
            sources.append({'name': 'Hermes', 'status': 'connected', 'detail': f'{len(rows)} recent session records; an open session does not prove active execution.'})
        except (OSError, sqlite3.Error) as error:
            sources.append({'name': 'Hermes', 'status': 'unavailable', 'detail': str(error)})

    # Configured runtime identities are team members; session history is not a roster.
    members=[]
    if 'OpenClaw' in os.environ.get('HQ_SOURCES','Codex,OpenClaw,Hermes').split(','):
        root=Path(os.environ.get('OPENCLAW_STATE_DIR',str(home/'.openclaw')))/'agents'
        if root.is_dir():
            members.extend({'source':'OpenClaw','id':p.name,'name':p.name} for p in sorted(root.iterdir()) if p.is_dir() and p.name!='main')
    profiles=Path(os.environ.get('HERMES_HOME',str(home/'.hermes')))/'profiles'
    if 'Hermes' in os.environ.get('HQ_SOURCES','Codex,OpenClaw,Hermes').split(',') and profiles.is_dir():
        members.extend({'source':'Hermes','id':p.name,'name':p.name} for p in sorted(profiles.iterdir()) if p.is_dir())

    roots = os.environ.get('HQ_PROJECT_ROOTS', '').split(os.pathsep)
    for root in filter(None, roots):
        p = Path(root).expanduser()
        if p.is_dir():
            for child in p.iterdir():
                if child.is_dir() and (child / '.git').exists():
                    project(child)
    sessions.sort(key=lambda s: (s['state'] in ('running','thinking','editing','reading','testing','waiting_user'), s['updatedAt']), reverse=True)
    return {'screens':collect_screens(home) if os.environ.get('HQ_MONITOR_OUTPUT')=='1' else [], 'observedAt': now, 'members':members, 'sources': sources, 'projects': sorted(projects.values(), key=lambda p:p['name'].lower()), 'sessions': sessions}

if __name__ == '__main__':
    print(json.dumps(collect()))
