import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {pathToFileURL} from 'node:url';
import {chromium} from 'playwright-core';
import type {WorldPlan} from './office-plan.js';

export function planHash(plan:WorldPlan):string{return createHash('sha256').update(JSON.stringify(plan)).digest('hex');}
export function previewGeometry(plan:WorldPlan){
  if(!plan.fills.length)throw new Error('Empty plan');
  const min=[0,1,2].map(d=>Math.min(...plan.fills.map(f=>f.from[d]!))),max=[0,1,2].map(d=>Math.max(...plan.fills.map(f=>f.to[d]!)));
  const size=max.map((n,d)=>n-min[d]!+1),[nx,ny,nz]=size as [number,number,number];
  if(nx*ny*nz>2400000)throw new Error('Preview bounds too large');
  const grid=new Uint16Array(nx*ny*nz),palette=['air'],ids=new Map<string,number>();
  for(const f of plan.fills){
    let id=0;
    if(!/^minecraft:(air|cave_air|void_air)$/.test(f.block)){
      if(!ids.has(f.block)){ids.set(f.block,palette.length);palette.push(f.block);}
      id=ids.get(f.block)!;
    }
    for(let y=f.from[1]!;y<=f.to[1]!;y++)for(let z=f.from[2]!;z<=f.to[2]!;z++)for(let x=f.from[0]!;x<=f.to[0]!;x++)grid[((y-min[1]!)*nz+z-min[2]!)*nx+x-min[0]!]=id;
  }
  const at=(p:number[])=>p.some((n,d)=>n<0||n>=size[d]!)?0:grid[(p[1]!*nz+p[2]!)*nx+p[0]!]!;
  const faces:number[][]=[];
  for(let axis=0;axis<3;axis++){
    const u=(axis+1)%3,v=(axis+2)%3,nu=size[u]!,nv=size[v]!;
    for(let k=0;k<=size[axis]!;k++)for(const side of [-1,1]){
      const mask=new Uint16Array(nu*nv);
      for(let j=0;j<nv;j++)for(let i=0;i<nu;i++){
        const p=[0,0,0];p[axis]=k-(side===1?1:0);p[u]=i;p[v]=j;const id=at(p);p[axis]!+=side;
        if(id&&!at(p))mask[j*nu+i]=id;
      }
      for(let j=0;j<nv;j++)for(let i=0;i<nu;i++){
        const id=mask[j*nu+i]!;if(!id)continue;
        let w=1,h=1;while(i+w<nu&&mask[j*nu+i+w]===id)w++;
        rows:while(j+h<nv){for(let x=0;x<w;x++)if(mask[(j+h)*nu+i+x]!==id)break rows;h++;}
        faces.push([axis,side,k,i,j,i+w,j+h,id]);
        for(let y=j;y<j+h;y++)mask.fill(0,y*nu+i,y*nu+i+w);
      }
    }
  }
  return {min,size,palette,faces,solidBlocks:grid.reduce((n,id)=>n+(id?1:0),0)};
}
export function writePreview(plan:WorldPlan,dir:string):{file:string;hash:string}{
  fs.mkdirSync(dir,{recursive:true,mode:0o700});const hash=planHash(plan),file=path.join(dir,hash+'.html');
  const data=JSON.stringify({title:plan.title,...previewGeometry(plan)}).replace(/</g,'\\u003c');
  const html=`<!doctype html><meta charset="utf-8"><title>Headquarters build preview</title>
<style>body{margin:0;background:#101820;color:#e7edf1;font:15px system-ui}header{position:absolute;top:24px;left:28px;max-width:850px}h1{font-size:23px;margin:0 0 8px}p{color:#aabcc9;margin:7px 0}canvas{display:block;width:100vw;height:100vh;touch-action:none}footer{position:absolute;bottom:24px;left:28px;display:flex;gap:12px;align-items:center}button{background:#24333f;color:#fff;border:1px solid #506570;border-radius:5px;padding:9px;cursor:pointer}label{color:#afc0cc}.note{max-width:800px;font-size:13px}</style>
<canvas id="view"></canvas><header><h1 id="title"></h1><p id="dimensions"></p><p class="note">Saved block-plan preview · drag to rotate · scroll to zoom. Colors approximate materials; stairs, slabs and other shaped blocks are shown as full voxels. Existing world surroundings are not shown.</p></header>
<footer><button id="front">Front</button><button id="back">Back</button><button id="top">Above</button><label>Cutaway height <input id="cut" type="range"></label><span id="height"></span></footer>
<script>const data=${data};
const canvas=document.getElementById('view'),ctx=canvas.getContext('2d');let yaw=-.65,pitch=.42,zoom=1,drag=null;
document.getElementById('title').textContent=data.title;document.getElementById('dimensions').textContent=data.size.join(' × ')+' blocks · '+data.solidBlocks.toLocaleString()+' solid blocks · minimum corner '+data.min.join(', ');
const cut=document.getElementById('cut');cut.min=1;cut.max=data.size[1];cut.value=data.size[1];
const colors=data.palette.map(s=>{for(const [key,color] of [['black','#353a42'],['white','#e1e2db'],['quartz','#e5dfd2'],['glass','#6d9dad'],['copper','#b58363'],['dark_oak','#5f4532'],['walnut','#624c3b'],['oak','#a78a57'],['plaster','#d7ccb3'],['concrete','#899098'],['light_gray','#b7bbbe'],['gray','#737c88'],['blue','#345682'],['purple','#786a9b'],['green','#668767'],['leaf','#608255'],['leaves','#608255'],['glow','#ffe9ab'],['lantern','#ffe9ab'],['brick','#978473'],['stone','#96999b']])if(s.includes(key))return color;return '#a5a9a5'});
function draw(){canvas.width=innerWidth;canvas.height=innerHeight;ctx.fillStyle='#101820';ctx.fillRect(0,0,canvas.width,canvas.height);
 const cy=Math.cos(yaw),sy=Math.sin(yaw),cp=Math.cos(pitch),sp=Math.sin(pitch),scale=Math.min((canvas.width-90)/(Math.abs(cy)*data.size[0]+Math.abs(sy)*data.size[2]),(canvas.height-250)/(Math.abs(cp)*data.size[1]+Math.abs(sp)*(Math.abs(sy)*data.size[0]+Math.abs(cy)*data.size[2])))*zoom;
 const project=p=>{const x=p[0]-data.size[0]/2,y=p[1]-data.size[1]/2,z=p[2]-data.size[2]/2;return [canvas.width/2+(cy*x-sy*z)*scale,canvas.height*.56-(cp*y-sp*(sy*x+cy*z))*scale,cp*(sy*x+cy*z)+sp*y]};
 const polys=[];for(const f of data.faces){const [a,side,k,u0,v0,u1,v1,id]=f,u=(a+1)%3,v=(a+2)%3;
  const normal=[sy*cp,sp,cy*cp][a]*side;if(normal<=0)continue;
  const vertices=[[u0,v0],[u1,v0],[u1,v1],[u0,v1]].map(q=>{const p=[0,0,0];p[a]=k;p[u]=q[0];p[v]=q[1];return p});
  if(vertices.every(p=>p[1]>Number(cut.value)))continue;for(const p of vertices)p[1]=Math.min(p[1],Number(cut.value));
  const points=vertices.map(project);polys.push({points,id,shade:a===1?1:a===0?.76:.9,depth:points.reduce((s,p)=>s+p[2],0)/4});}
 polys.sort((a,b)=>a.depth-b.depth);for(const p of polys){ctx.beginPath();p.points.forEach((q,i)=>i?ctx.lineTo(q[0],q[1]):ctx.moveTo(q[0],q[1]));ctx.closePath();const hex=colors[p.id].slice(1);ctx.fillStyle='rgb('+[0,2,4].map(i=>Math.round(parseInt(hex.slice(i,i+2),16)*p.shade)).join(',')+')';ctx.fill();ctx.strokeStyle='rgba(10,20,30,.13)';ctx.lineWidth=.5;ctx.stroke();}
 document.getElementById('height').textContent='Y '+(data.min[1]+Number(cut.value)-1);window.previewReady=true;}
canvas.onpointerdown=e=>{drag=[e.clientX,e.clientY];canvas.setPointerCapture(e.pointerId)};canvas.onpointerup=()=>drag=null;canvas.onpointermove=e=>{if(!drag)return;yaw+=(e.clientX-drag[0])*.009;pitch=Math.max(-1.4,Math.min(1.5,pitch+(e.clientY-drag[1])*.009));drag=[e.clientX,e.clientY];draw()};canvas.onwheel=e=>{e.preventDefault();zoom=Math.max(.2,Math.min(5,zoom*Math.exp(-e.deltaY*.001)));draw()};onresize=draw;cut.oninput=draw;document.getElementById('front').onclick=()=>{yaw=0;pitch=.2;draw()};document.getElementById('back').onclick=()=>{yaw=Math.PI;pitch=.2;draw()};document.getElementById('top').onclick=()=>{pitch=1.4;draw()};draw();</script>`;
  fs.writeFileSync(file,html,{mode:0o600});return {file,hash};
}
export async function previewImages(file:string):Promise<string[]>{
  const browser=await chromium.launch({channel:'chrome',headless:true,timeout:15000});
  try{const page=await browser.newPage({viewport:{width:1400,height:1050}});await page.goto(pathToFileURL(file).href);await page.waitForFunction('window.previewReady');
    const images=[file+'.png',file+'-back.png'];await page.screenshot({path:images[0]});await page.locator('#back').click();await page.screenshot({path:images[1]});return images;
  }finally{await browser.close();}
}
