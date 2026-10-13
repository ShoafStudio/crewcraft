import unittest
import tempfile
import sqlite3
import json
import os
from pathlib import Path
from unittest.mock import patch
from observe import collect, codex_state, metadata_database, screen_text

class ObserverTests(unittest.TestCase):
    def test_completed_turn_survives_later_bookkeeping(self):
        events = [{'type':'event_msg','timestamp':'2026-10-07T01:00:00Z','payload':{'type':'task_complete'}}, {'type':'event_msg','payload':{'type':'token_count'}}]
        self.assertEqual(codex_state(events,0,1791339000000)[0], 'done')

    def test_old_tool_event_does_not_claim_live_execution(self):
        events = [{'type':'response_item','timestamp':'2026-10-07T01:00:00Z','payload':{'type':'function_call'}}]
        self.assertEqual(codex_state(events,0,1791339000000)[0], 'idle')
        self.assertEqual(codex_state(events,0,1791334801000)[0], 'running')

    def test_missing_sources_do_not_create_databases(self):
        with tempfile.TemporaryDirectory() as d, patch.dict(os.environ, {'CODEX_HOME':d+'/codex', 'OPENCLAW_STATE_DIR':d+'/claw','HERMES_HOME':d+'/hermes','HQ_PROJECT_ROOTS':d,'HQ_HERMES_SNAPSHOT':''}):
            result = collect()
            self.assertEqual(result['sessions'], [])
            self.assertEqual(len(list(Path(d).iterdir())), 0)

    def test_hermes_and_claw_metadata_no_transcripts(self):
        with tempfile.TemporaryDirectory() as d, patch.dict(os.environ, {'CODEX_HOME':d+'/codex', 'OPENCLAW_STATE_DIR':d+'/claw','HERMES_HOME':d+'/hermes','HQ_PROJECT_ROOTS':d,'HQ_HERMES_SNAPSHOT':''}):
            h = Path(d)/'hermes';h.mkdir()
            with sqlite3.connect(h/'state.db') as c:
                c.execute('create table sessions(id text, title text, started_at real, ended_at real)')
                c.execute("insert into sessions values('h1','Hermes task',1791334800,null)")
            p=Path(d)/'claw/agents/main/sessions';p.mkdir(parents=True)
            (p/'sessions.json').write_text(json.dumps({'key':{'sessionId':'c1','status':'done','updatedAt':1791334800000,'secret':'NEVER_EXPORT'}}))
            result=collect()
            self.assertEqual(len(result['sessions']),2)
            self.assertNotIn('NEVER_EXPORT',json.dumps(result))
            self.assertEqual(next(s for s in result['sessions'] if s['source']=='Hermes')['state'],'idle')
            with patch.dict(os.environ, {'HQ_SOURCES':'Codex,Hermes'}):
                hermes_only=collect()
            self.assertEqual([s['source'] for s in hermes_only['sessions']], ['Hermes'])
            self.assertNotIn('OpenClaw',[s['name'] for s in hermes_only['sources']])

    def test_openclaw_sqlite_current_layout(self):
        with tempfile.TemporaryDirectory() as d, patch.dict(os.environ, {'CODEX_HOME':d+'/codex', 'OPENCLAW_STATE_DIR':d+'/claw','HERMES_HOME':d+'/hermes','HQ_PROJECT_ROOTS':d,'HQ_HERMES_SNAPSHOT':''}):
            p=Path(d)/'claw/agents/main/agent';p.mkdir(parents=True)
            with sqlite3.connect(p/'openclaw-agent.sqlite') as c:
                c.execute('create table session_nodes(current_session_id text, updated_at integer, status text, label text, display_name text, last_activity_at integer, archived_at integer)')
                c.execute("insert into session_nodes values('one',1791334800000,'done','Researcher task',null,null,null)")
                c.execute("insert into session_nodes values('hidden',1791334800000,'done',null,null,null,1)")
            result=collect()
            self.assertEqual(len(result['sessions']),1)
            self.assertEqual(result['sessions'][0]['title'],'main / Researcher task')
            self.assertEqual(result['sessions'][0]['state'],'done')

    def test_snapshot_fallback_does_not_modify_source_or_ignore_wal(self):
        with tempfile.TemporaryDirectory() as d:
            source = Path(d)/'state.sqlite'
            connect = sqlite3.connect
            with connect(source) as db:
                db.execute('create table example(value integer)')
                db.execute('insert into example values(42)')
            original = source.read_bytes()
            def old_reader(database, **kwargs):
                if str(database).startswith(source.as_uri()):
                    raise sqlite3.OperationalError('unable to open database file')
                return connect(database, **kwargs)
            with patch('observe.sqlite3.connect', side_effect=old_reader):
                with metadata_database(source) as db:
                    self.assertEqual(db.execute('select value from example').fetchone()[0],42)
                    snapshot = Path(db.execute('pragma database_list').fetchone()[2])
                    self.assertTrue(snapshot.exists())
                self.assertFalse(snapshot.exists())
                self.assertEqual(source.read_bytes(), original)
                Path(str(source)+'-wal').write_bytes(b'active WAL')
                with self.assertRaises(sqlite3.OperationalError):
                    with metadata_database(source):
                        pass

class MonitorOutputTests(unittest.TestCase):
    def test_only_visible_assistant_text(self):
        content=[{'type':'thinking','text':'private reasoning'}, {'type':'tool_use','input':{'secret':'hidden'}}, {'type':'text','text':'Visible reply'}]
        self.assertEqual(screen_text(content),'Visible reply')

    def test_redacts_credentials_and_bounds_output(self):
        self.assertNotIn('123456789012345678901234',screen_text('api_key=123456789012345678901234'))
        self.assertNotIn('abcdefghijklmnop',screen_text('sk-ant-abcdefghijklmnop'))
        self.assertEqual(len(screen_text('x'*9000)),1600)

if __name__ == '__main__': unittest.main()
