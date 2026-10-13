// The person the team works for. Used in agent prompts ("ask <name>"), feed lines and the sim's
// dialogue, and sent to the mod in foreman.status so the UI can say it too.
// Config: --user-name / AGENTCRAFT_USER_NAME / config.json "userName"; default: the OS user name.
import os from 'node:os';

let current: string | undefined;

/** The OS account name with a capital first letter ("sam" -> "Sam"), or "the user". */
export function defaultUserName(): string {
  let raw = '';
  try {
    raw = os.userInfo().username;
  } catch {
    // no passwd entry (some containers)
  }
  raw = raw.trim().replace(/[^\p{L}\p{N} ._-]/gu, '');
  return raw ? raw[0]!.toUpperCase() + raw.slice(1) : 'the user';
}

/** Set once at Foreman start from the config. */
export function setUserName(name: string | undefined): void {
  const n = name?.trim();
  current = n ? n.slice(0, 40) : undefined;
}

export function userName(): string {
  return current ?? (process.env.AGENTCRAFT_USER_NAME?.trim() || defaultUserName());
}
