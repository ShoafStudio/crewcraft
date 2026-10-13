// A planning turn that deliberately creates no tasks (the user said "ignore it", or the goal needs no
// code) closes the goal instead of leaving it "active" at 0% on the HUD forever.
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const S = '00000000-0000-4000-8000-000000000001';
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: S, ...o }) as unknown as SDKMessage;

function fakeQuery() {
  return ({ options }: { prompt: unknown; options?: Options }) => {
    void options;
    async function* run(): AsyncGenerator<SDKMessage> {
      yield m({ type: 'system', subtype: 'init', session_id: S, model: 'fake', cwd: '', tools: [] });
      yield m({ type: 'result', subtype: 'success', is_error: false, result: 'Nothing to do.', num_turns: 1, total_cost_usd: 0.01, session_id: S, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

let h: Harness;
let home: string;
let repoPath: string;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath]);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery() as never, skipAuthCheck: true }));
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('claude backend: a plan without tasks', () => {
  it('closes the goal, and a task added later makes it active again', async () => {
    const goal = await h.fm.submitGoal('C:/Program Files/Git/status');
    await until(() => h.fm.goal(goal.id)!.status !== 'planning');
    expect(h.fm.goal(goal.id)!.status).toBe('cancelled');
    expect(h.fm.store.data.feed.some((f) => /planned no tasks: goal closed/.test(f.text))).toBe(true);
    h.fm.tasks.create({ title: 'Follow-up after all', createdBy: 'marlow', repoId: 'demo-app', goalId: goal.id });
    await until(() => h.fm.goal(goal.id)!.status === 'active');
  });
});
