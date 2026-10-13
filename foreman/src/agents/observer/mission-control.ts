import fs from 'node:fs';
import path from 'node:path';
import {execFile} from 'node:child_process';
import {z} from 'zod';
import {pullRequestLink} from './pull-requests.js';
import type {Foreman} from '../../foreman.js';
import type {Task} from '../../protocol.js';
const Config=z.object({baseUrl:z.string().url().refine(s=>{const u=new URL(s);return u.protocol==='https:'&&!u.username&&!u.password&&u.pathname==='/';}),machine:z.string(),envFile:z.string(),humans:z.array(z.string()).default([])});
const Row=z.object({_id:z.string(),title:z.string(),description:z.string().default(''),status:z.enum(['inbox','in_progress','review','done','blocked','archived']),priority:z.enum(['P0','P1','P2']),assignee:z.string(),projectId:z.string().optional(),projectName:z.string().optional(),createdAt:z.number(),updatedAt:z.number()});
export function boardTasks(input:unknown,agents:Array<{id:string;name:string}>,humans:string[]):Task[]{
 const rows=z.array(Row).parse(input);
 const names=new Map(agents.map(a=>[a.name.toLowerCase(),a.id]));
 const statuses={inbox:'todo',in_progress:'doing',review:'review',done:'done',blocked:'blocked',archived:'cancelled'} as const;
 return rows.filter(r=>r.status!=='archived').map(r=>{
  const human=humans.some(n=>n.toLowerCase()===r.assignee.toLowerCase());
  const attention=!['done','archived'].includes(r.status)&&(human||['review','blocked'].includes(r.status));
  const pr=pullRequestLink(r.description);
  return {...(pr?{repoId:'mission-control-pr',worktree:`mc-${r._id}`} : {}),id:`mc-${r._id}`,title:r.title,description:`${r.priority} · ${r.status} · Assigned to ${r.assignee}${r.projectName?' · '+r.projectName:''}\n\n${r.description}`,status:statuses[r.status],assignee:names.get(r.assignee.toLowerCase())??(human?'user':undefined),deps:[],priority:3-Number(r.priority.slice(1)),ci:'unknown',createdBy:'mission-control',createdAt:r.createdAt,updatedAt:r.updatedAt,summary:attention?'Needs attention':undefined};
 });
}
export class MissionControl {
 private last=0;
 private cached:Task[]=[];
 constructor(private fm:Foreman){}
 async refresh():Promise<Task[]>{
  const file=path.join(this.fm.config.home,'mission-control.json');
  if(!fs.existsSync(file))return [];
  if(Date.now()-this.last<60000)return this.cached;
  this.last=Date.now();
  try{
   const config=Config.parse(JSON.parse(fs.readFileSync(file,'utf8')));
   const machines=JSON.parse(fs.readFileSync(path.join(this.fm.config.home,'machines.json'),'utf8'));
   const host=machines.find((m:any)=>m.id===config.machine);
   if(!host||host.snapshot||host.ssh&&!/^[a-zA-Z0-9_][a-zA-Z0-9_.@:-]*$/.test(host.ssh))throw new Error('Invalid board host');
   const script=`import json\nCONFIG=json.loads(${JSON.stringify(JSON.stringify(config))})\n`+fs.readFileSync(path.join(this.fm.config.projectRoot,'tools/mission-control.py'),'utf8');
   const data:any=await new Promise((resolve,reject)=>{
    const child=execFile(host.ssh?'ssh':'python3',host.ssh?['-T','-p',String(host.port??22),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=5',host.ssh,'python3','-']:['-'],{timeout:45000,maxBuffer:4*1024*1024},(e,out)=>{if(e)reject(new Error('Board host unavailable'));else try{resolve(JSON.parse(out));}catch{reject(new Error('Invalid board response'));}});
    child.stdin?.on('error',()=>{});child.stdin?.end(script);
   });
   if(!data.ok)throw new Error(data.error);
   this.cached=boardTasks(data.tasks,this.fm.agents(),config.humans);
   this.fm.store.data.backend.missionControl={state:'connected',at:Date.now(),tasks:this.cached.length,attention:this.cached.filter(t=>t.summary).length,coordinator:this.fm.store.data.backend.coordinator??null};
   this.fm.memory.write({scope:'shared',slug:'attention',title:'Attention desk · Mission Control',body:`Live configured workspace board. ${this.cached.filter(t=>t.summary).length} items need attention. Open /attention or the Decision Podium. The configured coordinator handles general requests. Builder handles Minecraft design and construction only. Last sync: ${new Date().toISOString()}.`,author:'observer'});
  }catch{
   this.fm.store.data.backend.missionControl={state:'unavailable',at:Date.now(),coordinator:this.fm.store.data.backend.coordinator??null};
   // Never present cached work as current when the source is inaccessible.
   this.cached=[];
   this.fm.memory.write({scope:'shared',slug:'attention',title:'Attention desk · unavailable',body:'Mission Control is unavailable. This is not an empty inbox. Check the configured board host and workspace API key. No tasks have been changed.',author:'observer'});
  }
  return this.cached;
 }
}
