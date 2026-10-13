import {execFile} from 'node:child_process';
import {parseUnifiedDiff} from '../../diff.js';
import type {DiffResult} from '../../repos.js';
export function pullRequestLink(text:string):string|undefined{
 return text.match(/https:\/\/github\.com\/([A-Za-z0-9_.-]+)\/([A-Za-z0-9_.-]+)\/pull\/([1-9][0-9]*)(?=$|[\s#?/)\]>,.;])/i)?.[0];
}
function gh(args:string[]):Promise<string>{return new Promise((resolve,reject)=>execFile('gh',args,{timeout:30000,maxBuffer:8*1024*1024,env:{...process.env,GH_PROMPT_DISABLED:'1'}},(e,out)=>e?reject(new Error('GitHub PR unavailable. Check GitHub CLI access on the local Mac; no changes were made.')):resolve(out)));}
export async function pullRequestDiff(repoId:string,worktree:string,url:string):Promise<DiffResult>{
 if(pullRequestLink(url)!==url)throw new Error('Invalid GitHub PR URL');
 const endpoint='repos/'+new URL(url).pathname.slice(1).replace('/pull/','/pulls/');
 const metadata=async()=>JSON.parse(await gh(['api',endpoint,'--jq','{base: .base.sha, head: .head.sha, branch: .head.ref, state: .state, merged: .merged}']));
 const before=await metadata();
 const diff=await gh(['pr','diff',url,'--color','never']);
 const after=await metadata();
 if(before.head!==after.head||before.base!==after.base)throw new Error('The PR changed while loading. Open its diff again for a consistent revision.');
 return {...parseUnifiedDiff(diff),repoId,worktree,base:after.base.slice(0,8),branch:`${after.branch} · ${after.merged?'merged':after.state} · ${after.head.slice(0,8)}`};
}
