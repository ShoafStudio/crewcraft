import WebSocket from 'ws';
import {randomUUID} from 'node:crypto';
import {ClientError,type Foreman} from '../../foreman.js';
export interface BuildJob {manager:string;name:string;state:string;placed:number;total:number;percent:number}
export function buildRequest(action:string,manager:string):Promise<{jobs:BuildJob[];plan?:string}> {
  return new Promise((resolve,reject)=>{
    const ws=new WebSocket('ws://127.0.0.1:7879',{handshakeTimeout:3000});let done=false;
    const end=(e?:Error,v?:any)=>{if(done)return;done=true;clearTimeout(timer);ws.close();e?reject(e):resolve(v);};
    const timer=setTimeout(()=>end(new Error('Minecraft did not confirm the construction command. Check /build status before retrying.')),30000);
    ws.on('error',e=>end(e));ws.on('close',()=>end(new Error('Minecraft disconnected.')));
    ws.on('message',data=>{try{const r=JSON.parse(String(data));if(r.type==='dev.hello')ws.send(JSON.stringify({id:'build',type:'dev.build',action,manager}));else if(r.id==='build')r.ok?end(undefined,r):end(new Error(typeof r.error==='string'?r.error:JSON.stringify(r.error)));}catch(e){end(e as Error);}});
  });
}
export function parseBuild(text:string,defaultManager:string,resolve:(id:string)=>string|undefined):{action:string;manager:string}|null {
  if(!text.startsWith('/build'))return null;
  const parts=text.trim().split(/\s+/);if(parts[0]!=='/build')return null;
  if(parts.length>3)throw new ClientError('Use /build office|visit|preview|status|pause|resume|cancel|undo [manager|all]');
  let action=parts[1]??'preview';if(action==='office')action='start';
  if(!['visit','preview','start','status','pause','resume','cancel','undo'].includes(action))throw new ClientError('Use /build office|visit|preview|status|pause|resume|cancel|undo [manager|all]');
  const input=(parts[2]??defaultManager).replace(/^@/,'');const manager=input==='all'?'all':resolve(input);
  if(!manager||!['all','workshop','study','library','studio'].includes(manager))throw new ClientError('Choose Workshop, Study, Library, Studio, or all.');
  return {action,manager};
}
export class Construction {
  jobs:BuildJob[]=[];
  private busy=false;
  constructor(private fm:Foreman){}
  accept(to:string,text:string,requestId?:string):Record<string,unknown>|null {
    const command=parseBuild(text,to,id=>this.fm.resolveAgentId(id));if(!command)return null;
    const records=(this.fm.store.data.backend.buildRequests??=[]) as Array<{id:string;text:string;to:string}>;
    if(requestId&&records.some(r=>r.id===requestId)){const old=records.find(r=>r.id===requestId)!;if(old.text!==text||old.to!==to)throw new ClientError('Build request ID was reused for different content.');return{duplicate:true};}
    if(this.busy)throw new ClientError('Wait for the previous construction command to be acknowledged.');
    const id=requestId??randomUUID();records.push({id,text,to});this.fm.store.markDirty();this.fm.store.flush();this.busy=true;
    void buildRequest(command.action,command.manager).then(r=>{this.jobs=r.jobs;this.report(to,r.plan??r.jobs.map(j=>`${j.name}: ${j.state} · ${j.percent}% (${j.placed}/${j.total} blocks)`).join('\n'));}).catch(e=>this.report(to,`Construction: ${e.message}`)).finally(()=>{this.busy=false;});
    return {construction:id,state:'submitted'};
  }
  private report(to:string,text:string):void {const agent=to==='all'?'studio':to;this.fm.agentLog(agent,'text',text);this.fm.bus.send(agent,'user',text);}
  async refresh():Promise<void>{try{this.jobs=(await buildRequest('status','all')).jobs;}catch{this.jobs=[];}}
}
