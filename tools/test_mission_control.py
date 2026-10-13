import importlib.util, json, unittest
from unittest.mock import patch
from pathlib import Path
from tempfile import TemporaryDirectory
from io import BytesIO
spec=importlib.util.spec_from_file_location('mc',Path(__file__).with_name('mission-control.py'))
mc=importlib.util.module_from_spec(spec);spec.loader.exec_module(mc)
class MissionControlTest(unittest.TestCase):
 def test_key_stays_in_header_and_paginated_feed_is_projected(self):
  with TemporaryDirectory() as folder:
   env=Path(folder)/'.env';env.write_text('MC_API_KEY="private-test-key"\n')
   replies=[{'taskPagination':True},{'page':[{'_id':'1','title':'Review','workspaceKey':'do-not-copy'}],'isDone':False,'continueCursor':'next'},{'page':[{'_id':'2','title':'Decision'}],'isDone':True,'continueCursor':''}]
   seen=[]
   class Opener:
    def open(self,req,timeout):
     seen.append(req);return BytesIO(json.dumps({'ok':True,'data':replies.pop(0)}).encode())
   with patch.dict(mc.os.environ,{},clear=True),patch.object(mc.urllib.request,'build_opener',return_value=Opener()):
    result=mc.collect({'baseUrl':'https://board.example.invalid','envFile':str(env)})
   self.assertEqual(len(result['tasks']),2);self.assertNotIn('private-test-key',json.dumps(result));self.assertNotIn('workspaceKey',json.dumps(result))
   self.assertTrue(all(r.get_header('Authorization')=='Bearer private-test-key' for r in seen));self.assertIn('cursor=next',seen[-1].full_url)
 def test_refuses_redirect_with_credentials(self):
  self.assertIsNone(mc.NoRedirect().redirect_request(None,None,302,'',{},'https://example.com'))
if __name__=='__main__':unittest.main()
