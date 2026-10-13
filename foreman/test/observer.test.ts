import fs from 'node:fs';
import path from 'node:path';
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { makeForeman, tempDir, rmrf } from './helpers.js';
import { ObserverBackend, collectMachine } from '../src/agents/observer/index.js';
import {Builder} from '../src/agents/observer/builder.js';
import {Construction} from '../src/agents/observer/construction.js';
import type { Outbound, ClientMessage } from '../src/protocol.js';

describe('observer', () => {
  beforeEach(()=>{vi.spyOn(Construction.prototype,'refresh').mockResolvedValue();vi.spyOn(Builder.prototype,'refresh').mockResolvedValue();});
  afterEach(()=>vi.restoreAllMocks());
  it('rejects all mutating client messages before changing state', async () => {
    const home = tempDir(); const {fm} = makeForeman(home, ['--backend','observer']);
    const before = JSON.stringify(fm.store.data);
    try {
      for (const type of ['goal.submit','decision.answer','task.action','agent.action','repo.add']) {
        const replies: Outbound[]=[];
        await fm.handle({type,id:'x'} as ClientMessage, m=>replies.push(m));
        expect(replies).toContainEqual(expect.objectContaining({type:'ack',ok:false,error:expect.stringContaining('read-only')}));
        expect(JSON.stringify(fm.store.data)).toBe(before);
      }
    } finally {await fm.close();rmrf(home);}
  });
  it('collects isolated metadata and skips repo manager even with repo arguments', async () => {
    const home = tempDir(); const {fm} = makeForeman(home,['--backend','observer','--repo','/does/not/exist']);
    fs.writeFileSync(path.join(home,'machines.json'), JSON.stringify([{id:'test',label:'Fixture Mac',env:{CODEX_HOME:home,OPENCLAW_STATE_DIR:home,HERMES_HOME:home,HQ_PROJECT_ROOTS:home}}]));
    try {
      await fm.start(new ObserverBackend(fm));
      expect(fm.status.backend).toBe('observer');
      expect(fm.store.data.repos).toEqual([]);
      expect(fm.memory.get('shared/machines')?.body).toContain('Fixture Mac');
      expect(fm.store.data.backend.observer).toMatchObject({online:1,sessions:0});
    } finally {await fm.close();rmrf(home);}
  });
  it('namespaces identical remote sessions and expires active snapshot data', async () => {
    const home = tempDir(); const {fm} = makeForeman(home, ['--backend','observer']);
    const snapshot = path.join(home, 'fixture.json');
    fs.writeFileSync(snapshot, JSON.stringify({observedAt:Date.now()-600000,sources:[],projects:[{id:'p',name:'Project',path:'/remote/project'}],sessions:[{id:'same',source:'Hermes',title:'Remote task',projectId:'p',state:'running',activity:'Running',updatedAt:Date.now()-600000,reference:'remote'}]}));
    fs.writeFileSync(path.join(home,'machines.json'), JSON.stringify([{id:'a',label:'Machine A',snapshot},{id:'b',label:'Machine B',snapshot},{id:'offline',label:'Disconnected',snapshot:path.join(home,'missing.json')}]));
    fs.writeFileSync(path.join(home,'characters.json'),JSON.stringify(['a','b'].map((machine,i)=>({id:`main-${machine}`,name:`Main ${machine}`,machine,source:'Hermes',skin:i?'juniper':'marlow'}))));
    try {
      await fm.start(new ObserverBackend(fm));
      expect(fm.store.data.tasks.map(t=>t.id)).toEqual(['agent-main-a','agent-main-b']);
      expect(fm.store.data.agents.filter(a=>a.role==='lead'&&a.id!=='builder').map(a=>a.id)).toEqual(['main-a','main-b']);
      expect(fm.memory.get('shared/agent-main-a')?.body).toContain('Remote task');
      expect(fm.store.data.tasks.every(t=>t.status==='blocked')).toBe(true);
      expect(fm.store.data.repos.map(r=>r.id)).toEqual(['a-p','b-p']);
      expect(fm.memory.get('shared/machines')?.body).toContain('Disconnected: OFFLINE');
      expect(fm.store.data.backend.observer).toMatchObject({machines:3,online:2,working:0});
    } finally {await fm.close();rmrf(home);}
  });
  it('groups many sessions beneath one permanent Hermes Coordinator character', async () => {
    const home=tempDir(); const {fm}=makeForeman(home,['--backend','observer']);
    const snapshot=path.join(home,'fixture.json');
    const sessions=Array.from({length:20},(_,i)=>({id:String(i),source:'Hermes',runtimeAgent:i%2?'subagent-'+i:'main',title:'Task '+i,projectId:null,state:'idle',activity:'Unknown',updatedAt:Date.now()-i,reference:'fixture'}));
    fs.writeFileSync(snapshot,JSON.stringify({observedAt:Date.now(),sources:[{name:'Hermes',status:'connected',detail:'Fixture'}],projects:[],sessions}));
    fs.writeFileSync(path.join(home,'machines.json'),JSON.stringify([{id:'coordinator',label:'Coordinator Mac',snapshot}]));
    fs.writeFileSync(path.join(home,'characters.json'),JSON.stringify([{id:'coordinator',name:'Coordinator',machine:'coordinator',source:'Hermes',skin:'marlow',chat:true}]));
    try {
      const backend=new ObserverBackend(fm);
      backend.construction.jobs=[{manager:'coordinator',name:'Coordinator',state:'building',placed:10,total:20,percent:50}];
      await fm.start(backend);
      expect(fm.store.data.agents.filter(a=>a.id.startsWith('team-')).every(a=>a.state==='idle'&&a.station==='lounge')).toBe(true);
      expect(fm.store.data.agents.filter(a=>a.role==='lead'&&a.id!=='builder')).toHaveLength(1);
      expect(fm.store.data.agents[0]).toMatchObject({id:'coordinator',name:'Coordinator',title:'Hermes main agent · Coordinator Mac',activity:'Available · 10 team members'});
      expect(fm.store.data.tasks).toHaveLength(1);
      expect(fm.memory.get('shared/agent-coordinator')?.body).toContain('Task 19');
      expect(backend.conversations.targets.get('coordinator')?.source).toBe('Hermes');
      expect(()=>backend.acceptMessage('coordinator','hello')).toThrow('offline or messaging is disabled');
    } finally {await fm.close();rmrf(home);}
  });
  it('pins one local Codex character to its chat and rejects runtime messaging', async () => {
    const home=tempDir(); const {fm}=makeForeman(home,['--backend','observer']);
    const snapshot=path.join(home,'fixture.json');
    const sessions=['current','other'].map(id=>({id,source:'Codex',title:id+' chat',projectId:null,state:'running',activity:'Working '+id,updatedAt:Date.now(),reference:'codex://threads/'+id}));
    fs.writeFileSync(snapshot,JSON.stringify({observedAt:Date.now(),sources:[{name:'Codex',status:'connected',detail:'Fixture'}],projects:[],sessions}));
    fs.writeFileSync(path.join(home,'machines.json'),JSON.stringify([{id:'local',label:'Local Mac',snapshot}]));
    fs.writeFileSync(path.join(home,'characters.json'),JSON.stringify([{id:'codex',name:'Codex',machine:'local',source:'Codex',skin:'kit',chat:true,sessionReference:'codex://threads/current'}]));
    try {
      const backend=new ObserverBackend(fm); await fm.start(backend);
      expect(fm.store.data.agents.filter(a=>a.role==='lead'&&a.id!=='builder')).toHaveLength(1);
      expect(fm.store.data.agents[0]).toMatchObject({id:'codex',state:'running',activity:'Working current'});
      expect(fm.store.data.agents.filter(a=>a.id.startsWith('team-codex-'))).toHaveLength(0);
      expect(fm.memory.get('shared/agent-codex')?.body).toContain('current chat');
      expect(fm.memory.get('shared/agent-codex')?.body).not.toContain('other chat');
      expect(fm.memory.get('shared/sessions')?.body).toContain('other chat');
      expect(backend.conversations.targets.get('codex')?.chat).toBe(true);
      expect(()=>backend.acceptMessage('codex','hello')).toThrow('pinned chat ID');
    } finally {await fm.close();rmrf(home);}
  });
  it('does not permit arbitrary environment overrides in remote collectors', () => {
    expect(()=>collectMachine({id:'x',label:'x',env:{PYTHONPATH:'/untrusted'}},'')).toThrow('Unsupported collector');
  });
});

describe('CrewCraft first-run isolation and configuration',()=>{
 beforeEach(()=>{vi.spyOn(Builder.prototype,'refresh').mockResolvedValue();});
 afterEach(()=>vi.restoreAllMocks());
 it('starts with no configured machines, no runtime roster and no remote targets',async()=>{
  const home=tempDir();const {fm}=makeForeman(home,['--backend','observer']);
  try{
   const backend=new ObserverBackend(fm);await fm.start(backend);
   expect(fm.store.data.backend.observer).toMatchObject({machines:0,online:0,characters:0,sessions:0});
   expect(backend.conversations.targets.size).toBe(0);
   expect(fm.agents().map(a=>a.id)).toEqual(['builder']);
   expect(fm.store.data.backend.coordinator).toBeNull();
   expect(fm.status.message).toContain('setup-crewcraft');
  }finally{await fm.close();rmrf(home);}
 });
 it('routes general chat only to an explicitly configured arbitrary coordinator ID',async()=>{
  const home=tempDir();const {fm}=makeForeman(home,['--backend','observer']);
  const snapshot=path.join(home,'inventory.json');
  fs.writeFileSync(snapshot,JSON.stringify({observedAt:Date.now(),sources:[],projects:[],sessions:[]}));
  fs.writeFileSync(path.join(home,'machines.json'),JSON.stringify([{id:'my-host',label:'Mine',snapshot}]));
  fs.writeFileSync(path.join(home,'characters.json'),JSON.stringify([{id:'atlas',name:'Atlas',source:'Hermes',machine:'my-host',skin:'marlow',coordinator:true}]));
  try{
   const backend=new ObserverBackend(fm);await fm.start(backend);
   expect(fm.store.data.backend.coordinator).toBe('atlas');
   expect(fm.routeUserMessage('all','hello')).toEqual({to:'atlas',text:'hello'});
   fm.store.data.backend.coordinator=null;
   expect(fm.routeUserMessage('all','hello').to).toBe('all');
   expect(backend.conversations.targets.get('atlas')).toMatchObject({coordinator:true,chat:false});
  }finally{await fm.close();rmrf(home);}
 });
});
