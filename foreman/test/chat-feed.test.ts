import {describe,it,expect} from 'vitest';
import {makeForeman,tempDir,rmrf} from './helpers.js';
import {truncate} from '../src/util/text.js';
describe('conversation feed',()=>{
 it('keeps long chat replies and user messages while bounding speech bubbles and activity summaries',async()=>{
  const home=tempDir(),{fm,events}=makeForeman(home);
  try{
   const text='A complete reply with evidence.\n'.repeat(100);
   fm.bus.send('forge','user',text);fm.bus.send('user','coordinator',text);
   expect(fm.store.data.feed.slice(-2).map(x=>x.text)).toEqual([text,text]);
   expect(events.find(e=>e.type==='agent.say')?.text.length).toBeLessThanOrEqual(600);
   expect(fm.bus.feed('plan',text).text.length).toBeLessThanOrEqual(400);
   expect(fm.bus.feed('message','x'.repeat(40000)).text.length).toBeLessThanOrEqual(32000);
  }finally{await fm.close();rmrf(home);}
 });
 it('restores historical previews from the matching sender and recipient without changing saved history',async()=>{
  const home=tempDir(),{fm}=makeForeman(home);
  try{
   const text='Verified result and supporting details. '.repeat(35);
   fm.bus.send('forge','user',text);
   const item=fm.store.data.feed.at(-1)!;item.text=truncate(text,400);
   expect(fm.bus.conversationFeed().at(-1)?.text).toBe(text);
   expect(item.text).toBe(truncate(text,400));
   item.agentId='coordinator';
   expect(fm.bus.conversationFeed().at(-1)?.text).toBe(item.text);
  }finally{await fm.close();rmrf(home);}
 });
});
