// Native UI regression check. Uses local DevBridge; never submits an agent message.
import assert from 'node:assert/strict';
import {DevClient} from './lib/devclient.mjs';
const dev=await DevClient.connect();
const initial=await dev.request('dev.state');
const refresh=async()=>{await dev.request('dev.wait',{frames:3});return dev.request('dev.console',{open:false});};
const fits=state=>{
 const [w,h]=state.layout.screen;
 for(const key of ['panel','composer']){
  const [x,y,rw,rh]=state.layout[key];
  assert(x>=0&&y>=0&&rw>0&&rh>0&&x+rw<=w&&y+rh<=h,`${key} outside ${w}x${h}: ${state.layout[key]}`);
 }
};
try{
 await dev.request('dev.console',{prefill:''});
 await dev.request('dev.console',{conversation:'forge'});
 await dev.request('dev.type',{text:'Unsent Forge draft'});
 let state=await refresh();
 assert.equal(state.layout.recipientInput,'@forge Unsent Forge draft');
 assert.equal(state.intent,'message Forge');
 await dev.request('dev.key',{key:'key.keyboard.page.up'});
 state=await refresh();const offset=state.layout.offset;
 await dev.request('dev.console',{conversation:'coordinator'});
 assert.equal((await refresh()).value,'');
 await dev.request('dev.type',{text:'Unsent Coordinator draft'});
 assert.equal((await refresh()).layout.recipientInput,'@coordinator Unsent Coordinator draft');
 await dev.request('dev.console',{conversation:'forge'});
 state=await refresh();assert.equal(state.value,'Unsent Forge draft');assert.equal(state.layout.offset,offset);
 await dev.request('dev.type',{text:' retained focus'});
 assert.equal((await refresh()).value,'Unsent Forge draft retained focus');
 for(const scale of [2,3,4]){
  await dev.request('dev.hud.guiScale',{scale});
  await dev.request('dev.console',{prefill:Array.from({length:8},(_,i)=>`Line ${i}`).join('\n')});
  fits(await refresh());
 }
 await dev.request('dev.window',{width:1280,height:800});
 await dev.request('dev.hud.guiScale',{scale:3});
 fits(await refresh());
 console.log('PASS: correct recipient, independent drafts, typing after switch, per-chat scroll, bounded layout at 3 scales and 1280×800; no messages sent.');
}finally{
 await dev.request('dev.window',{width:initial.window.width,height:initial.window.height});
 await dev.request('dev.hud.guiScale',{scale:initial.window.guiScale});
 // Remove test drafts before returning control.
 for(const conversation of ['forge','coordinator','all']){
  await dev.request('dev.console',{conversation});
  await dev.request('dev.console',{prefill:''});
 }
 await dev.request('dev.screen',{open:null});
 dev.close();
}
