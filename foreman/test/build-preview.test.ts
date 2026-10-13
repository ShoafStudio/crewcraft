import {describe,it,expect} from 'vitest';
import {previewGeometry,planHash} from '../src/agents/observer/build-preview.js';
const fill=(from:number[],to:number[],block='minecraft:quartz_block')=>({from,to,block,binding:null});
describe('Exact plan preview',()=>{
 it('merges exposed faces without changing solid volume',()=>{
  const p={title:'Cube',fills:[fill([0,65,60],[2,67,62])],seats:[]};
  const mesh=previewGeometry(p);expect(mesh.solidBlocks).toBe(27);expect(mesh.faces).toHaveLength(6);expect(mesh.size).toEqual([3,3,3]);
 });
 it('honors later air cuts and invalidates approval when geometry changes',()=>{
  const a={title:'Door',fills:[fill([0,65,60],[2,67,60])],seats:[]},b={...a,fills:[...a.fills,fill([1,65,60],[1,66,60],'minecraft:air')]};
  expect(previewGeometry(b).solidBlocks).toBe(7);expect(planHash(a)).not.toBe(planHash(b));
 });
});
