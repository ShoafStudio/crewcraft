#!/usr/bin/env node
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createInterface} from 'node:readline/promises';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const home=path.join(root,'.crewcraft-home');
if(process.argv.includes('--help')){console.log('node tools/setup-crewcraft.mjs\nInteractive setup for your first local or SSH agent. Writes private .crewcraft-home/ configuration only; does not connect or send messages. For additional agents, edit the JSON files using tools/*.example.json. Existing files are never overwritten.');process.exit(0);}
if(['machines.json','characters.json'].some(n=>fs.existsSync(path.join(home,n)))){console.error('Configuration already exists. Edit .crewcraft-home/machines.json and characters.json to add agents; setup will not overwrite them.');process.exit(1);}
if(!process.stdin.isTTY){console.error('Run setup in an interactive terminal, or copy and edit tools/*.example.json.');process.exit(1);}
const rl=createInterface({input:process.stdin,output:process.stdout});
try{
 const ask=async(q,def='')=>(await rl.question(q+(def?` [${def}]`:'')+': ')).trim()||def;
 const name=await ask('Your agent’s display name','My Agent');
 const id=await ask('Stable lowercase agent ID','my-agent');
 if(!/^[a-z0-9-]+$/.test(id)||id==='builder'||id.startsWith('team-')||id.startsWith('construction-'))throw Error('Use a lowercase ID without spaces; builder, team-* and construction-* are reserved.');
 const source=await ask('Runtime: Hermes, OpenClaw, or Codex','Hermes');
 if(!['Hermes','OpenClaw','Codex'].includes(source))throw Error('Runtime must be Hermes, OpenClaw, or Codex.');
 const ssh=await ask('SSH alias or user@host (leave blank for this computer)');
 if(ssh&&!/^[a-zA-Z0-9_][a-zA-Z0-9_.@:-]*$/.test(ssh))throw Error('Invalid SSH target. Configure ports and identities in ~/.ssh/config.');
 if(ssh&&source==='Codex')throw Error('The Codex desktop messaging bridge is local-only. Configure remote Codex as read-only inventory manually.');
 const projects=await ask('Project root on that computer (optional; leave blank for no extra folder scan)');
 const chat=(await ask('Enable sending messages to this agent? yes/no','no')).toLowerCase()==='yes';
 let sessionReference;
 if(source==='Codex'&&chat){const thread=await ask('Existing Codex chat UUID');if(!/^[a-f0-9-]{36}$/.test(thread))throw Error('Invalid chat UUID');sessionReference='codex://threads/'+thread;}
 const coordinator=(await ask('Use this agent as your coordinator? yes/no','yes')).toLowerCase()==='yes';
 const machine={id:'primary',label:ssh?'My agent host':'This computer',...(ssh?{ssh}:{}),env:{HQ_SOURCES:source,HQ_PROJECT_ROOTS:projects}};
 fs.mkdirSync(home,{recursive:true,mode:0o700});
 for(const [file,data] of [['machines.json',[machine]],['characters.json',[{id,name,machine:'primary',source,skin:'marlow',chat,coordinator,...(sessionReference?{sessionReference}:{})}]]])fs.writeFileSync(path.join(home,file),JSON.stringify(data,null,2)+'\n',{mode:0o600,flag:'wx'});
 console.log('Saved your private configuration. No connections or messages were sent. Launch CrewCraft when ready. SSH hosts must already be trusted and work with BatchMode authentication.');
}catch(e){console.error(e.message);process.exitCode=1;}finally{rl.close();}
