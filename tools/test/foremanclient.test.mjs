import { test } from 'node:test';
import assert from 'node:assert/strict';
import { WebSocketServer } from 'ws';
import { ForemanClient } from '../lib/foremanclient.mjs';

// A tiny stand-in for the Foreman's WS server (protocol v1 shapes from docs/protocol.md).
function fakeForeman() {
  const wss = new WebSocketServer({ host: '127.0.0.1', port: 0 });
  const seen = { origins: [], messages: [] };
  wss.on('connection', (ws, req) => {
    seen.origins.push(req.headers.origin ?? null);
    ws.on('message', (data) => {
      const m = JSON.parse(String(data));
      seen.messages.push(m);
      const send = (o) => ws.send(JSON.stringify({ v: 1, ...o }));
      if (m.type === 'hello') {
        send({ type: 'snapshot', foreman: { version: '0.1.0', backend: 'sim', auth: 'ok', showcase: false }, agents: [{ id: 'kit', name: 'Kit', active: true }], tasks: [], decisions: [{ id: 'd1', kind: 'merge', status: 'open', repoId: 'r', worktree: 'kit-t1', createdAt: 1 }], repos: [], memory: [], goals: [], feed: [], logs: [] });
        setTimeout(() => send({ type: 'foreman.status', status: { version: '0.1.0', backend: 'sim', auth: 'ok', showcase: true } }), 50);
      } else if (m.type === 'diff.request') {
        if (m.id) send({ type: 'ack', re: m.id, ok: true });
        send({ type: 'diff', requestId: m.requestId, repoId: m.repoId, worktree: m.worktree, files: [{ path: 'a.ts', status: 'modified', binary: false, additions: 1, deletions: 0, hunks: [] }], stats: { files: 1, additions: 1, deletions: 0 }, truncated: false });
      } else if (m.type === 'user.message' && m.to === 'nobody') {
        send({ type: 'ack', re: m.id, ok: false, error: 'no agent named "nobody"' });
      } else if (m.id) {
        send({ type: 'ack', re: m.id, ok: true, result: { echo: m.type } });
        if (m.type === 'agent.action') send({ type: 'agent.upsert', agent: { id: 'kit', name: 'Kit', active: false } });
      }
    });
  });
  return new Promise((resolve) => wss.on('listening', () => resolve({ wss, seen, port: wss.address().port })));
}

test('hello -> snapshot, acks, rejected acks, diff, live state, no Origin header', async () => {
  const { wss, seen, port } = await fakeForeman();
  const fm = await ForemanClient.connect({ port, timeoutMs: 5000, client: 'test' });
  try {
    assert.equal(seen.messages[0].type, 'hello');
    assert.equal(seen.messages[0].v, 1);
    assert.equal(seen.messages[0].protocol, 1);
    assert.deepEqual(seen.origins, [null]);
    assert.equal(fm.state.agents.get('kit').name, 'Kit');
    assert.deepEqual(fm.openDecisions('merge').map((d) => d.id), ['d1']);

    await fm.waitForState((s) => s.foreman?.showcase === true, { timeoutMs: 2000 });

    const ack = await fm.send('agent.action', { agentId: 'kit', action: 'stop' });
    assert.equal(ack.ok, true);
    assert.equal(ack.result.echo, 'agent.action');
    await fm.waitForState((s) => s.agents.get('kit').active === false, { timeoutMs: 2000 });

    await assert.rejects(fm.send('user.message', { to: 'nobody', text: 'x' }), /no agent named/);

    const d = await fm.diff('r', 'kit-t1');
    assert.equal(d.stats.files, 1);
    const req = seen.messages.find((m) => m.type === 'diff.request');
    assert.equal(req.repoId, 'r');
    assert.ok(req.requestId && req.id && req.requestId !== req.id);
  } finally {
    fm.close();
    wss.close();
  }
});

test('connect retries until the Foreman is up, then times out cleanly when it never is', async () => {
  await assert.rejects(ForemanClient.connect({ port: 1, timeoutMs: 600, retryMs: 100 }), /not reachable/);
});
