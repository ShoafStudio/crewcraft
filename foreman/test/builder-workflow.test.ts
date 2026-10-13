import {describe,it,expect,vi,beforeEach,afterEach} from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
const homes:string[]=[];
afterEach(()=>{for(const home of homes.splice(0))fs.rmSync(home,{recursive:true,force:true});});
vi.mock('node:child_process',()=>({execFile:(_file:any,_args:any,callback:any)=>{callback(null);}}));
import {EventEmitter} from 'node:events';
const calls=vi.hoisted(()=>({actions:[] as any[],fail:false}));
vi.mock('ws',()=>({default:class extends EventEmitter{
 constructor(){super();queueMicrotask(()=>this.emit('message',JSON.stringify({type:'dev.hello'})));}
 send(raw:string){const data=JSON.parse(raw);calls.actions.push(data);queueMicrotask(()=>this.emit('message',JSON.stringify(calls.fail&&data.action==='submit'?{id:'builder',ok:false,error:'conflicting player blocks'}:{id:'builder',ok:true,state:data.action==='submit'?'building':'idle',title:'Test',percent:0})));}
 close(){}
}}));
import {Builder} from '../src/agents/observer/builder.js';
const plan={title:'Desk',fills:[{from:[0,66,60],to:[1,66,60],block:'minecraft:oak_planks',binding:null}],seats:[]};
const answer={reply:'Proposed desk.',action:'plan',plan,brief:'Warm wood, connected rooms.',sources:[]};
const fm=()=>({config:{home:(()=>{const home=fs.mkdtempSync(path.join(os.tmpdir(),'hq-builder-'));homes.push(home);return home;})()},store:{data:{backend:{} as any},markDirty:vi.fn(),flush:vi.fn()},agents:()=>[{id:'builder'}],setAgent:vi.fn(),agentLog:vi.fn(),bus:{send:vi.fn()}} as any);
async function idle(b:Builder){await vi.waitFor(()=>expect(b.busy).toBe(false));}
beforeEach(()=>{calls.actions=[];calls.fail=false;});
describe('Builder proposal execution',()=>{
 it('research/design never changes blocks, stores preferences, and builds the exact saved proposal across restart',async()=>{
  const f=fm(),b=new Builder(f);vi.spyOn(b as any,'plan').mockResolvedValue(answer);
  b.accept('builder','design a desk','design');await idle(b);
  expect(calls.actions.map(x=>x.action)).toEqual(['status','validate']);expect(f.store.data.backend.builderBrief).toBe(answer.brief);
  expect(f.store.data.backend.builderProposal.plan).toEqual(plan);
  const next=new Builder(f);next.accept('builder','preview','view');await idle(next);next.accept('builder','build it','build');await idle(next);
  expect(calls.actions.filter(x=>x.action==='submit')).toHaveLength(1);expect(calls.actions.at(-1).plan).toEqual(plan);
  next.accept('builder','build it','build');expect(calls.actions.filter(x=>x.action==='submit')).toHaveLength(1);
  expect(f.store.data.backend.builderProposal).toBeUndefined();
 });
 it('does not regenerate or automatically retry a failed world submission',async()=>{
  const f=fm();f.store.data.backend.builderProposal={plan};const b=new Builder(f);calls.fail=true;
  b.accept('builder','build it','one');await idle(b);
  expect(f.store.data.backend.builderMessages[0].state).toBe('failed');expect(f.store.data.backend.builderProposal).toBeUndefined();
  b.accept('builder','build it','two');await idle(b);expect(calls.actions.filter(x=>x.action==='submit')).toHaveLength(1);
 });
});

it('requires viewing the exact proposal before building, without consuming it on refusal',async()=>{
 const f=fm(),b=new Builder(f);vi.spyOn(b as any,'plan').mockResolvedValue(answer);
 b.accept('builder','design a desk','new-design');await idle(b);
 b.accept('builder','build it','too-soon');await idle(b);
 expect(calls.actions.filter(x=>x.action==='submit')).toHaveLength(0);
 expect(f.store.data.backend.builderProposal.plan).toEqual(plan);
 b.accept('builder','preview','inspect');await idle(b);
 f.store.data.backend.builderProposal.plan={...plan,title:'Changed draft'};
 b.accept('builder','build it','stale-view');await idle(b);
 expect(calls.actions.filter(x=>x.action==='submit')).toHaveLength(0);
});
