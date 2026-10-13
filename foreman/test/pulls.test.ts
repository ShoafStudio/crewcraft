// Pull request intake: refs in a goal, fetching pull/<n>/head into agentcraft/pr-<n> (real git, a
// fake gh), a worker worktree that starts from the contributor's commits, and a merge that keeps them.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { fetchPulls, githubOrigin, isPrBranch, prBranch, prRefs, pullBriefs, type Runner } from '../src/pulls.js';
import { MERGE_OPTIONS } from '../src/protocol.js';
import { run } from '../src/util/proc.js';
import { makeForeman, rmrf, tempDir } from './helpers.js';

const sh = (cwd: string, args: string[]) => {
  const r = spawnSync('git', ['-c', 'user.name=T', '-c', 'user.email=t@x', '-c', 'commit.gpgsign=false', ...args], { cwd, encoding: 'utf8' });
  if (r.status !== 0) throw new Error(`git ${args.join(' ')}: ${r.stderr}`);
  return r.stdout.trim();
};

describe('prRefs', () => {
  it('finds #n, PR n and pull/n, de-duplicated', () => {
    expect(prRefs('Review and merge #19, #17 and PR 12, then pull/7 (and #19 again)')).toEqual([19, 17, 12, 7]);
    expect(prRefs('Add #tags to pocket-notes')).toEqual([]);
    expect(prRefs('no refs here')).toEqual([]);
  });
  it('branch names', () => {
    expect(prBranch(12)).toBe('agentcraft/pr-12');
    expect(isPrBranch('agentcraft/pr-12')).toBe(true);
    expect(isPrBranch('main')).toBe(false);
    expect(isPrBranch('agentcraft/kit/t1-x')).toBe(false);
  });
});

describe('fetchPulls (real git, fake gh)', () => {
  const base = tempDir('ac-pulls-');
  const home = tempDir('ac-pulls-home-');
  afterAll(() => {
    rmrf(base);
    rmrf(home);
  });

  // origin = a bare repo with main and a PR head under refs/pull/7/head (how GitHub exposes PRs)
  const origin = path.join(base, 'origin.git');
  const seed = path.join(base, 'seed');
  const repo = path.join(base, 'repo');
  fs.mkdirSync(seed, { recursive: true });
  sh(base, ['init', '-q', '--bare', '-b', 'main', origin]);
  sh(seed, ['init', '-q', '-b', 'main']);
  fs.writeFileSync(path.join(seed, 'README.md'), 'hello\n');
  sh(seed, ['add', '.']);
  sh(seed, ['commit', '-q', '-m', 'init']);
  sh(seed, ['remote', 'add', 'origin', origin]);
  sh(seed, ['push', '-q', 'origin', 'main']);
  sh(seed, ['checkout', '-q', '-b', 'contrib']);
  fs.writeFileSync(path.join(seed, 'feature.txt'), 'from a contributor\n');
  sh(seed, ['add', '.']);
  sh(seed, ['commit', '-q', '-m', 'Add feature (contributor)']);
  const prSha = sh(seed, ['rev-parse', 'HEAD']);
  sh(seed, ['push', '-q', 'origin', 'HEAD:refs/pull/7/head']);
  sh(base, ['clone', '-q', origin, repo]);

  const meta = (n: number, state = 'OPEN') =>
    JSON.stringify({ number: n, title: `Feature ${n}`, author: { login: 'contributor' }, body: 'Adds a feature.', url: `https://github.com/o/r/pull/${n}`, headRefOid: prSha, changedFiles: 1, additions: 1, deletions: 0, state });
  const runner: Runner = async (cmd, args, opts) => {
    if (cmd === 'gh') {
      const n = Number(args[2]);
      if (n === 7) return { code: 0, stdout: meta(7), stderr: '', timedOut: false };
      if (n === 8) return { code: 0, stdout: meta(8, 'MERGED'), stderr: '', timedOut: false };
      return { code: 1, stdout: '', stderr: 'GraphQL: Could not resolve to a PullRequest', timedOut: false };
    }
    return run(cmd, args, opts);
  };

  it('fetches open PRs into agentcraft/pr-<n> and reports the rest', async () => {
    const { pulls, errors } = await fetchPulls(repo, [7, 8, 9], runner);
    expect(pulls.map((p) => p.number)).toEqual([7]);
    expect(pulls[0]).toMatchObject({ author: 'contributor', branch: 'agentcraft/pr-7', headSha: prSha });
    expect(sh(repo, ['rev-parse', 'refs/heads/agentcraft/pr-7'])).toBe(prSha);
    expect(errors.some((e) => e.startsWith('#8') && /merged/.test(e))).toBe(true);
    expect(errors.some((e) => e.startsWith('#9'))).toBe(true);
    expect(pullBriefs(pulls)).toContain('PR #7 "Feature 7" by @contributor');
  });

  it('githubOrigin only accepts GitHub remotes', async () => {
    expect(await githubOrigin(repo)).toBeUndefined(); // a local path
    const fake: Runner = async () => ({ code: 0, stdout: 'git@github.com:blendi-remade/agentcraft.git\n', stderr: '', timedOut: false });
    expect(await githubOrigin(repo, fake)).toBe('git@github.com:blendi-remade/agentcraft.git');
  });

  it('a worktree started from the PR branch carries the contributor commit through an approved merge', async () => {
    const h = makeForeman(home);
    try {
      const r = await h.fm.repos.add(repo);
      const wt = await h.fm.repos.createWorktree(r.id, 'kit', { id: 't1', title: 'PR #7: Feature 7' }, { startPoint: 'agentcraft/pr-7' });
      expect(sh(wt.path, ['log', '-1', '--format=%s'])).toBe('Add feature (contributor)');
      // the worker adds a small fix on top
      fs.writeFileSync(path.join(wt.path, 'feature.txt'), 'from a contributor\nwith a fix\n');
      await h.fm.repos.commitAll(r.id, wt.id, 'Fix feature');
      const d = h.fm.createDecision({ agentId: 'marlow', kind: 'merge', question: `Merge ${wt.id}?`, options: [...MERGE_OPTIONS], repoId: r.id, worktree: wt.id });
      h.fm.decisions.answer(d.id, 'Merge');
      await h.fm.repos.merge(h.fm.decisions.get(d.id)!);
      // the contributor's commit is now reachable from main (GitHub marks the PR merged on push)
      const ok = spawnSync('git', ['merge-base', '--is-ancestor', prSha, 'main'], { cwd: repo }).status === 0;
      expect(ok).toBe(true);
    } finally {
      await h.fm.close();
    }
  }, 60_000);
});
