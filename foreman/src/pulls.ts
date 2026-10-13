// Pull request intake: a goal that mentions "#12" (or "PR 12") on a repository whose origin is on
// GitHub gets those pull requests fetched by the Foreman before the lead plans. Each PR's head
// lands on a local branch `agentcraft/pr-<n>`; the lead turns PRs into tasks that start from that
// branch, so workers review the contributor's commits, fix on top, and an approved merge brings
// the contributor's commits into the base branch (GitHub marks the PR merged once the user pushes).
//
// This is the one place the Foreman talks to a remote, and only when the user's goal asks for it:
// agents still have no git network access (gitsafety.ts), and nothing is ever pushed.
import os from 'node:os';
import path from 'node:path';
import { GIT_REDIRECT_VARS } from './gitsafety.js';
import { run, type RunResult } from './util/proc.js';

export interface PullRequest {
  number: number;
  title: string;
  author: string;
  url: string;
  body: string;
  /** local branch holding the PR head */
  branch: string;
  headSha: string;
  files: number;
  additions: number;
  deletions: number;
}

/** "#12", "PR 12", "pull/12" in a goal; at most 30 distinct numbers. */
export function prRefs(text: string): number[] {
  const out = new Set<number>();
  for (const m of text.matchAll(/(?:#|\bPRs?\s*#?|\bpull\/)(\d{1,6})\b/gi)) {
    const n = Number(m[1]);
    if (n > 0) out.add(n);
    if (out.size >= 30) break;
  }
  return [...out];
}

export const prBranch = (n: number): string => `agentcraft/pr-${n}`;
export const isPrBranch = (b: string): boolean => /^agentcraft\/pr-\d+$/.test(b);

export type Runner = (cmd: string, args: string[], opts: { cwd: string; env: NodeJS.ProcessEnv; timeoutMs: number }) => Promise<RunResult>;

const NO_HOOKS_DIR = path.join(os.tmpdir(), 'agentcraft-no-hooks-7f3e9c');

function env(): NodeJS.ProcessEnv {
  const e: NodeJS.ProcessEnv = { ...process.env, GIT_TERMINAL_PROMPT: '0', GCM_INTERACTIVE: 'never', LC_ALL: 'C' };
  for (const k of Object.keys(e)) if (GIT_REDIRECT_VARS.includes(k.toUpperCase())) delete e[k];
  return e;
}

/** Is the repository's origin a GitHub repository? */
export async function githubOrigin(repoPath: string, runner: Runner = run): Promise<string | undefined> {
  const r = await runner('git', ['remote', 'get-url', 'origin'], { cwd: repoPath, env: env(), timeoutMs: 15_000 });
  const url = r.stdout.trim();
  return r.code === 0 && /github\.com[:/]/i.test(url) ? url : undefined;
}

/**
 * Fetch the given PRs: metadata through `gh pr view` (the user's own gh login) and the head commits
 * into `agentcraft/pr-<n>` (repository hooks disabled). Returns the PRs that could be fetched and
 * an error line for each that could not.
 */
export async function fetchPulls(repoPath: string, numbers: number[], runner: Runner = run): Promise<{ pulls: PullRequest[]; errors: string[] }> {
  const pulls: PullRequest[] = [];
  const errors: string[] = [];
  for (const n of numbers) {
    const view = await runner('gh', ['pr', 'view', String(n), '--json', 'number,title,author,body,url,headRefOid,changedFiles,additions,deletions,state'], { cwd: repoPath, env: env(), timeoutMs: 60_000 });
    if (view.code !== 0) {
      errors.push(`#${n}: ${(view.stderr || view.stdout).trim().split('\n').pop() ?? 'gh pr view failed'}`);
      continue;
    }
    let meta: { number: number; title: string; author?: { login?: string }; body?: string; url: string; headRefOid: string; changedFiles?: number; additions?: number; deletions?: number; state?: string };
    try {
      meta = JSON.parse(view.stdout);
    } catch {
      errors.push(`#${n}: unreadable gh output`);
      continue;
    }
    if (meta.state && meta.state !== 'OPEN') {
      errors.push(`#${n} is ${meta.state.toLowerCase()}, skipped`);
      continue;
    }
    const branch = prBranch(n);
    const fetch = await runner('git', ['-c', `core.hooksPath=${NO_HOOKS_DIR}`, 'fetch', '--no-tags', '--quiet', 'origin', `+pull/${n}/head:refs/heads/${branch}`], { cwd: repoPath, env: env(), timeoutMs: 120_000 });
    if (fetch.code !== 0) {
      errors.push(`#${n}: git fetch failed: ${(fetch.stderr || fetch.stdout).trim().split('\n').pop()}`);
      continue;
    }
    pulls.push({
      number: meta.number,
      title: meta.title,
      author: meta.author?.login ?? 'unknown',
      url: meta.url,
      body: (meta.body ?? '').slice(0, 1500),
      branch,
      headSha: meta.headRefOid,
      files: meta.changedFiles ?? 0,
      additions: meta.additions ?? 0,
      deletions: meta.deletions ?? 0,
    });
  }
  return { pulls, errors };
}

/** The briefing the lead gets in its plan prompt. */
export function pullBriefs(pulls: PullRequest[]): string {
  return pulls
    .map((p) => `- PR #${p.number} "${p.title}" by @${p.author} (${p.files} files, +${p.additions} -${p.deletions}) ${p.url}\n  branch: ${p.branch}\n  description: ${p.body.replace(/\s+/g, ' ').slice(0, 400) || '(none)'}`)
    .join('\n');
}
