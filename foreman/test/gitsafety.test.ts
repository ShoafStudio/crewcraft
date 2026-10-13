// The git safety environment really stops pushes, wherever they hide (a script, node -e, an
// explicit pushurl, send-pack, a CI test script), and CI timeouts kill the whole process tree.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { gitSafetyEnv, withGitSafety } from '../src/gitsafety.js';
import { git, gitOut } from '../src/util/git.js';
import { runShell } from '../src/util/proc.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let root: string;
let remote: string;
let work: string;

const refs = async () => (await gitOut(remote, ['for-each-ref', '--format=%(refname)'])).split('\n').filter(Boolean);
const sh = (cmd: string, env?: NodeJS.ProcessEnv) => runShell(cmd, { cwd: work, env: env ?? withGitSafety(process.env), timeoutMs: 60_000 });

beforeAll(async () => {
  root = tempDir('ac-gitsafety-');
  remote = path.join(root, 'remote.git');
  work = path.join(root, 'work');
  await git(root, ['init', '-q', '--bare', remote]);
  await git(root, ['init', '-q', '-b', 'main', work]);
  fs.writeFileSync(path.join(work, 'a.txt'), 'a\n');
  await git(work, ['add', '-A']);
  await git(work, ['-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'init']);
  await git(work, ['remote', 'add', 'origin', remote.replace(/\\/g, '/')]);
});
afterAll(() => rmrf(root));

describe('git safety env', () => {
  it('control: without it, a push to the local remote works (so the checks below mean something)', async () => {
    const r = await sh('git push -q origin HEAD:refs/heads/control', { ...process.env, GIT_TERMINAL_PROMPT: '0' });
    expect(r.code).toBe(0);
    expect(await refs()).toContain('refs/heads/control');
  });

  it('git push fails in every spelling', async () => {
    const before = await refs();
    for (const cmd of [
      'git push origin HEAD:refs/heads/x1',
      'git push --no-verify origin HEAD:refs/heads/x2',
      `git push "${remote.replace(/\\/g, '/')}" HEAD:refs/heads/x3`,
      `git send-pack "${remote.replace(/\\/g, '/')}" HEAD:refs/heads/x4`,
      `node -e "require('child_process').execSync('git push origin HEAD:refs/heads/x5', { stdio: 'inherit' })"`,
    ]) {
      const r = await sh(cmd);
      expect(r.code, `${cmd}\n${r.stderr}`).not.toBe(0);
    }
    // explicit pushurl (not covered by pushInsteadOf): the transport block still holds
    await git(work, ['config', 'remote.origin.pushurl', remote.replace(/\\/g, '/')]);
    expect((await sh('git push origin HEAD:refs/heads/x6')).code).not.toBe(0);
    await git(work, ['config', '--unset', 'remote.origin.pushurl']);
    expect(await refs()).toEqual(before);
  });

  it('local git still works (status, log, commit, branch)', async () => {
    fs.writeFileSync(path.join(work, 'b.txt'), 'b\n');
    for (const cmd of ['git status --short', 'git log --oneline -1', 'git add -A', 'git -c user.name=t -c user.email=t@t commit -qm b', 'git branch topic']) {
      const r = await sh(cmd);
      expect(r.code, `${cmd}: ${r.stderr}`).toBe(0);
    }
  });

  it("keeps existing env-scoped git config entries and appends its own", () => {
    const env = gitSafetyEnv({ GIT_CONFIG_COUNT: '1', GIT_CONFIG_KEY_0: 'core.autocrlf', GIT_CONFIG_VALUE_0: 'false' });
    expect(env.GIT_CONFIG_COUNT).toBe('8');
    expect(env.GIT_CONFIG_KEY_1).toBe('protocol.allow');
    expect(env.GIT_CONFIG_KEY_3).toBe('commit.gpgsign');
    expect(env.GIT_CONFIG_KEY_0).toBeUndefined();
  });

  it("agents' commits are never signed, even when the user's config (or -S) asks for it", async () => {
    // the repo's own config signs every commit with a (fake, always-working) gpg: like the user's
    // global commit.gpgsign=true
    const signer = path.join(root, 'fake-gpg.sh');
    fs.writeFileSync(signer, ['#!/bin/sh', 'cat >/dev/null', 'echo "" >&2', 'echo "[GNUPG:] SIG_CREATED D 1 8 00 0 0" >&2', 'echo "-----BEGIN PGP SIGNATURE-----"', 'echo "ZmFrZQ=="', 'echo "-----END PGP SIGNATURE-----"', ''].join('\n'));
    fs.chmodSync(signer, 0o755);
    for (const [k, v] of [['commit.gpgsign', 'true'], ['gpg.format', 'openpgp'], ['gpg.program', signer.split(path.sep).join('/')], ['user.signingkey', 'TESTKEY']]) await git(work, ['config', '--local', k!, v!]);
    const id = '-c user.name=t -c user.email=t@t';
    try {
      // control: without the agent env the commit IS signed
      fs.writeFileSync(path.join(work, 'sign.txt'), 'control\n');
      const control = await sh(`git add -A && git ${id} commit -qm control`, { ...process.env, GIT_TERMINAL_PROMPT: '0' });
      expect(control.code, control.stderr).toBe(0);
      expect(await gitOut(work, ['cat-file', 'commit', 'HEAD'])).toMatch(/gpgsig -----BEGIN PGP SIGNATURE-----/);
      // with it: not signed
      fs.writeFileSync(path.join(work, 'sign.txt'), 'agent\n');
      const plain = await sh(`git add -A && git ${id} commit -qm unsigned`);
      expect(plain.code, plain.stderr).toBe(0);
      expect(await gitOut(work, ['cat-file', 'commit', 'HEAD'])).not.toMatch(/gpgsig/);
      // an explicit -S runs the disabled signing program and fails: nothing is committed
      fs.writeFileSync(path.join(work, 'sign.txt'), 'forced\n');
      const head = await gitOut(work, ['rev-parse', 'HEAD']);
      const forced = await sh(`git ${id} commit -qam forced -S`);
      expect(forced.code).not.toBe(0);
      expect(await gitOut(work, ['rev-parse', 'HEAD'])).toBe(head);
    } finally {
      for (const k of ['commit.gpgsign', 'gpg.format', 'gpg.program', 'user.signingkey']) await git(work, ['config', '--unset', k], { allowFail: true });
      await git(work, ['checkout', '--', 'sign.txt'], { allowFail: true });
    }
  });

  it('git does not walk up out of the agent cwd (a removed .git link does not reach an enclosing repo)', async () => {
    const inner = path.join(work, 'nested', 'wt');
    fs.mkdirSync(inner, { recursive: true });
    const env = withGitSafety(process.env, {}, { ceiling: path.dirname(inner) });
    const without = await runShell('git rev-parse --show-toplevel', { cwd: inner, env: withGitSafety(process.env), timeoutMs: 30_000 });
    expect(without.code).toBe(0); // control: git finds the enclosing repo
    const r = await runShell('git rev-parse --show-toplevel', { cwd: inner, env, timeoutMs: 30_000 });
    expect(r.code).not.toBe(0);
    expect(r.stderr).toMatch(/not a git repository/);
    // the repo itself (its own top level) still works with the ceiling at its parent
    const own = await runShell('git status --short', { cwd: work, env: withGitSafety(process.env, {}, { ceiling: path.dirname(work) }), timeoutMs: 30_000 });
    expect(own.code, own.stderr).toBe(0);
  });

  it('drops inherited GIT_DIR / GIT_WORK_TREE / GIT_INDEX_FILE', () => {
    const env = withGitSafety({ PATH: process.env.PATH, GIT_DIR: 'C:/x/.git', git_work_tree: 'C:/x', GIT_INDEX_FILE: 'C:/x/index' });
    expect(env.GIT_DIR).toBeUndefined();
    expect(env.git_work_tree).toBeUndefined();
    expect(env.GIT_INDEX_FILE).toBeUndefined();
    expect(env.PATH).toBe(process.env.PATH);
  });
});

describe('CI runs with the git safety env', () => {
  let h: Harness;
  let home: string;
  beforeAll(() => {
    home = tempDir();
    h = makeForeman(home, ['--backend', 'sim']);
  });
  afterAll(async () => {
    await h.fm.close();
    rmrf(home);
  });

  it('a git push inside the test script fails and nothing reaches the remote', async () => {
    fs.writeFileSync(path.join(work, 'package.json'), JSON.stringify({ name: 'x', scripts: { test: 'git push origin HEAD:refs/heads/ci-leak' } }));
    await git(work, ['add', '-A']);
    await git(work, ['-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'test script']);
    const r = await h.fm.repos.add(work);
    const res = await h.fm.repos.runTests(r.id);
    expect(res.pass).toBe(false);
    expect(await refs()).not.toContain('refs/heads/ci-leak');
  });

  it('a CI timeout kills the whole process tree, not just the shell', async () => {
    const script = path.join(root, 'tree.cjs');
    fs.writeFileSync(
      script,
      "const { spawn } = require('child_process');\nconst c = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 120000)'], { stdio: 'inherit' });\nconsole.log('GRANDCHILD ' + c.pid);\nsetTimeout(() => {}, 120000);\n",
    );
    const t0 = Date.now();
    const res = await runShell(`node "${script}"`, { cwd: root, timeoutMs: 2000 });
    expect(res.timedOut).toBe(true);
    expect(Date.now() - t0).toBeLessThan(20_000); // returns: the grandchild no longer holds the pipes
    const pid = Number(/GRANDCHILD (\d+)/.exec(res.stdout)?.[1]);
    expect(pid).toBeGreaterThan(0);
    await new Promise((r) => setTimeout(r, 500));
    let alive = true;
    try {
      process.kill(pid, 0);
    } catch {
      alive = false;
    }
    expect(alive).toBe(false);
  });
});
