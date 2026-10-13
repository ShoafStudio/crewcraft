// Messages to a worker with no task: a worker only reads messages while it works on a task, so a
// request sent to an idle worker would silently never happen (found in the real-claude e2e: the
// user asked idle Kit to run a command, Kit handed it to Marlow, Marlow "passed it on" to Kit with
// send_message, and nothing happened). The lead is told to create a task instead.
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until } from './helpers.js';

type ToolServer = { instance: { _registeredTools: Record<string, { handler: (a: unknown, e: unknown) => Promise<{ content: Array<{ text: string }> }> }> } };
const tools = (o: Options) => (o.mcpServers!.agentcraft as unknown as ToolServer).instance._registeredTools;
const callTool = async (o: Options, name: string, args: Record<string, unknown>) => (await tools(o)[name]!.handler(args, {})).content.map((c) => c.text).join('\n');
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: '00000000-0000-4000-8000-000000000000', ...o }) as unknown as SDKMessage;
const sid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const init = (s: string) => m({ type: 'system', subtype: 'init', session_id: s, model: 'fake' });
const ok = (s: string) => m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0.001, session_id: s, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });

const cleanup: string[] = [];
afterAll(() => cleanup.forEach(rmrf));

describe('claude backend: messages to idle workers', () => {
  it('tells the lead that an idle worker will not read a message, and to create a task instead', async () => {
    const leadPrompts: string[] = [];
    const results: Record<string, string> = {};
    let leadOpts: Options | undefined;
    const queryFn = ({ prompt, options }: { prompt: string; options: Options }) => {
      const p = String(prompt);
      const lead = 'create_task' in tools(options);
      async function* run(): AsyncGenerator<SDKMessage> {
        if (lead) {
          leadOpts = options;
          leadPrompts.push(p);
          yield init(sid(1));
          if (p.startsWith('New goal')) {
            await callTool(options, 'create_task', { title: 'Add a version flag', description: 'x', assignee: 'kit' });
            results.toIdle = await callTool(options, 'send_message', { to: 'juniper', text: 'stand by' });
            results.toAll = await callTool(options, 'send_message', { to: 'all', text: 'plan is up' });
          }
          yield ok(sid(1));
          return;
        }
        // Kit, on t1: a message from the lead now reaches it (it is on a task), no hint
        yield init(sid(2));
        results.toBusy = await callTool(leadOpts!, 'send_message', { to: 'kit', text: 'keep it small' });
        await callTool(options, 'update_task', { task_id: 't1', status: 'blocked', blocked_reason: 'test stops here' });
        yield ok(sid(2));
      }
      return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
    };

    const home = tempDir();
    const repo = await demoRepo();
    cleanup.push(home, path.dirname(repo));
    const h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit,juniper', '--repo', repo]);
    const fm = h.fm;
    await fm.start(new ClaudeBackend(fm, h.cfg.claude, { queryFn: queryFn as never, skipAuthCheck: true }));
    await fm.submitGoal('version flag');
    await until(() => !!results.toBusy && fm.tasks.get('t1')?.status === 'blocked');

    expect(results.toIdle).toMatch(/Juniper is not on a task/);
    expect(results.toIdle).toMatch(/create_task \(assignee "juniper"\)/);
    expect(results.toAll).toBe('Sent to all.');
    expect(results.toBusy).toBe('Sent to kit.');

    // the user messages idle Juniper: she hands it to Marlow, whose prompt says how to get it done
    const before = leadPrompts.length;
    await fm.handle({ v: 1, type: 'user.message', to: 'juniper', text: 'run the linter please' }, () => undefined);
    await until(() => leadPrompts.length > before);
    const p = leadPrompts[leadPrompts.length - 1]!;
    expect(p).toMatch(/run the linter please/);
    expect(p).toMatch(/Juniper is not on a task/);
    expect(p).toMatch(/create_task \(assignee "juniper"\)/);
    expect(h.events.some((e) => e.type === 'agent.say' && e.agentId === 'juniper' && /not on a task/.test(e.text))).toBe(true);
    await fm.close();
  });
});
