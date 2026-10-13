// The Foreman's own git calls (worktree add, commitAll) must never run the user's repository hooks,
// and nothing the Foreman does may push. Regression for the phase-1 verifier finding where a
// post-commit hook in the user's repo pushed during the Foreman's commitAll.
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { makeForeman, rmrf, tempDir } from './helpers.js';

const sh = (cwd: string, args: string[]) => spawnSync('git', args, { cwd, encoding: 'utf8' });

describe('Foreman git calls ignore repository hooks', () => {
  const base = tempDir('ac-hooks-');
  const home = tempDir('ac-hooks-home-');
  afterAll(() => {
    rmrf(base);
    rmrf(home);
  });

  it('commitAll and createWorktree run no hooks and push nothing', async () => {
    const repo = path.join(base, 'repo');
    const bare = path.join(base, 'remote.git');
    const marker = path.join(base, 'marker.txt').replace(/\\/g, '/');
    fs.mkdirSync(repo, { recursive: true });
    sh(base, ['init', '-q', '--bare', bare]);
    sh(repo, ['init', '-q', '-b', 'main']);
    fs.writeFileSync(path.join(repo, 'README.md'), 'hi\n');
    sh(repo, ['add', '.']);
    sh(repo, ['-c', 'user.name=T', '-c', 'user.email=t@x', 'commit', '-q', '-m', 'init']);
    sh(repo, ['remote', 'add', 'origin', bare]);
    for (const name of ['post-commit', 'post-checkout', 'reference-transaction']) {
      fs.writeFileSync(
        path.join(repo, '.git', 'hooks', name),
        `#!/bin/sh\necho "${name}" >> "${marker}"\ngit push -q origin HEAD 2>/dev/null && echo pushed >> "${marker}"\nexit 0\n`,
        { mode: 0o755 },
      );
    }

    const h = makeForeman(home);
    try {
      const r = await h.fm.repos.add(repo);
      const w = await h.fm.repos.createWorktree(r.id, 'kit', { id: 't1', title: 'Hook test' });
      fs.writeFileSync(path.join(w.path, 'b.txt'), 'work\n');
      expect(await h.fm.repos.commitAll(r.id, w.id, 'agentcraft: t1')).toBe(true);
      await h.fm.repos.abandon(r.id, w.id);
    } finally {
      await h.fm.close();
    }

    expect(fs.existsSync(marker) ? fs.readFileSync(marker, 'utf8') : '').toBe('');
    expect(sh(bare, ['for-each-ref', '--format=%(refname)']).stdout.trim()).toBe('');
  }, 60_000);
});
