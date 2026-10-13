// The claude spend shown in-game (foreman.status.costUsd) is the profile's total, not "since this
// Foreman started": sessions persist their cost, so a restart starts from their sum.
import { afterEach, describe, expect, it } from 'vitest';
import { ClaudeBackend } from '../src/agents/claude/index.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness | undefined;
let home: string | undefined;
afterEach(async () => {
  await h?.fm.close();
  if (home) rmrf(home);
  h = undefined;
  home = undefined;
});

const noQuery = () => {
  throw new Error('no turns in this test');
};

describe('claude spend across restarts', () => {
  it('starts from the sum of the persisted session costs', async () => {
    home = tempDir();
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit']);
    h.fm.store.data.sessions['marlow:g1'] = { sessionId: 'a', turns: 3, costUsd: 0.933, updatedAt: 1 };
    h.fm.store.data.sessions['kit:t1'] = { sessionId: 'b', turns: 1, costUsd: 1.003, updatedAt: 1 };
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: noQuery as never, skipAuthCheck: true }));
    expect(h.fm.status.costUsd).toBe(1.936);
  });

  it('a fresh profile has no spend yet', async () => {
    home = tempDir();
    h = makeForeman(home, ['--backend', 'claude', '--workers', 'kit']);
    await h.fm.start(new ClaudeBackend(h.fm, h.cfg.claude, { queryFn: noQuery as never, skipAuthCheck: true }));
    expect(h.fm.status.costUsd ?? 0).toBe(0);
  });
});
