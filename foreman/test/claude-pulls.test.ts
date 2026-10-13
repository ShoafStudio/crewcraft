// A goal that mentions pull requests: the Foreman fetches them before the lead plans, the lead's
// plan prompt carries the PR briefs, the briefs land in shared memory, and the feed names each PR.
// A goal without PR references never touches the fetcher.
import path from 'node:path';
import type { Options, SDKMessage } from '@anthropic-ai/claude-agent-sdk';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { ClaudeBackend, type PullFetcher } from '../src/agents/claude/index.js';
import type { PullRequest } from '../src/pulls.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

const S = '00000000-0000-4000-8000-000000000002';
const m = (o: Record<string, unknown>) => ({ parent_tool_use_id: null, uuid: S, ...o }) as unknown as SDKMessage;
const prompts: string[] = [];

function fakeQuery() {
  return ({ prompt, options }: { prompt: unknown; options?: Options }) => {
    void options;
    if (typeof prompt === 'string') prompts.push(prompt);
    async function* run(): AsyncGenerator<SDKMessage> {
      yield m({ type: 'system', subtype: 'init', session_id: S, model: 'fake', cwd: '', tools: [] });
      yield m({ type: 'result', subtype: 'success', is_error: false, result: 'ok', num_turns: 1, total_cost_usd: 0.01, session_id: S, duration_ms: 1, duration_api_ms: 1, usage: {}, modelUsage: {}, permission_denials: [] });
    }
    return Object.assign(run(), { close() {}, accountInfo: async () => ({ email: 'x' }) });
  };
}

const pr: PullRequest = { number: 19, title: 'fix(foreman): --goal is ignored', author: 'petrock', url: 'https://github.com/o/r/pull/19', body: 'Fixes the goal flag.', branch: 'agentcraft/pr-19', headSha: 'abc', files: 2, additions: 11, deletions: 3 };
const fetched: number[][] = [];
const fetcher: PullFetcher = {
  origin: async () => 'https://github.com/o/r.git',
  fetch: async (_p, numbers) => {
    fetched.push(numbers);
    return { pulls: numbers.includes(19) ? [pr] : [], errors: numbers.includes(99) ? ['#99: not found'] : [] };
  },
};

let h: Harness;
let home: string;
let repoPath: string;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit', '--repo', repoPath]);
  await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: fakeQuery() as never, skipAuthCheck: true, pullFetcher: fetcher }));
});

afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('claude backend: pull request intake', () => {
  it('fetches the PRs a goal mentions and briefs the lead', async () => {
    await h.fm.submitGoal('Review and merge the open PRs #19 and #99');
    await until(() => prompts.some((p) => p.startsWith('New goal')));
    expect(fetched).toEqual([[19, 99]]);
    const plan = prompts.find((p) => p.startsWith('New goal'))!;
    expect(plan).toContain('PR #19 "fix(foreman): --goal is ignored" by @petrock');
    expect(plan).toContain('start_branch');
    expect(h.fm.memory.list().some((e) => /^Pull requests for/.test(e.title) && e.body.includes('agentcraft/pr-19'))).toBe(true);
    expect(h.fm.store.data.feed.some((f) => f.text === 'PR #19 by @petrock: fix(foreman): --goal is ignored')).toBe(true);
    expect(h.fm.store.data.feed.some((f) => f.kind === 'error' && f.text === 'PR #99: not found')).toBe(true);
  });

  it('a goal without PR references does not fetch', async () => {
    const before = fetched.length;
    prompts.length = 0;
    await h.fm.submitGoal('Add a stats command');
    await until(() => prompts.some((p) => p.startsWith('New goal')));
    expect(fetched.length).toBe(before);
    expect(prompts.find((p) => p.startsWith('New goal'))).not.toContain('Pull requests the Foreman fetched');
  });
});
