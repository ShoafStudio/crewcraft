// Hand-off of a task from a stopped worker, with real processes keeping the old worktree busy
// (what a real Claude CLI does on Windows for a moment after close()), plus: processes a stopped
// turn started are killed, messages that arrive after an agent's last tool call are delivered,
// and the scheduler recovers from errors by itself.
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { git } from '../src/util/git.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: '00000000-0000-4000-8000-000000000000', ...o }) as unknown as SDKMessage;
const sid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const init = (s: string) => m({ type: 'system', subtype: 'init', session_id: s, model: 'fake' });
const ok = (s: string) => m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0.001, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

const cleanup: string[] = [];
const kids: ChildProcess[] = [];
/** the Foreman's info/warn lines (shown when an assertion fails) */
const infos: string[] = [];
const pidsToCheck: number[] = [];
afterAll(() => {
  kids.forEach((k) => {
    try {
      k.kill();
    } catch {
      /* gone */
    }
  });
  for (const pid of pidsToCheck) {
    try {
      process.kill(pid);
    } catch {
      /* gone */
    }
  }
  cleanup.forEach(rmrf);
});

function pidAlive(pid: number): boolean {
  if (process.platform === 'win32') {
    const r = spawnSync('tasklist', ['/FI', `PID eq ${pid}`, '/NH', '/FO', 'CSV'], { encoding: 'utf8', windowsHide: true });
    return r.stdout.includes(`"${pid}"`);
  }
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
}

interface Call {
  agent: string;
  prompt: string;
}

type Script = (o: Options, p: string, aborted: Promise<never>, onClose: (f: () => void) => void) => AsyncGenerator<SDKMessage>;

function fake(calls: Call[], scripts: Record<string, Script>) {
  return ({ prompt, options }: { prompt: string; options: Options }) => {
    const p = String(prompt);
    const lead = 'create_task' in tools(options);
    const agent = lead ? 'marlow' : (/[\\/](\w+)-t\d+(-\d+)?$/.exec(options.cwd!)?.[1] ?? '?');
    calls.push({ agent, prompt: p });
    const aborted = new Promise<never>((_, reject) => options.abortController!.signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }));
    aborted.catch(() => undefined);
    const closers: Array<() => void> = [];
    async function* run(): AsyncGenerator<SDKMessage> {
      if (lead) {
        yield init(sid(1));
        if (p.startsWith('New goal')) await callTool(options, 'create_task', { title: 'Add a version flag', description: 'x', assignee: 'kit' });
        const review = /Review request: (t\d+)/.exec(p)?.[1];
        if (review) await callTool(options, 'request_merge', { task_id: review, summary: 'ok' });
        yield ok(sid(1));
        return;
      }
      yield* scripts[agent]!(options, p, aborted, (f) => closers.push(f));
    }
    return Object.assign(run(), {
      close() {
        closers.forEach((f) => f());
      },
      accountInfo: async () => ({ email: 'x' }),
    });
  };
}

async function boot(workers: string, calls: Call[], scripts: Record<string, Script>): Promise<{ h: Harness; b: ClaudeBackend; errors: string[] }> {
  const home = tempDir();
  const repo = await demoRepo();
  cleanup.push(home, path.dirname(repo));
  const h = makeForeman(home, ['--backend', 'claude', '--workers', workers, '--repo', repo]);
  const errors: string[] = [];
  const log = h.fm.log as { error: (s: string) => void; warn: (s: string) => void; info: (s: string) => void };
  log.error = (s: string) => errors.push(s);
  log.warn = (s: string) => infos.push(`warn ${s}`);
  log.info = (s: string) => infos.push(`info ${s}`);
  const b = new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fake(calls, scripts) as never, skipAuthCheck: true });
  await h.fm.start(b);
  return { h, b, errors };
}

let juniperSawKitWork: boolean | undefined;
const juniperFinishes: Script = async function* (o) {
  yield init(sid(3));
  juniperSawKitWork = fs.existsSync(path.join(o.cwd!, 'KIT_PARTIAL.md'));
  fs.appendFileSync(path.join(o.cwd!, 'README.md'), '\nversion flag (Juniper)\n');
  await callTool(o, 'update_task', { task_id: 't1', status: 'review', summary: 'finished by Juniper' });
  yield ok(sid(3));
};

describe('claude backend hand-off with real processes (fake SDK)', () => {
  it("stop: Juniper continues from Kit's branch even while Kit's worktree is still busy", async () => {
    // the verifier's repro: the "CLI" has a process with cwd = Kit's worktree that lives on for
    // 3 s after close(), as a real CLI does on Windows
    juniperSawKitWork = undefined;
    const calls: Call[] = [];
    const kit: Script = async function* (o, _p, aborted, onClose) {
      const child = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 60000)'], { cwd: o.cwd!, stdio: 'ignore' });
      kids.push(child);
      onClose(() => setTimeout(() => child.kill(), 3000));
      yield init(sid(2));
      fs.writeFileSync(path.join(o.cwd!, 'KIT_PARTIAL.md'), 'half-done by Kit\n');
      await Promise.race([callTool(o, 'ask_user', { question: 'Short or long?', options: ['Short', 'Long'] }), aborted]);
      yield ok(sid(2));
    };
    const { h, errors } = await boot('kit,juniper', calls, { kit, juniper: juniperFinishes });
    const fm = h.fm;
    await fm.submitGoal('version flag');
    await until(() => fm.decisions.open().some((d) => d.kind === 'question' && d.agentId === 'kit'));
    await fm.handle({ v: 1, type: 'agent.action', agentId: 'kit', action: 'stop' }, () => undefined);
    // no manual tick: the hand-off finishes by itself
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 30_000);
    expect(errors).toEqual([]);
    expect(juniperSawKitWork).toBe(true);
    const t1 = fm.tasks.get('t1')!;
    expect(t1.assignee).toBe('juniper');
    expect(t1.worktree).toBe('juniper-t1');
    const diff = await fm.repos.diff('demo-app', 'juniper-t1');
    expect(diff.files.map((f) => f.path).sort()).toEqual(['KIT_PARTIAL.md', 'README.md']);
    const kitWt = fm.repos.findWorktree('demo-app', 'kit-t1')!;
    expect(kitWt.status).toBe('abandoned');
    const subject = (await git(fm.repos.require('demo-app').path, ['log', '--format=%s', kitWt.branch, '-1'])).stdout.trim();
    expect(subject).toMatch(/work in progress \(Kit was stopped\)/);
    expect(h.events.some((e) => e.type === 'feed.add' && /Juniper continues t1 from Kit's branch/.test(e.item.text))).toBe(true);
    // the busy directory is gone once nothing holds it any more
    await until(() => !fs.existsSync(kitWt.path), 20_000);
    await fm.close();
  });

  it('stop kills what the stopped turn started (orphans of the CLI too)', async () => {
    juniperSawKitWork = undefined;
    const calls: Call[] = [];
    const pidFile = path.join(tempDir('ac-pid-'), 'grandchild.pid');
    cleanup.push(path.dirname(pidFile));
    let cli: ChildProcess | undefined;
    const kit: Script = async function* (o, _p, aborted, onClose) {
      // a fake "CLI" spawned exactly like the real one (through spawnClaudeCodeProcess), which
      // starts a long-running tool process in the worktree (e.g. `npm test`) and ignores stdin EOF
      const script = `const { spawn } = require('child_process');
const g = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { cwd: process.cwd(), stdio: 'ignore' });
require('fs').writeFileSync(${JSON.stringify(pidFile)}, String(g.pid));
setInterval(() => {}, 1000);`;
      cli = o.spawnClaudeCodeProcess!({ command: process.execPath, args: ['-e', script], cwd: o.cwd!, env: { ...process.env }, signal: new AbortController().signal }) as ChildProcess;
      kids.push(cli);
      // what the SDK does after its grace period: kill the CLI process only (its child survives)
      onClose(() => setTimeout(() => cli?.kill(), 300));
      yield init(sid(2));
      fs.writeFileSync(path.join(o.cwd!, 'KIT_PARTIAL.md'), 'half-done by Kit\n');
      await Promise.race([callTool(o, 'ask_user', { question: 'Short or long?', options: ['Short', 'Long'] }), aborted]);
      yield ok(sid(2));
    };
    const { h, errors } = await boot('kit,juniper', calls, { kit, juniper: juniperFinishes });
    const fm = h.fm;
    await fm.submitGoal('version flag');
    await until(() => fm.decisions.open().some((d) => d.kind === 'question' && d.agentId === 'kit'));
    await until(() => fs.existsSync(pidFile) && fs.readFileSync(pidFile, 'utf8').length > 0);
    const grandchild = Number(fs.readFileSync(pidFile, 'utf8'));
    pidsToCheck.push(grandchild);
    expect(pidAlive(grandchild)).toBe(true);
    await fm.agentAction('kit', 'stop');
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 40_000);
    expect(errors).toEqual([]);
    expect(pidAlive(grandchild), infos.join('\n')).toBe(false);
    expect(cli!.exitCode !== null || cli!.signalCode !== null).toBe(true);
    expect(juniperSawKitWork).toBe(true);
    await until(() => !fs.existsSync(fm.repos.findWorktree('demo-app', 'kit-t1')!.path), 20_000);
    await fm.close();
  });

  it('pause then an immediate resume: the resumed turn waits until the old CLI is gone', async () => {
    const calls: Call[] = [];
    let oldCli: ChildProcess | undefined;
    let oldAliveWhenResumed: boolean | undefined;
    const kit: Script = async function* (o, p, aborted) {
      if (p.startsWith('Reviewer paused you')) {
        oldAliveWhenResumed = !!oldCli && oldCli.exitCode === null && oldCli.signalCode === null;
        yield init(sid(2));
        fs.appendFileSync(path.join(o.cwd!, 'README.md'), '\nversion flag (Kit)\n');
        await callTool(o, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
        yield ok(sid(2));
        return;
      }
      // a "CLI" that ignores close() (it only dies when the Foreman kills it)
      oldCli = o.spawnClaudeCodeProcess!({ command: process.execPath, args: ['-e', 'setInterval(() => {}, 1000)'], cwd: o.cwd!, env: { ...process.env }, signal: new AbortController().signal }) as ChildProcess;
      kids.push(oldCli);
      yield init(sid(2));
      await aborted.catch(() => undefined);
      yield ok(sid(2));
    };
    const { h, errors } = await boot('kit', calls, { kit });
    const fm = h.fm;
    await fm.submitGoal('version flag');
    await until(() => !!oldCli && fm.agent('kit')?.taskId === 't1');
    await fm.agentAction('kit', 'pause');
    await fm.agentAction('kit', 'resume');
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 40_000);
    expect(oldAliveWhenResumed).toBe(false);
    expect(errors).toEqual([]);
    await fm.close();
  });

  it("a message that arrives after the agent's last tool call is delivered when the turn ends", async () => {
    const calls: Call[] = [];
    let release!: () => void;
    const gate = new Promise<void>((r) => (release = r));
    let firstTurnDone = false;
    const kit: Script = async function* (o, p) {
      yield init(sid(2));
      if (!firstTurnDone) {
        fs.appendFileSync(path.join(o.cwd!, 'README.md'), '\nversion flag (Kit)\n');
        await callTool(o, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
        // the last agentcraft tool call is behind us; the user writes now, then the turn ends
        await gate;
        firstTurnDone = true;
        yield ok(sid(2));
        return;
      }
      expect(p).toMatch(/please also mention emoji tags/);
      await callTool(o, 'send_message', { to: 'user', text: 'Will do.' });
      yield ok(sid(2));
    };
    const { h } = await boot('kit', calls, { kit });
    const fm = h.fm;
    await fm.submitGoal('version flag');
    await until(() => fm.tasks.get('t1')?.status === 'review');
    await fm.handle({ v: 1, type: 'user.message', to: 'kit', text: 'please also mention emoji tags' }, () => undefined);
    expect(fm.bus.inbox('kit').length).toBe(1); // not consumed yet: Kit is mid-turn
    release();
    await until(() => calls.some((c) => c.agent === 'kit' && /please also mention emoji tags/.test(c.prompt)), 20_000);
    await until(() => h.events.some((e) => e.type === 'agent.say' && e.agentId === 'kit' && e.text === 'Will do.'));
    expect(fm.bus.inbox('kit').length).toBe(0);
    await fm.close();
  });

  it("the next worker is told it takes over, with Reviewer's earlier answers (so it does not ask again)", async () => {
    const calls: Call[] = [];
    let stateAfterAnswer: { state: string; station: string } | undefined;
    const kit: Script = async function* (o, _p, aborted) {
      yield init(sid(2));
      // the real stream mapper shows the agent at the user before the tool runs
      h.fm.setAgent('kit', { state: 'waiting_user', station: 'user' });
      await callTool(o, 'ask_user', { question: 'Short or long output?', options: ['Short', 'Long'] });
      const a = h.fm.agent('kit')!;
      stateAfterAnswer = { state: a.state, station: a.station };
      fs.writeFileSync(path.join(o.cwd!, 'KIT_PARTIAL.md'), 'half-done by Kit\n');
      await aborted.catch(() => undefined); // still working when the user stops Kit
      yield ok(sid(2));
    };
    const { h, errors } = await boot('kit,juniper', calls, { kit, juniper: juniperFinishes });
    const fm = h.fm;
    await fm.submitGoal('version flag');
    await until(() => fm.decisions.open().some((d) => d.kind === 'question' && d.agentId === 'kit'));
    await fm.answerDecision(fm.decisions.open().find((d) => d.kind === 'question')!.id, 'Long', 'with the package name');
    await until(() => fs.existsSync(path.join(fm.repos.findWorktree('demo-app', 'kit-t1')!.path, 'KIT_PARTIAL.md')));
    expect(stateAfterAnswer).toEqual({ state: 'thinking', station: 'desk' });
    await fm.agentAction('kit', 'stop');
    await until(() => calls.some((c) => c.agent === 'juniper'), 30_000);
    const jp = calls.find((c) => c.agent === 'juniper')!.prompt;
    expect(jp).toMatch(/You take over this task from Kit/);
    expect(jp).toMatch(/Kit asked: "Short or long output\?" -> Reviewer: Long - with the package name/);
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 30_000);
    // the lead's review knows the history too (seen for real: it blamed Juniper for not asking)
    const review = calls.find((c) => c.agent === 'marlow' && c.prompt.startsWith('Review request: t1'))!.prompt;
    expect(review).toMatch(/Worked on by Kit, then Juniper/);
    expect(review).toMatch(/Reviewer already answered these questions on this task/);
    expect(errors).toEqual([]);
    await fm.close();
  });

  it('a task that changed no files is closed as done instead of asking for an empty merge', async () => {
    const calls: Call[] = [];
    const kit: Script = async function* (o) {
      yield init(sid(2));
      await callTool(o, 'send_message', { to: 'user', text: 'npm outdated: everything is up to date.' });
      await callTool(o, 'update_task', { task_id: 't1', status: 'review', summary: 'report only: up to date' });
      yield ok(sid(2));
    };
    const { h, errors } = await boot('kit', calls, { kit });
    const fm = h.fm;
    await fm.submitGoal('check outdated deps');
    await until(() => fm.tasks.get('t1')?.status === 'done', 30_000);
    expect(fm.decisions.list().filter((d) => d.kind === 'merge')).toEqual([]);
    expect(fm.goal('g1')!.status).toBe('done');
    expect(fm.repos.findWorktree('demo-app', 'kit-t1')!.status).toBe('abandoned');
    expect(h.events.some((e) => e.type === 'feed.add' && /changed no files/.test(e.item.text))).toBe(true);
    expect(errors).toEqual([]);
    await fm.close();
  });

  it('Merge on a branch without changes closes the task as done; a goal whose tasks were all cancelled is closed', async () => {
    const { h } = await boot('kit', [], {});
    const fm = h.fm;
    // Merge on an empty branch
    const g = fm.createGoal('report');
    fm.setGoal(g.id, { status: 'active' });
    const t = fm.tasks.create({ title: 'Report only', createdBy: 'marlow', repoId: 'demo-app', assignee: 'kit', goalId: g.id });
    const wt = await fm.repos.createWorktree('demo-app', 'kit', t);
    fm.tasks.update(t.id, { worktree: wt.id });
    fm.tasks.setStatus(t.id, 'doing');
    fm.tasks.setStatus(t.id, 'review');
    const d = fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Merge?', options: ['Merge', 'Request changes', 'Reject'], repoId: 'demo-app', worktree: wt.id, taskId: t.id });
    await fm.answerDecision(d.id, 'Merge');
    expect(fm.decisions.get(d.id)!.status).toBe('answered');
    expect(fm.tasks.get(t.id)!.status).toBe('done');
    await until(() => fm.goal(g.id)!.status === 'done');
    // every task cancelled -> the goal is closed, not stuck at 0%; a new task re-opens it
    const g2 = fm.createGoal('to be cancelled');
    fm.setGoal(g2.id, { status: 'active' });
    const t2 = fm.tasks.create({ title: 'x', createdBy: 'marlow', goalId: g2.id });
    fm.taskAction(t2.id, 'cancel');
    await until(() => fm.goal(g2.id)!.status === 'cancelled');
    fm.tasks.create({ title: 'y', createdBy: 'marlow', goalId: g2.id });
    await until(() => fm.goal(g2.id)!.status === 'active');
    await fm.close();
  });

  it('the scheduler retries by itself after an error', async () => {
    const calls: Call[] = [];
    const kit: Script = async function* (o) {
      yield init(sid(2));
      fs.appendFileSync(path.join(o.cwd!, 'README.md'), '\nversion flag (Kit)\n');
      await callTool(o, 'update_task', { task_id: 't1', status: 'review', summary: 'done' });
      yield ok(sid(2));
    };
    const { h, errors } = await boot('kit', calls, { kit });
    const fm = h.fm;
    const orig = fm.repos.createWorktree.bind(fm.repos);
    let failures = 0;
    fm.repos.createWorktree = (async (...args: Parameters<typeof orig>) => {
      if (failures++ === 0) throw new Error('EBUSY: resource busy or locked (simulated)');
      return orig(...args);
    }) as typeof orig;
    await fm.submitGoal('version flag');
    await until(() => fm.decisions.open().some((d) => d.kind === 'merge' && d.taskId === 't1'), 30_000);
    expect(errors.some((e) => /could not start t1 for kit: EBUSY/.test(e))).toBe(true);
    expect(fm.tasks.get('t1')!.worktree).toBe('kit-t1');
    await fm.close();
  });
});
