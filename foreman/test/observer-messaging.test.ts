import { describe,it,expect,vi } from 'vitest';
import {makeForeman,tempDir,rmrf,until} from './helpers.js';
import {AgentConversations,type MessageTarget} from '../src/agents/observer/messaging.js';

describe('main-agent conversations',()=>{
  it('routes Coordinator only to Hermes, deduplicates requests, and prevents concurrent/broadcast sends',async()=>{
    const home=tempDir();const {fm}=makeForeman(home,['--backend','observer']);
    fm.store.data.agents=[{...fm.store.data.agents[0]!,id:'coordinator',name:'Coordinator',skin:'marlow'}];
    let finish!:(value:{ok:boolean;reply:string})=>void;
    const invoke=vi.fn(()=>new Promise<{ok:boolean;reply:string}>(resolve=>{finish=resolve;}));
    const chat=new AgentConversations(fm,invoke);
    const target:MessageTarget={id:'coordinator',name:'Coordinator',source:'Hermes',chat:true,online:true,machine:{id:'coordinator',ssh:'coordinator@100.64.0.10',env:{}}};
    chat.targets.set('coordinator',target);
    try{
      const text='Explain this literally: $(touch /tmp/nope) `echo test`';
      expect(fm.resolveAgentId('marlow')).toBe('coordinator');
      expect(()=>chat.accept('all',text)).toThrow('Choose one of your configured main agents');
      chat.accept('coordinator',text,'req1');
      expect(invoke).toHaveBeenCalledExactlyOnceWith(target,expect.any(String),text);
      expect(chat.accept('coordinator',text,'req1')).toMatchObject({duplicate:true,state:'sending'});
      expect(()=>chat.accept('coordinator','other','req1')).toThrow('different content');
      expect(()=>chat.accept('coordinator','another','req2')).toThrow('previous message');
      finish({ok:true,reply:'Hermes reply'});
      await until(()=>!chat.isBusy('coordinator'));
      expect(chat.accept('coordinator',text,'req1')).toMatchObject({state:'complete',duplicate:true});
      expect(invoke).toHaveBeenCalledTimes(1);
      target.online=false;
      expect(()=>chat.accept('coordinator','hello','req3')).toThrow('offline');
    }finally{await fm.close();rmrf(home);}
  });
  it('never automatically resends a request interrupted by a restart',async()=>{
    const home=tempDir();const {fm}=makeForeman(home,['--backend','observer']);
    fm.store.data.backend.messageJobs=[{id:'job',target:'coordinator',requestId:'r',text:'hello',state:'sending',createdAt:1}];
    const invoke=vi.fn();const chat=new AgentConversations(fm,invoke);
    chat.targets.set('coordinator',{id:'coordinator',name:'Coordinator',source:'Hermes',chat:true,online:true,machine:{id:'coordinator',env:{}}});
    try{expect(chat.accept('coordinator','hello','r')).toMatchObject({duplicate:true,state:'uncertain'});expect(invoke).not.toHaveBeenCalled();}
    finally{await fm.close();rmrf(home);}
  });
});
