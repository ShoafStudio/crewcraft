import {describe,it,expect} from 'vitest';
import {runtimeActivity,workstation} from '../src/agents/observer/activity.js';
describe('resting and working agents',()=>{
 it('sends only active work states to computers',()=>{
  for(const state of ['thinking','running','reading','editing','testing'] as const)expect(workstation(state)).toBe('desk');
  for(const state of ['idle','done','blocked','error','waiting_user'] as const)expect(workstation(state)).toBe('lounge');
 });
 it('expires stale activity and lets a newer reply end an old running session',()=>{
  expect(runtimeActivity([{state:'running',updatedAt:1000,activity:'Running'}],[],200000).state).toBe('idle');
  expect(runtimeActivity([{state:'running',updatedAt:1000,activity:'Running'}],[{at:2000,text:'Done.'}],3000).state).toBe('idle');
  expect(runtimeActivity([{state:'running',updatedAt:1000,activity:'Running'}],[],3000).state).toBe('running');
 });
 it('uses fresh tool evidence for runtimes without an execution-state API',()=>{
  expect(runtimeActivity([],[{at:1000,text:'Tools: terminal'}],2000).state).toBe('running');
  expect(runtimeActivity([],[{at:1000,text:'Tools: terminal'}],50000).state).toBe('idle');
 });
});
