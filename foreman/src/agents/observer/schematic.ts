import fs from 'node:fs';
import path from 'node:path';
import {gunzipSync} from 'node:zlib';
import {parseUncompressed,simplify} from 'prismarine-nbt';
import type {WorldPlan} from './office-plan.js';

export function schematicFiles(home:string):string[]{
  const dir=path.join(home,'builder','imports');fs.mkdirSync(dir,{recursive:true,mode:0o700});
  return fs.readdirSync(dir).filter(n=>/^[\w .()-]+\.schem$/i.test(n)&&fs.lstatSync(path.join(dir,n)).isFile()).sort();
}
export function loadSchematic(home:string,name:string,origin:[number,number,number]){
  if(!schematicFiles(home).includes(name))throw new Error('Schematic not found in Builder imports. Use “schematics” to list files.');
  const file=path.join(home,'builder','imports',name),stat=fs.statSync(file);
  if(stat.size>16*1024*1024)throw new Error('Schematic exceeds 16 MiB compressed limit');
  return decodeSchematic(fs.readFileSync(file),origin,name);
}
export function decodeSchematic(input:Buffer,origin:[number,number,number],filename='Imported design'):{plan:WorldPlan;details:string}{
  if(input.length>16*1024*1024)throw new Error('Schematic file too large');
  const raw=input[0]===31&&input[1]===139?gunzipSync(input,{maxOutputLength:32*1024*1024}):input;
  const root=simplify(parseUncompressed(raw,'big')),s=root.Schematic??root;
  if(![2,3].includes(s.Version))throw new Error('Use Sponge .schem version 2 or 3; legacy .schematic and .litematic are not supported.');
  const dims=[s.Width,s.Height,s.Length].map(n=>Number(n)&65535);
  const [w,h,l]=dims as [number,number,number],volume=w*h*l;
  if(!w||!h||!l||volume>350000)throw new Error('Schematic selection must contain 1–350,000 cells; export a tighter selection or split the build.');
  for(let i=0;i<3;i++)if(!Number.isInteger(origin[i])||origin[i]!<[-56,64,-36][i]!||origin[i]!+dims[i]!-1>[52,192,118][i]!)throw new Error('Schematic dimensions do not fit the Headquarters construction bounds at this origin. No resizing or clipping was applied.');
  const blocks=s.Version===3?s.Blocks:s;
  if(!blocks?.Palette)throw new Error('Missing block palette');
  if((s.Entities?.length??0)||(blocks.BlockEntities?.length??0))throw new Error('This schematic includes entities or block-entity contents. Export a blocks-only copy first; these are not silently discarded.');
  const palette=new Map<number,string>();
  for(const [state,id] of Object.entries(blocks.Palette)){
    if(!Number.isInteger(id)||Number(id)<0||Number(id)>2147483647||palette.has(Number(id))||!/^\w+:[a-z0-9_]+(?:\[[a-z0-9_=,]+\])?$/.test(state))throw new Error('Invalid schematic palette');
    palette.set(Number(id),state);
  }
  const encoded=s.Version===3?blocks.Data:blocks.BlockData;
  if(!Array.isArray(encoded)&&!Buffer.isBuffer(encoded))throw new Error('Missing or invalid block byte array');
  if(encoded.length>volume*5||!encoded.every((n:number)=>Number.isInteger(n)&&n>=-128&&n<=255))throw new Error('Invalid block byte array');
  const data=Buffer.from(encoded);
  const cells=new Uint32Array(volume);let pos=0;
  for(let i=0;i<volume;i++){
    let id=0,shift=0,byte=0;
    do{if(pos>=data.length||shift>=35)throw new Error('Truncated or invalid block data');byte=data[pos++]!;if(shift===28&&(byte&0xf0))throw new Error('Palette ID overflow');id+=(byte&127)*2**shift;shift+=7;}while(byte&128);
    if(!palette.has(id))throw new Error('Unknown block palette index');cells[i]=id;
  }
  if(pos!==data.length)throw new Error('Unexpected trailing block data');
  const fills:WorldPlan['fills']=[];
  // Exact X runs: preserve orientation/properties, include air to preserve openings and interiors.
  for(let y=0;y<h;y++)for(let z=0;z<l;z++)for(let x=0;x<w;x++){
    const id=cells[(y*l+z)*w+x]!,state=palette.get(id)!;let end=x;
    while(end+1<w&&cells[(y*l+z)*w+end+1]===id)end++;
    fills.push({from:[origin[0]+x,origin[1]+y,origin[2]+z],to:[origin[0]+end,origin[1]+y,origin[2]+z],block:['minecraft:cave_air','minecraft:void_air'].includes(state)?'minecraft:air':state,binding:null});x=end;
    if(fills.length>50000)throw new Error('Schematic exceeds 50,000 block runs; split the build into sections.');
  }
  const title=String(s.Metadata?.Name??filename).slice(0,160);
  return {plan:{title:`Imported: ${title}`.slice(0,180),fills,seats:[]},details:`${title} · ${w} × ${h} × ${l} blocks · origin ${origin.join(', ')} (minimum corner; file Offset intentionally not applied) · ${palette.size} exact block states. Author: ${String(s.Metadata?.Author??'not specified').slice(0,150)}. No scaling or material substitution. Agent computers and lounge positions are a separate furnishing step.`};
}
