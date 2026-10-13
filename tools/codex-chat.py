#!/usr/bin/env python3
"""Explicit pinned-chat output adapter. Returns assistant text and status, never tools or reasoning."""
import json,sys,sqlite3,time
from pathlib import Path
from observe import metadata_database,tail_events,codex_state,stamp
thread=sys.argv[1]
home=Path.home()/'.codex'
dbs=sorted(home.glob('state_*.sqlite'),key=lambda p:int(p.stem.split('_')[-1]),reverse=True)
with metadata_database(dbs[0]) as db:
    db.row_factory=sqlite3.Row
    row=db.execute('select rollout_path,updated_at from threads where id=?',(thread,)).fetchone()
    if row is None:raise ValueError('Pinned chat not found')
events=tail_events(row['rollout_path']);state,activity,observed=codex_state(events,stamp(row['updated_at']),int(time.time()*1000))
messages=[]
for event in events:
    p=event.get('payload',{})
    if not isinstance(p,dict):continue
    # Rollouts contain both response_item and event_msg mirrors. Use only response_item.
    if event.get('type')=='response_item' and p.get('type')=='message' and p.get('role')=='assistant':
        text='\n'.join(c.get('text','') for c in p.get('content',[]) if c.get('type')=='output_text')
        if text and p.get('phase') in ('commentary','final_answer'):
            messages.append({'id':p.get('id') or event.get('timestamp','')+p['phase'],'text':text[:64000],'phase':p['phase'],'timestamp':stamp(event.get('timestamp'))})
print(json.dumps({'state':state,'activity':activity,'observedAt':observed,'messages':messages[-12:]}))
