import {describe,it,expect,vi} from 'vitest';
import {validatePlan,Builder} from '../src/agents/observer/builder.js';
import {sharedOffice} from '../src/agents/observer/office-plan.js';
describe('Builder world plans',()=>{
 it('furnishes attached team rooms with separate computers and lounge seats for every character',()=>{
  const agents=[...['atlas','nova','sage','pixel'].map(id=>({id,role:'lead'})),...Array.from({length:27},(_,i)=>({id:`team-atlas-${i}`,role:'worker'})),...Array.from({length:8},(_,i)=>({id:`team-nova-${i}`,role:'worker'}))];
  const p=validatePlan(sharedOffice(agents));
  expect(new Set(p.seats.map(s=>s.id)).size).toBe((agents.length+1)*2);
  for(const a of agents){expect(p.fills.some(f=>f.binding===a.id&&f.block.includes('monitor'))).toBe(true);expect(p.seats.some(s=>s.id===`lounge-${a.id}`)).toBe(true);}
  expect(p.fills.some(f=>f.block.includes('waxed_cut_copper'))).toBe(true);
  expect(p.fills.filter(f=>f.binding?.startsWith('label:'))).toHaveLength(5);
 });
 it('rejects out-of-bounds, fractional and oversized operations before submission',()=>{
  const p={title:'test',seats:[],fills:[{from:[0,66,60],to:[1,67,61],block:'minecraft:glass',binding:null}]};
  expect(()=>validatePlan(p)).not.toThrow();
  for(const from of [[0,0,0],[0.5,66,60],[2,66,60]])expect(()=>validatePlan({...p,fills:[{...p.fills[0],from}]})).toThrow();
  expect(()=>validatePlan({...p,fills:Array.from({length:3},()=>({from:[-56,64,58],to:[52,84,118],block:'minecraft:glass',binding:null}))})).toThrow('too large');
 });
 it('deduplicates builder requests across restarts without resubmission',()=>{
  const fm:any={store:{data:{backend:{builderMessages:[{id:'a',text:'office',state:'planning'}]}}}};
  const b=new Builder(fm);
  expect(b.accept('builder','office','a')).toEqual({builder:'a',state:'interrupted'});
  expect(()=>b.accept('builder','different','a')).toThrow('reused');
  expect(b.accept('coordinator','office')).toBeNull();
 });
});

describe('Builder site awareness',()=>{
 it('replaces obsolete player-centered instructions with autonomous full-site inspection',async()=>{
  const {originalHouseContext}=await import('../src/agents/observer/builder.js');
  const context=originalHouseContext({originalHouse:{hallFootprintXZ:[-24,-10,24,6]},complete:true});
  expect(context).toContain('ENTIRE Headquarters site');
  expect(context).toContain('Never ask the player to move');
  expect(context).toContain('Earlier dialogue claiming only nearby vision is obsolete');
  expect(context).toContain('Outside the surveyed bounds is unknown');
 });
});

describe('Raised construction ceiling',()=>{
 it('allows fills and seats through Y=192 and rejects Y=193',()=>{
  const plan={title:'Tall room',fills:[{from:[0,97,0],to:[0,192,0],block:'minecraft:stone_bricks',binding:null}],seats:[{id:'builder',x:1,y:192,z:1,yaw:0}]};
  expect(()=>validatePlan(plan)).not.toThrow();
  expect(()=>validatePlan({...plan,fills:[{...plan.fills[0],to:[0,193,0]}]})).toThrow('outside');
  expect(()=>validatePlan({...plan,seats:[{...plan.seats[0],y:193}]})).toThrow('outside');
 });
});
