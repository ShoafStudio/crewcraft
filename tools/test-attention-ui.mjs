// Native attention-board regression check. Does not send messages or modify tasks.
import assert from 'node:assert/strict';
import {DevClient} from './lib/devclient.mjs';
const dev = await DevClient.connect();
await dev.waitInWorld({timeoutMs:60000});
const initial = await dev.request('dev.state');
const key = async key => {
  const result = await dev.request('dev.key', {key});
  assert.equal(result.screenHandled, true, `${key} should be handled`);
};
const shot = name => dev.request('dev.screenshot', {name, hideHud: false, waitChunks: false});
try {
  for (const [width, height, scale, name] of [[1920,1080,3,'wide'], [1280,800,4,'compact']]) {
    await dev.request('dev.window', {width, height});
    await dev.request('dev.hud.guiScale', {scale});
    await dev.request('dev.wait', {frames:10});
    await dev.request('dev.screen', {open:'attention'});
    await dev.request('dev.wait', {frames:3});
    await key('right');
    await key('down');
    await shot(`attention-board-${name}`);
    await key('enter');
    await shot(`attention-detail-${name}`);
    await key('escape');
    assert.match((await dev.request('dev.state')).screen.class, /AttentionScreen$/);
    await key('escape');
    assert.equal((await dev.request('dev.state')).screen, null);
  }
  await dev.request('dev.screen', {open:'attention'});
  await dev.request('dev.wait', {frames:3});
  await key('j');
  assert.match((await dev.request('dev.state')).screen.class, /ConsoleScreen$/);
  const consoleState = await dev.request('dev.console', {open:false});
  assert.match(consoleState.value, /Let's review Mission Control task /);
  await dev.request('dev.console', {prefill:''});
  console.log('PASS: board keyboard navigation, task detail/back/close, compact and wide captures, Coordinator draft; no messages sent.');
} finally {
  await dev.request('dev.window', {width:initial.window.width, height:initial.window.height});
  await dev.request('dev.hud.guiScale', {scale:initial.window.guiScale});
  await dev.request('dev.screen', {open:'attention'});
  dev.close();
}
