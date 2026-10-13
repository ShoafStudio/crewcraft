import {describe,it,expect,afterEach,vi} from 'vitest';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {getConcept,saveConcept,conceptDir,conceptImages,copyGeneratedImage,makeConcepts,chooseConcept} from '../src/agents/observer/builder-concepts.js';
vi.mock('node:child_process',()=>({execFile:(_cli:string,args:string[],_options:any,callback:Function)=>{
 const job=args[args.indexOf('--cd')+1]!,out=args[args.indexOf('--output-last-message')+1]!;
 const image=path.join(job,'generated.png');fs.writeFileSync(image,Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j5V8AAAAASUVORK5CYII=','base64'));
 fs.writeFileSync(out,JSON.stringify({images:[image],error:''}));queueMicrotask(()=>callback(null));
 return {stdin:{on:()=>{},end:()=>{}}};
}}));
const homes:string[]=[];
function home(){const p=fs.mkdtempSync(path.join(os.tmpdir(),'hq-concept-'));homes.push(p);return p;}
afterEach(()=>{for(const h of homes.splice(0))fs.rmSync(h,{recursive:true,force:true});});
describe('Persistent image-first concepts',()=>{
 it('keeps the selected panel and views across reload and attaches views first',()=>{
  const h=home(),dir=conceptDir(h);fs.mkdirSync(dir,{recursive:true});for(const n of ['sheet.png','views.png'])fs.writeFileSync(path.join(dir,n),'test');
  saveConcept(h,{id:'one',brief:'sculptural tower',sheet:'sheet.png',selected:null,views:null,createdAt:1});expect(conceptImages(h)).toEqual([]);
  saveConcept(h,{...getConcept(h)!,selected:'A',views:'views.png'});
  expect(getConcept(h)?.selected).toBe('A');expect(conceptImages(h)).toEqual([path.join(dir,'views.png'),path.join(dir,'sheet.png')]);
 });
 it('rejects model paths outside the image output roots and stale/non-image output',()=>{
  const h=home(),root=path.join(h,'generated');fs.mkdirSync(root);const outside=path.join(h,'secret.png'),inside=path.join(root,'image.png');fs.writeFileSync(outside,'secret');fs.writeFileSync(inside,'not an image');
  expect(()=>copyGeneratedImage(outside,path.join(h,'copy.png'),[root],Date.now())).toThrow('invalid');
  expect(()=>copyGeneratedImage(inside,path.join(h,'copy.png'),[root],Date.now())).toThrow('PNG');
  fs.utimesSync(inside,1,1);expect(()=>copyGeneratedImage(inside,path.join(h,'copy.png'),[root],Date.now())).toThrow('stale');
 });
});

it('generates and selects through the runtime adapter, retaining matching views',async()=>{
 const h=home();const first=await makeConcepts(h,'codex','Sculptural tower');
 expect(fs.existsSync(path.join(conceptDir(h),first.sheet))).toBe(true);expect(first.selected).toBeNull();
 const chosen=await chooseConcept(h,'codex','A');expect(chosen.selected).toBe('A');expect(chosen.views).not.toBe(first.sheet);expect(conceptImages(h)).toHaveLength(2);
});
