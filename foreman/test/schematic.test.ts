import {describe,it,expect} from 'vitest';
import {writeUncompressed} from 'prismarine-nbt';
import {gzipSync} from 'node:zlib';
import {decodeSchematic} from '../src/agents/observer/schematic.js';
function fixture(version:number,change:any={}){
 const s:any={Version:{type:'int',value:version},DataVersion:{type:'int',value:3465},Width:{type:'short',value:2},Height:{type:'short',value:2},Length:{type:'short',value:2},Offset:{type:'intArray',value:[100,20,-40]}};
 const palette={type:'compound',value:{'minecraft:air':{type:'int',value:1},'minecraft:quartz_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]':{type:'int',value:128}}};
 const data={type:'byteArray',value:[-128,1,1,1,1,1,1,1,-128,1]};
 if(version===2){s.Palette=palette;s.BlockData=data;}else{s.Blocks={type:'compound',value:{Palette:palette,Data:data}};}
 Object.assign(s,change);
 return gzipSync(writeUncompressed({type:'compound',name:version===2?'Schematic':'',value:version===2?s:{Schematic:{type:'compound',value:s}}}));
}
describe('Sponge architectural import',()=>{
 for(const version of [2,3])it(`decodes v${version}, multibyte palette IDs, orientation, air and x/z/y order`,()=>{
  const {plan,details}=decodeSchematic(fixture(version),[0,65,60]);
  const blocks=new Map<string,string>();for(const f of plan.fills)for(let x=f.from[0]!;x<=f.to[0]!;x++)blocks.set([x,f.from[1],f.from[2]].join(','),f.block);
  expect(blocks.size).toBe(8);expect(blocks.get('0,65,60')).toContain('facing=east');expect(blocks.get('1,66,61')).toContain('stairs');expect(blocks.get('1,65,60')).toBe('minecraft:air');
  expect(details).toContain('Offset intentionally not applied');
 });
 it('rejects clipping, oversized selections and unsupported versions',()=>{
  expect(()=>decodeSchematic(fixture(2),[52,65,60])).toThrow('do not fit');
  expect(()=>decodeSchematic(fixture(2,{Width:{type:'short',value:1000}}),[0,65,60])).toThrow();
  expect(()=>decodeSchematic(fixture(1),[0,65,60])).toThrow('version 2 or 3');
 });
 it('does not silently discard entities or truncated data',()=>{
  expect(()=>decodeSchematic(fixture(2,{Entities:{type:'list',value:{type:'compound',value:[{}]}}}),[0,65,60])).toThrow('not silently discarded');
  expect(()=>decodeSchematic(fixture(2,{BlockData:{type:'byteArray',value:[-128]}}),[0,65,60])).toThrow('Truncated');
 });
});
