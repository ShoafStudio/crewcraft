import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch
spec=importlib.util.spec_from_file_location('message_agent',Path(__file__).with_name('message-agent.py'))
bridge=importlib.util.module_from_spec(spec);spec.loader.exec_module(bridge)

class MessageBridgeTest(unittest.TestCase):
    @patch.object(bridge.shutil,'which',return_value='/bin/hermes')
    def test_hermes_literal_message_and_private_file(self,_):
        message='hello $(touch /tmp/nope) `echo test`\nsecond line'
        def run(args,**kwargs):
            file=Path(args[-1]);self.assertEqual(file.read_text(),message)
            self.assertEqual(file.stat().st_mode & 0o777,0o600)
            self.assertIn('--query-file',args);self.assertIn('Headquarters',args)
            self.assertNotIn('--yolo',args);self.assertNotIn('shell',kwargs)
            return SimpleNamespace(returncode=0,stdout='Hermes reply')
        with patch.object(bridge.subprocess,'run',side_effect=run):
            self.assertEqual(bridge.run({'source':'Hermes','text':message})['reply'],'Hermes reply')
    @patch.object(bridge.shutil,'which',return_value='/bin/openclaw')
    def test_openclaw_main_only_no_external_delivery(self,_):
        args,_=bridge.prepare_request({'source':'OpenClaw','text':'hello'})
        self.assertEqual(args[args.index('--agent')+1],'main')
        self.assertNotIn('--deliver',args)
        with patch.object(bridge.subprocess,'run',return_value=SimpleNamespace(returncode=0,stdout='Notice\n{"result":{"payloads":[{"text":"Reply"}]}}')):
            self.assertEqual(bridge.run({'source':'OpenClaw','text':'hello'})['reply'],'Reply')
    @patch.object(bridge.shutil,'which',return_value='/bin/hermes')
    def test_connection_check_never_runs_agent(self,_):
        with patch.object(bridge.subprocess,'run') as run:
            self.assertTrue(bridge.run({'source':'Hermes','text':'check','check':True})['ok']);run.assert_not_called()
    @patch.object(bridge.shutil,'which',return_value='/bin/hermes')
    def test_expired_login_actionable_without_secret_output(self,_):
        with patch.object(bridge.subprocess,'run',return_value=SimpleNamespace(returncode=1,stdout='',stderr='invalid_grant Refresh token expired SECRET')):
            result=bridge.run({'source':'Hermes','text':'hello'})
            self.assertIn('hermes auth add anthropic',result['error']);self.assertNotIn('SECRET',result['error'])
    @patch.object(bridge.shutil,'which',return_value='/bin/hermes')
    def test_timeout_not_retried(self,_):
        with patch.object(bridge.subprocess,'run',side_effect=bridge.subprocess.TimeoutExpired('hermes',620)) as run:
            self.assertFalse(bridge.run({'source':'Hermes','text':'hello'})['ok']);self.assertEqual(run.call_count,1)
if __name__=='__main__':unittest.main()
