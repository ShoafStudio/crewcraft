import type {AgentState} from '../../protocol.js';
const working=new Set<AgentState>(['thinking','running','reading','editing','testing']);
export function workstation(state:AgentState):'desk'|'lounge'{return working.has(state)?'desk':'lounge';}
/** Activity must be recent evidence, not merely an open session or a configured profile. */
export function runtimeActivity(sessions:Array<{state:AgentState;updatedAt:number;activity:string}>,output:Array<{at:number;text:string}>,now=Date.now()):{state:AgentState;activity:string}{
 const recent=[...sessions].sort((a,b)=>b.updatedAt-a.updatedAt)[0];
 const last=[...output].sort((a,b)=>a.at-b.at).at(-1);
 // A newer visible reply supersedes an older session index that still says "running".
 if(last&&last.at>=(recent?.updatedAt??0)&&!last.text.startsWith('Tools:'))return{state:'idle',activity:'Available · last reply completed'};
 if(recent&&now-recent.updatedAt<180000&&working.has(recent.state))return{state:recent.state,activity:recent.activity};
 if(last&&now-last.at>=0&&now-last.at<45000&&last.text.startsWith('Tools:'))return{state:'running',activity:'Recent tool activity · '+last.text.slice(7,85)};
 return{state:'idle',activity:'Available · no active work observed'};
}
