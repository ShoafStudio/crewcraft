// Git safety environment for every process an agent can influence: the agent's Claude CLI
// process (and so every Bash command it runs) and the Foreman's CI runs in worktrees.
//
// The permission policy (policy.ts) refuses `git push` when it can see it. This module makes git
// itself refuse, so a push hidden where a parser cannot look — an npm test script, a node/python
// script, a git alias — still fails:
//
//   GIT_ALLOW_PROTOCOL=agentcraft-none   no git transport at all (file, ssh, https, git, ext)
//                                         -> push, send-pack, fetch, clone, ls-remote all fail
//   protocol.allow=never                  same, via env-scoped config (if the variable is removed)
//   url.<dead>.pushInsteadOf=""           every push URL is rewritten to a URL git cannot use
//
// It also keeps agents' git away from the user's identity and repositories:
//
//   commit.gpgsign=false, tag.gpgsign=false  agents' commits are never signed with the user's key
//   gpg[.ssh|.x509].program=<missing>        and an explicit `-S` fails instead of signing
//   GIT_CEILING_DIRECTORIES=<parent of cwd>  git never walks up out of the agent's worktree: if
//                                            its `.git` link is removed, git stops instead of
//                                            finding an enclosing repository
//   GIT_DIR, GIT_WORK_TREE, GIT_INDEX_FILE, ... inherited from the Foreman's own environment
//                                            are removed (they would point git elsewhere)
//
// Env-scoped config (GIT_CONFIG_COUNT/KEY/VALUE) has a higher precedence than the repository's
// own .git/config and the user's global config, so neither can re-enable pushes or signing. Agents
// do not need git network access: the Foreman does every git operation they rely on (worktrees,
// diffs, merges) itself, locally. The Foreman's own git calls (util/git.ts) do not use this
// environment. The agents' git identity ("AgentCraft Kit <kit@agentcraft.local>") is set by the
// claude backend (agentEnv).
//
// Not a sandbox: code an agent runs can still unset these variables on purpose. The policy
// denies commands that mention them; together that covers mistakes and overeager agents.

export const PUSH_BLOCK_URL = 'agentcraft-push-blocked:///';
/** a program name that does not exist: any signing attempt by an agent fails */
export const NO_SIGNING_PROGRAM = 'agentcraft-signing-disabled';

/** Names an agent command must never touch (policy.ts denies commands that mention them). */
export const GIT_SAFETY_VARS = ['GIT_ALLOW_PROTOCOL', 'GIT_CONFIG_COUNT', 'GIT_CONFIG_KEY_', 'GIT_CONFIG_VALUE_', 'GIT_CONFIG_PARAMETERS', 'GIT_CONFIG_NOSYSTEM', 'GIT_CONFIG_GLOBAL', 'GIT_CONFIG_SYSTEM', 'GIT_PROTOCOL_FROM_USER', 'GIT_CEILING_DIRECTORIES'];

/**
 * Variables that point git at another repository, work tree or index. Removed from agents'
 * inherited environment; policy.ts asks before any command that mentions them.
 */
export const GIT_REDIRECT_VARS = ['GIT_DIR', 'GIT_WORK_TREE', 'GIT_INDEX_FILE', 'GIT_COMMON_DIR', 'GIT_OBJECT_DIRECTORY', 'GIT_ALTERNATE_OBJECT_DIRECTORIES', 'GIT_NAMESPACE', 'GIT_DISCOVERY_ACROSS_FILESYSTEM', 'GIT_CONFIG', 'GIT_REPLACE_REF_BASE', 'GIT_SHALLOW_FILE', 'GIT_GRAFT_FILE'];

export interface GitSafetyOptions {
  /** git must not walk up into this directory (or above) looking for a repository */
  ceiling?: string;
}

/**
 * Variables to merge over `base` (usually process.env). Existing env-scoped config entries in
 * `base` are kept: ours are appended after them.
 */
export function gitSafetyEnv(base: NodeJS.ProcessEnv = process.env, opts: GitSafetyOptions = {}): Record<string, string> {
  const start = Math.max(0, Number(base.GIT_CONFIG_COUNT) || 0);
  const pairs: Array<[string, string]> = [
    ['protocol.allow', 'never'],
    [`url.${PUSH_BLOCK_URL}.pushInsteadOf`, ''],
    ['commit.gpgsign', 'false'],
    ['tag.gpgsign', 'false'],
    ['gpg.program', NO_SIGNING_PROGRAM],
    ['gpg.ssh.program', NO_SIGNING_PROGRAM],
    ['gpg.x509.program', NO_SIGNING_PROGRAM],
  ];
  const env: Record<string, string> = {
    GIT_ALLOW_PROTOCOL: 'agentcraft-none',
    GIT_CONFIG_COUNT: String(start + pairs.length),
    // never block on a credential prompt or pop a Git Credential Manager window
    GIT_TERMINAL_PROMPT: '0',
    GCM_INTERACTIVE: 'never',
  };
  pairs.forEach(([k, v], i) => {
    env[`GIT_CONFIG_KEY_${start + i}`] = k;
    env[`GIT_CONFIG_VALUE_${start + i}`] = v;
  });
  if (opts.ceiling) {
    const sep = process.platform === 'win32' ? ';' : ':';
    env.GIT_CEILING_DIRECTORIES = [opts.ceiling, base.GIT_CEILING_DIRECTORIES].filter(Boolean).join(sep);
  }
  return env;
}

/** process.env (or `base`) with the git safety variables applied (and git redirects removed). */
export function withGitSafety(base: NodeJS.ProcessEnv = process.env, extra: Record<string, string> = {}, opts: GitSafetyOptions = {}): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...base };
  for (const k of Object.keys(env)) if (GIT_REDIRECT_VARS.includes(k.toUpperCase())) delete env[k];
  return { ...env, ...gitSafetyEnv(base, opts), ...extra };
}
