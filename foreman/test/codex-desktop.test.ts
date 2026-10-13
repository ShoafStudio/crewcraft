import {describe,it,expect,vi} from 'vitest';
const exec=vi.hoisted(()=>vi.fn());
vi.mock('node:child_process',()=>({execFile:exec}));
import {CodexDesktop} from '../src/agents/observer/codex-desktop.js';
import {tempDir,rmrf} from './helpers.js';
describe('Codex existing-chat queue',()=>{
  it('queues literal user text without resuming or changing the model or permissions',async()=>{
    const home=tempDir(),id='11111111-2222-4333-8444-555555555555';
    exec.mockImplementation((_bin,args,_opts,callback)=>callback(null,`Queued message test for thread ${id}.`));
    try{
      const text='literal $(echo nope) `test`\nline two';
      expect(await new CodexDesktop(home,id).send(text)).toMatchObject({ok:true,queued:true});
      expect(exec.mock.calls.at(-1)?.[1]).toEqual(['queue','--thread',id,'--message',text]);
    }finally{rmrf(home);}
  });
  it('reports ambiguity and does not retry on connection failure',async()=>{
    exec.mockClear();exec.mockImplementation((_bin,_args,_opts,callback)=>callback(new Error('timeout'),''));
    const home=tempDir();try{await expect(new CodexDesktop(home,'11111111-2222-4333-8444-555555555555').send('hello')).rejects.toThrow('no automatic retry');expect(exec).toHaveBeenCalledTimes(1);}finally{rmrf(home);}
  });
});
