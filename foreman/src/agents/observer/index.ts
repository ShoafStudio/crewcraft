import fs from 'node:fs';
import path from 'node:path';
import { execFile } from 'node:child_process';
import { z } from 'zod';
import { ClientError, type Backend, type Foreman } from '../../foreman.js';
import {runtimeActivity,workstation} from './activity.js';
import { MissionControl } from './mission-control.js';
import { Builder } from './builder.js';
import { Construction } from './construction.js';
import { AgentConversations } from './messaging.js';
import { AgentState, type Task } from '../../protocol.js';

const Machine = z.object({
  id: z.string().regex(/^[a-zA-Z0-9_-]+$/),
  label: z.string().min(1),
  ssh: z.string().regex(/^[a-zA-Z0-9_][a-zA-Z0-9_.@:-]*$/).optional(),
  port: z.number().int().min(1).max(65535).optional(),
  snapshot: z.string().optional(),
  env: z.record(z.string(), z.string()).default({}),
}).refine(m => !(m.ssh && m.snapshot), 'Choose SSH or snapshot, not both');
const Inventory = z.object({
  observedAt: z.number(),
  screens:z.array(z.object({source:z.string(),agent:z.string(),id:z.string(),at:z.number(),text:z.string()})).default([]),
  members: z.array(z.object({source:z.string(),id:z.string(),name:z.string()})).default([]),
  sources: z.array(z.object({ name: z.string(), status: z.string(), detail: z.string() })),
  projects: z.array(z.object({ id: z.string(), name: z.string(), path: z.string() })),
  sessions: z.array(z.object({ id: z.string(), source: z.string(), title: z.string(), runtimeAgent: z.string().optional(), projectId: z.string().nullable(), state: AgentState, activity: z.string(), updatedAt: z.number(), reference: z.string() })),
});
type InventoryData = z.infer<typeof Inventory>;
type ObservedSession = InventoryData['sessions'][number] & { machine: string; machineId: string };

/** SSH only runs our read-only collector through stdin; no files installed on other Macs. */
export function collectMachine(machine: z.infer<typeof Machine>, script: string): Promise<InventoryData> {
  if (machine.snapshot) return Promise.resolve(Inventory.parse(JSON.parse(fs.readFileSync(machine.snapshot, 'utf8'))));
  const allowed = new Set(['CODEX_HOME', 'OPENCLAW_STATE_DIR', 'HERMES_HOME', 'HQ_PROJECT_ROOTS', 'HQ_HERMES_SNAPSHOT', 'HQ_SOURCES', 'HQ_CODEX_THREAD_ID', 'HQ_HERMES_BIN', 'HQ_HERMES_CWD', 'HQ_MONITOR_OUTPUT']);
  if (Object.keys(machine.env).some(k => !allowed.has(k))) throw new Error('Unsupported collector environment key');
  const prefix = `import os, json\nos.environ.update(json.loads(${JSON.stringify(JSON.stringify(machine.env))}))\n`;
  return new Promise((resolve, reject) => {
    const args = machine.ssh
      ? ['-T', '-p', String(machine.port ?? 22), '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=yes', '-o', 'ConnectTimeout=5', machine.ssh, 'python3', '-']
      : ['-'];
    const child = execFile(machine.ssh ? 'ssh' : 'python3', args, { timeout: 12000, maxBuffer: 4 * 1024 * 1024 }, (error, stdout, stderr) => {
      if (error) {
        const reason = /requires an additional check/.test(stderr) ? 'Tailscale browser verification required' : /Connection refused/.test(stderr) ? 'SSH connection refused' : /Host key verification failed/.test(stderr) ? 'SSH host key not trusted yet' : /Permission denied|does not permit you to SSH/.test(stderr) ? 'SSH login denied; check username and Tailscale SSH policy' : 'Check SSH access and Python 3';
        reject(new Error(`${reason} (${error.code ?? 'timeout'})`)); return;
      }
      try { resolve(Inventory.parse(JSON.parse(stdout))); } catch (e) { reject(e); }
    });
    child.stdin?.on('error', () => {});
    child.stdin?.end(prefix + script);
  });
}

const Character = z.object({ id:z.string().regex(/^[a-z0-9-]+$/), name:z.string().min(1), machine:z.string(), source:z.enum(['OpenClaw','Hermes','Codex']), sessionReference:z.string().optional(), skin:z.enum(['marlow','juniper','kit','wren','rowan','tove']), coordinator:z.boolean().default(false), chat:z.boolean().default(false) });

export class ObserverBackend implements Backend {
  readonly name = 'observer' as const;
  private timer?: NodeJS.Timeout;
  private pending?: Promise<void>;
  private stopped = false;
  readonly conversations: AgentConversations;
  readonly construction: Construction;
  readonly builder: Builder;
  readonly missionControl: MissionControl;
  constructor(private fm: Foreman) { this.conversations = new AgentConversations(fm); this.construction = new Construction(fm); this.builder = new Builder(fm); this.missionControl = new MissionControl(fm); }
  acceptMessage(to:string,text:string,requestId?:string):Record<string,unknown> {
    if(/^\/build(?:\s|$)/.test(text)){
      const command=text.trim().split(/\s+/);
      if(command.length>2)throw new ClientError('Builder commands apply to the saved proposal; select characters in the console to talk to them. Use /build office, preview, status, pause, resume, undo, or build.');
      const action=command[1]??'status';
      const controls:Record<string,string>={office:'office',preview:'preview',status:'status',pause:'pause',resume:'resume',undo:'undo',build:'build it'};
      if(!controls[action])throw new ClientError('Use /build office, preview, status, pause, resume, undo, or build.');
      return this.builder.accept('builder',controls[action],requestId)!;
    }
    return this.builder.accept(to,text,requestId) ?? this.conversations.accept(to,text,requestId);
  }

  async start(): Promise<void> {
    this.fm.setStatus({ backend: 'observer', auth: 'ok', message: 'Headquarters: reading machine inventories' });
    await this.poll();
    this.timer = setInterval(() => { if (!this.pending) void this.poll(); }, 15000);
    this.timer.unref();
  }
  async stop(): Promise<void> { this.stopped = true; clearInterval(this.timer); await this.pending; }
  private poll(): Promise<void> {
    this.pending = this.refresh().catch(e => {
      this.fm.setStatus({ auth: 'failed', message: `Observer unavailable: ${String(e.message).slice(0, 160)}` });
      // A failed refresh must not leave agents looking active indefinitely.
      for (const a of this.fm.agents()) this.fm.setAgent(a.id, { state: 'idle', station: 'lounge', activity: 'Observer unavailable; status unknown' });
    }).finally(() => { this.pending = undefined; });
    return this.pending;
  }
  private async refresh(): Promise<void> {
    const file = path.join(this.fm.config.home, 'machines.json');
    const machines = z.array(Machine).max(16).parse(fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8')) : []);
    if (new Set(machines.map(m => m.id)).size !== machines.length) throw new Error('Machine IDs must be unique');
    const script = fs.readFileSync(path.join(this.fm.config.projectRoot, 'tools', 'observe.py'), 'utf8');
    const results = await Promise.allSettled(machines.map(async m => collectMachine(m, script)));
    if (this.stopped) return;
    const sessions: ObservedSession[] = [];
    const screens:Array<InventoryData['screens'][number]&{machine:string}>=[];
    const members:Array<{machine:string;source:string;id:string;name:string}>=[];
    const projects: Array<InventoryData['projects'][number] & { machine: string }> = [];
    const health: string[] = [];
    const connected = new Set<string>();
    let online = 0;
    results.forEach((r, i) => {
      const m = machines[i]!;
      if (r.status === 'rejected') { health.push(`${m.label}: OFFLINE — ${r.reason.message}`); return; }
      online++; connected.add(m.id);
      screens.push(...r.value.screens.map(row=>({...row,machine:m.id})));
      members.push(...r.value.members.map(member=>({...member,machine:m.id})));
      for (const source of r.value.sources) health.push(`${m.label} / ${source.name}: ${source.status} — ${source.detail}`);
      projects.push(...r.value.projects.map(p => ({ ...p, id: `${m.id}-${p.id}`, machine: m.label })));
      sessions.push(...r.value.sessions.map(s => ({ ...s, ...(Date.now() - r.value.observedAt > 180000 && !['done', 'error', 'blocked'].includes(s.state) ? { state: 'idle' as const, activity: 'Stale machine snapshot; current status unknown' } : {}), id: `${m.id}-${s.id}`, projectId: s.projectId ? `${m.id}-${s.projectId}` : null, machine: m.label, machineId: m.id })));
    });
    await this.builder.refresh();
    if (this.stopped) return;
    const busy = new Set(['thinking', 'running', 'editing', 'reading', 'testing', 'waiting_user']);
    sessions.sort((a, b) => Number(busy.has(b.state)) - Number(busy.has(a.state)) || b.updatedAt - a.updatedAt);
    const working = sessions.filter(s => busy.has(s.state)).length;
    const old = this.fm.store.data;
    const characterFile = path.join(this.fm.config.home, 'characters.json');
    const characters = z.array(Character).max(6).parse(fs.existsSync(characterFile) ? JSON.parse(fs.readFileSync(characterFile,'utf8')) : []);
    if(new Set(characters.map(c=>c.id)).size!==characters.length || new Set(characters.map(c=>c.skin)).size!==characters.length)throw new Error('Character IDs and desk skins must be unique');
    if(characters.filter(c=>c.coordinator).length>1)throw new Error('Choose only one coordinator');
    if(characters.some(c=>c.id==='builder'||c.id.startsWith('construction-')||c.id.startsWith('team-')))throw new Error('Reserved character ID');
    old.backend.coordinator=characters.find(c=>c.coordinator)?.id??null;
    for (const c of characters) if (!machines.some(m=>m.id===c.machine)) throw new Error(`Unknown machine for ${c.id}`);
    old.agents = [];
    this.conversations.targets.clear();
    const summaries: Task[] = [];
    for (const c of characters) {
      const machine = machines.find(m=>m.id===c.machine);
      if(!machine)throw new Error(`Unknown machine for ${c.id}`);
      const grouped = sessions.filter(s=>s.machineId===c.machine && s.source===c.source && (!c.sessionReference || s.reference===c.sessionReference));
      const configuredTeam=members.filter(m=>m.machine===c.machine && m.source===c.source);
      const subagents = new Set(grouped.filter(s=>s.runtimeAgent && s.runtimeAgent!=='main').map(s=>s.runtimeAgent));
      const latest=grouped[0];
      const sourceAvailable = results[machines.indexOf(machine)];
      const available=connected.has(c.machine) && sourceAvailable?.status==='fulfilled' && sourceAvailable.value.sources.some(s=>s.name===c.source && ['connected','partial'].includes(s.status));
      const busyHere=this.conversations.isBusy(c.id);
      const lastError=this.conversations.lastError(c.id);
      const codex=c.source==='Codex';
      const observed=runtimeActivity(grouped.filter(s=>!s.runtimeAgent||s.runtimeAgent==='main'),screens.filter(s=>s.machine===c.machine&&s.source===c.source&&s.agent==='main'));
      const observedState=observed.state;
      const mainState=busyHere?'thinking':lastError?'blocked':available?observedState:'blocked';
      old.agents.push({id:c.id,name:c.name,role:'lead',skin:c.skin,color:this.fm.cast.find(a=>a.id===c.skin)?.color??'#72B6A4',title:`${c.source} main agent · ${machine.label}`,state:mainState,station:workstation(mainState),activity:busyHere?'Replying to you':lastError?lastError.slice(0,110):available?(observedState!=='idle'?observed.activity:`Available · ${configuredTeam.length||subagents.size} team members`):'Offline · waiting for connection',active:true,paused:false,taskId:`agent-${c.id}`});
      this.conversations.targets.set(c.id,{...c,chat:c.chat,machine,online:available});
      const detail=`${c.name} — ${c.source} main agent on ${machine.label}\n${grouped.length} collected sessions; ${subagents.size} collected sub-agents.\n${codex?'Message @'+c.id+' to queue directly into this chat: '+c.sessionReference:'Message @'+c.name.toLowerCase()+' or right-click this character and choose Message.'}\n\n` + grouped.map(s=>`${s.runtimeAgent??'main'}: ${s.title}\n${s.activity} · ${new Date(s.updatedAt).toISOString()}`).join('\n\n');
      if(this.fm.memory.get(`shared/agent-${c.id}`)?.body!==detail)this.fm.memory.write({scope:'shared',slug:`agent-${c.id}`,title:`${c.name} — sessions and sub-agents`,body:detail,author:'observer'});
      // Team roster is stable by observed runtime identity; historical members rest until there is real work.
      const rosters=(old.backend.teamRosters??={}) as Record<string,Array<{key:string;name:string}>>;
      if(configuredTeam.length)rosters[c.id]=configuredTeam.map(m=>({key:m.id,name:m.name}));
      const roster=(rosters[c.id]??=[]);
      for(const member of configuredTeam.length?[]:grouped.filter(s=>s.runtimeAgent && s.runtimeAgent!=='main')) {
        if(!roster.some(r=>r.key===member.runtimeAgent))roster.push({key:member.runtimeAgent!,name:member.runtimeAgent!.startsWith('subagent-')?member.title.slice(0,28):member.runtimeAgent!});
      }
      const crew=roster;
      for(let i=0;i<crew.length;i++){
        const member=crew[i]!;
        const work=runtimeActivity(grouped.filter(s=>s.runtimeAgent===member.key),screens.filter(s=>s.machine===c.machine&&s.source===c.source&&s.agent===member.key));
        const memberState=available?work.state:'idle';
        old.agents.push({id:`team-${c.id}-${i}`,name:member.name,role:'worker',skin:['rowan','tove','kit','wren'][i%4]!,color:old.agents.find(a=>a.id===c.id)!.color,title:`${c.name}'s sub-agent`,state:memberState,station:workstation(memberState),activity:work.activity,active:true,paused:false});
      }
      const screenSeen=(old.backend.screenSeen??={}) as Record<string,string[]>;
      for(const output of screens.filter(s=>s.machine===c.machine&&s.source===c.source)){
        const memberIndex=crew.findIndex(r=>r.key===output.agent);
        const target=output.agent==='main'?c.id:memberIndex>=0?`team-${c.id}-${memberIndex}`:null;
        if(!target)continue;
        const seen=(screenSeen[target]??=[]);if(seen.includes(output.id))continue;
        seen.push(output.id);if(seen.length>200)seen.shift();
        this.fm.agentLog(target,'text',`[${new Date(output.at).toLocaleTimeString()}] ${output.text}`);
      }
      // Every computer displays fresh session metadata even when its runtime provides no output stream.
      for(const target of [c.id,...crew.map((_,i)=>`team-${c.id}-${i}`)]){
        const agent=old.agents.find(a=>a.id===target)!;
        const signature=`${agent.activity} · ${target===c.id?latest?.title??c.source:agent.name}`;
        const summary=(old.backend.screenSummary??={}) as Record<string,string>;
        if(summary[target]!==signature){summary[target]=signature;this.fm.agentLog(target,'text',`${agent.name} — ${signature}`);}
      }
      if(codex && c.sessionReference)await this.syncCodex(c.id,c.sessionReference);
      summaries.push({id:`agent-${c.id}`,title:c.name,description:detail,status:!available?'blocked':busyHere?'doing':'todo',assignee:c.id,deps:[],priority:1,ci:'unknown',createdBy:'observer',createdAt:latest?.updatedAt??0,updatedAt:latest?.updatedAt??0});
    }
    const builderState=this.builder.busy?'thinking':this.builder.status.state==='building'||this.builder.status.state==='undoing'?'editing':'idle';
    old.agents.push({id:'builder',name:'Builder',role:'lead',skin:'rowan',color:'#E8AF50',title:'In-game construction foreman',state:builderState,station:workstation(builderState),activity:this.builder.busy?'Planning your request':this.builder.status.state==='building'?`Building · ${this.builder.status.percent}%`:'Available · talk to me to build',active:true,paused:false});
    old.repos = projects.map(p => ({ id: p.id, name: `${p.name} · ${p.machine}`, path: p.path, branch: 'observed', dirty: false, worktrees: [], ci: 'unknown' }));
    old.tasks = [...summaries,...await this.missionControl.refresh()];
    const docs = [
      ['machines', 'Machine connections', health.join('\n\n')],
      ['projects', 'All observed projects', projects.map(p => `${p.machine} / ${p.name}\n${p.path}`).join('\n\n')],
      ['sessions', 'All observed sessions', sessions.map(s => `${s.source} / ${s.machine}: ${s.title}\n${s.activity} · ${new Date(s.updatedAt).toISOString()}\n${s.reference}`).join('\n\n')],
      ['guide', 'Headquarters controls', 'Managers have permanent identities and offices. Their sub-agents have separate team characters; historical members rest unless their runtime reports recent work. Minecraft construction workers are separate local NPCs controlled by the game; they never represent these real agents. Right-click a character and choose Message, or use @your-agent in the console. Replies appear in the character log and a speech bubble. Messages use a dedicated persistent Headquarters conversation with the existing runtime identity, memory, skills and model settings. No messages are forwarded to Discord or other channels. Approvals, merges and lifecycle controls remain in the original runtime. Message @codex to queue into the pinned Headquarters chat. Replies are mirrored into its log. /build office proposes attached rooms for your configured managers. /build preview opens the proposal and /build build constructs it. /build status, pause, resume and undo control Minecraft construction only. Other Codex chats remain in the library.'],
    ];
    for (const [slug, title, body] of docs) if (this.fm.memory.get(`shared/${slug}`)?.body !== body) this.fm.memory.write({ scope: 'shared', slug: slug!, title: title!, body: body!, author: 'observer' });
    old.backend.observer = { observedAt: Date.now(), machines: machines.length, online, projects: projects.length, sessions: sessions.length, characters:characters.length, teamMembers:old.agents.length-characters.length, working };
    this.fm.store.markDirty();
    this.fm.setStatus({ auth: !machines.length || online ? 'ok' : 'failed', message: !machines.length ? 'CrewCraft: run node tools/setup-crewcraft.mjs to connect your own agents' : `CREWCRAFT · ${characters.length} main agents · ${online}/${machines.length} machines · ${projects.length} projects` });
    this.fm.ctx.emit(this.fm.snapshot());
  }
  private async syncCodex(id:string,reference:string):Promise<void> {
    const thread=reference.replace('codex://threads/','');
    if(!/^[a-f0-9-]{36}$/.test(thread))return;
    try {
      const data:any=await new Promise((resolve,reject)=>execFile('python3',[path.join(this.fm.config.projectRoot,'tools/codex-chat.py'),thread],{timeout:8000,maxBuffer:1024*1024},(e,out)=>{if(e)reject(e);else try{resolve(JSON.parse(out));}catch(e){reject(e);}}));
      const backend=this.fm.store.data.backend;
      const seen=(backend.codexMirrored??=[]) as string[];
      if(backend.codexMirrorInitialized!==thread){seen.push(...data.messages.map((m:any)=>m.id));backend.codexMirrorInitialized=thread;}
      for(const m of data.messages){
        if(seen.includes(m.id))continue;seen.push(m.id);
        for(let i=0;i<m.text.length;i+=1800)this.fm.agentLog(id,'text',m.text.slice(i,i+1800));
        if(m.phase==='final_answer')this.fm.bus.send(id,'user',m.text);
      }
      if(seen.length>300)seen.splice(0,seen.length-300);
    }catch{this.fm.agentLog(id,'error','Codex output connection unavailable; queued messages remain in the Codex chat.');}
  }
  async submitGoal(): Promise<never> { throw new ClientError('Read-only observer'); }
  onUserMessage(): void { throw new ClientError('Read-only observer'); }
  onDecisionSettled(): void {}
  onTaskAction(): void { throw new ClientError('Read-only observer'); }
  onAgentAction(): void { throw new ClientError('Read-only observer'); }
}
