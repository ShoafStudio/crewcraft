import {execFile} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

/** Queue into the existing chat through the installed CLI; never resume it in a second process. */
export class CodexDesktop {
  constructor(private home:string,readonly threadId:string) {}
  async send(text:string):Promise<{ok:boolean;reply:string;queued:boolean;error?:string}> {
    if(!/^[a-f0-9-]{36}$/.test(this.threadId))throw new Error('Invalid pinned Codex chat ID');
    const file=path.join(this.home,'codex-desktop.json');const config=fs.existsSync(file)?JSON.parse(fs.readFileSync(file,'utf8')):{};
    const binary=process.env.CODEX_CLI_PATH||config.cli||'codex';
    return new Promise((resolve,reject)=>execFile(binary,['queue','--thread',this.threadId,'--message',text],{timeout:20000,maxBuffer:1024*1024},(error,stdout)=>{
      if(error){reject(new Error('Codex did not confirm queuing the message. Check the chat before resending; no automatic retry was made.'));return;}
      if(!stdout.includes('Queued message ')||!stdout.includes(this.threadId)){reject(new Error('Unrecognized Codex queue response; check the chat before resending.'));return;}
      resolve({ok:true,queued:true,reply:'Queued in this Codex chat. It will be handled after the current turn; replies appear here.'});
    }));
  }
}
