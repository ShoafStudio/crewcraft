import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { CodexDesktop } from './codex-desktop.js';
import { ClientError, type Foreman } from '../../foreman.js';

export interface MessageTarget {
  id: string; name: string; source: 'OpenClaw' | 'Hermes' | 'Codex'; chat: boolean; coordinator?:boolean; sessionReference?:string;
  machine: {id: string; ssh?: string; port?: number; snapshot?: string; env: Record<string,string>};
  online: boolean;
}
interface Job { id:string; target:string; requestId?:string; text:string; state:'sending'|'queued'|'complete'|'uncertain'; createdAt:number; error?:string; errorResolved?:boolean; }

export function invokeAgent(target:MessageTarget, script:string, text:string, check=false):Promise<{ok:boolean; reply?:string; error?:string; queued?:boolean}> {
  const prefix = `import json, os\nos.environ.update(json.loads(${JSON.stringify(JSON.stringify(target.machine.env))}))\nREQUEST = json.loads(${JSON.stringify(JSON.stringify({source:target.source,text,check,coordinator:target.coordinator===true}))})\n`;
  return new Promise((resolve,reject)=>{
    const args=target.machine.ssh?['-T','-p',String(target.machine.port??22),'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','ConnectTimeout=5',target.machine.ssh,'python3','-']:['-'];
    const child=execFile(target.machine.ssh?'ssh':'python3',args,{timeout:check?20000:650000,maxBuffer:2*1024*1024},(error,stdout)=>{
      if(error) { reject(new Error('Connection ended before a verified reply. The agent may still be working; check its original app before resending.'));return; }
      try {const result=JSON.parse(stdout); if(typeof result.ok!=='boolean')throw Error();resolve(result);} catch {reject(new Error('Unrecognized reply from the agent bridge; no automatic retry was made.'));}
    });
    child.stdin?.on('error',()=>{}); child.stdin?.end(prefix+script);
  });
}

export class AgentConversations {
  targets=new Map<string,MessageTarget>();
  private running=new Set<string>();
  private jobs:Job[];
  constructor(private fm:Foreman, private invoke=invokeAgent) {
    this.jobs=(fm.store.data.backend.messageJobs as Job[]|undefined)??[];
    for(const job of this.jobs)if(job.state==='sending')job.state='uncertain';
    fm.store.data.backend.messageJobs=this.jobs;
    fm.store.markDirty();
  }
  lastError(id:string):string|undefined {const j=this.jobs.filter(j=>j.target===id).at(-1);return j?.state==='uncertain'&&!j.errorResolved?(j.error??'Last message failed; check the agent log'):undefined;}
  isBusy(id:string):boolean{return this.running.has(id);}
  accept(id:string,text:string,requestId?:string):Record<string,unknown> {
    const target=this.targets.get(id);
    if(!target)throw new ClientError('Choose one of your configured main agents. Broadcast messages are not supported.');
    if(target.source==='Codex'&&!/^codex:\/\/threads\/[a-f0-9-]{36}$/.test(target.sessionReference??''))throw new ClientError('Codex requires a pinned chat ID.');
    if(!text.trim()||text.length>32000)throw new ClientError('Message must contain 1–32000 characters.');
    const duplicate=requestId?this.jobs.find(j=>j.requestId===requestId):undefined;
    if(duplicate){
      if(duplicate.target!==id||duplicate.text!==text)throw new ClientError('Message ID was reused for different content.');
      return {messageId:duplicate.id,state:duplicate.state,duplicate:true};
    }
    if(!target.chat||!target.online||target.machine.snapshot)throw new ClientError('This main agent is offline or messaging is disabled.');
    if(this.running.has(id))throw new ClientError(`${target.name} is replying to your previous message. Wait for its reply before sending another.`);
    const job:Job={id:randomUUID(),target:id,requestId,text,state:'sending',createdAt:Date.now()};
    this.jobs.push(job);if(this.jobs.length>500)this.jobs.splice(0,this.jobs.length-500);
    this.fm.store.markDirty();this.fm.store.flush();
    this.running.add(id);
    this.fm.bus.send('user',id,text);
    this.fm.agentLog(id,'text',`You: ${text}`);
    this.fm.setAgent(id,{state:'thinking',station:'desk',activity:'Replying to you'});
    void this.deliver(target,job);
    return {messageId:job.id,state:'sending'};
  }
  private async deliver(target:MessageTarget,job:Job):Promise<void> {
    try {
      const script=fs.readFileSync(path.join(this.fm.config.projectRoot,'tools/message-agent.py'),'utf8');
      const response=target.source==='Codex'
        ? await new CodexDesktop(this.fm.config.home,target.sessionReference!.replace('codex://threads/','')).send(job.text)
        : await this.invoke(target,script,job.text);
      if(!response.ok||!response.reply)throw new Error(response.error??'No verified reply returned.');
      job.state=response.queued?'queued':'complete';
      // Agent logs retain the complete response in chunks; speech bubbles show a short preview.
      for(let i=0;i<response.reply.length;i+=1800)this.fm.agentLog(target.id,'text',response.reply.slice(i,i+1800));
      this.fm.bus.send(target.id,'user',response.reply);
      this.fm.setAgent(target.id,{state:response.queued?'thinking':'idle',station:response.queued?'desk':'lounge',activity:response.queued?'Message queued in Codex':'Available · replied to your message'});
    } catch(error) {
      job.state='uncertain';
      const message=(error as Error).message;job.error=message;
      this.fm.agentLog(target.id,'error',message);
      this.fm.bus.send(target.id,'user',`Message status: ${message}`);
      this.fm.setAgent(target.id,{state:'error',station:'user',activity:'Check message status in original app'});
    } finally {this.running.delete(target.id);this.fm.store.markDirty();this.fm.store.flush();}
  }
}
