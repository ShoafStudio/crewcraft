// Map tool use -> agent state / station / nameplate activity. Shared by the sim and claude
// backends so both drive the world the same way.
//
//   Read / Grep / Glob / LS       -> reading      @ library
//   Edit / Write / MultiEdit      -> editing      @ desk
//   Bash (test commands)          -> testing      @ testbench
//   Bash (anything else)          -> running      @ terminal
//   ask_user                      -> waiting_user @ user
//   memory tools                  -> reading      @ library
//   send_message                  -> (unchanged state) @ meeting
import path from 'node:path';
import type { AgentState, Station } from '../protocol.js';

export interface Activity {
  state: AgentState;
  station: Station;
  activity: string;
  /** one-line log label, e.g. "Read src/cli.ts" */
  label: string;
}

export const TEST_CMD_RE =
  /(^|[\s;&|(])((npm|pnpm|yarn|bun)\s+(run\s+)?test\b|npx\s+(vitest|jest|mocha)\b|vitest\b|jest\b|pytest\b|go\s+test\b|cargo\s+test\b|node\s+--test\b|dotnet\s+test\b|mvn\s+test\b|gradlew?\s+test\b|python\s+-m\s+(pytest|unittest)\b|tsc\b.*--noEmit)/;

function short(p: string): string {
  const parts = p.replace(/\\/g, '/').split('/').filter(Boolean);
  return parts.slice(-2).join('/') || p;
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.trim() ? v : undefined;
}

/** Shorten an absolute path to be relative to `cwd` when inside it. */
export function relPath(p: string, cwd?: string): string {
  if (!cwd) return p;
  const rel = path.relative(cwd, p);
  if (rel && !rel.startsWith('..') && !path.isAbsolute(rel)) return rel.replace(/\\/g, '/');
  return p;
}

export function toolActivity(tool: string, input: Record<string, unknown>, cwd?: string): Activity {
  const name = tool.startsWith('mcp__') ? tool.split('__').pop()! : tool;
  const file = str(input.file_path) ?? str(input.path) ?? str(input.notebook_path);
  const rel = file ? relPath(file, cwd) : undefined;
  switch (name) {
    case 'Read':
    case 'NotebookRead':
      return { state: 'reading', station: 'library', activity: `reading ${short(rel ?? 'files')}`, label: `Read ${rel ?? ''}`.trim() };
    case 'Grep': {
      const pat = str(input.pattern) ?? '';
      return { state: 'reading', station: 'library', activity: `searching "${pat.slice(0, 24)}"`, label: `Grep "${pat}"${rel ? ` in ${rel}` : ''}` };
    }
    case 'Glob':
    case 'LS': {
      const pat = str(input.pattern) ?? rel ?? '.';
      return { state: 'reading', station: 'library', activity: `browsing ${pat.slice(0, 30)}`, label: `${name} ${pat}` };
    }
    case 'Edit':
    case 'MultiEdit':
    case 'NotebookEdit':
      return { state: 'editing', station: 'desk', activity: `editing ${short(rel ?? 'files')}`, label: `Edit ${rel ?? ''}`.trim() };
    case 'Write':
      return { state: 'editing', station: 'desk', activity: `writing ${short(rel ?? 'a file')}`, label: `Write ${rel ?? ''}`.trim() };
    case 'Bash':
    case 'PowerShell': {
      const cmd = (str(input.command) ?? '').replace(/\s+/g, ' ').trim();
      if (TEST_CMD_RE.test(cmd)) return { state: 'testing', station: 'testbench', activity: `testing: ${cmd.slice(0, 32)}`, label: `$ ${cmd}` };
      return { state: 'running', station: 'terminal', activity: `$ ${cmd.slice(0, 40)}`, label: `$ ${cmd}` };
    }
    case 'WebFetch':
    case 'WebSearch':
      return { state: 'reading', station: 'library', activity: name === 'WebFetch' ? 'reading the web' : 'searching the web', label: `${name} ${str(input.url) ?? str(input.query) ?? ''}`.trim() };
    case 'TodoWrite':
      return { state: 'thinking', station: 'desk', activity: 'planning steps', label: 'TodoWrite' };
    case 'ask_user':
      return { state: 'waiting_user', station: 'user', activity: 'waiting for you', label: `ask_user: ${str(input.question) ?? ''}` };
    case 'send_message':
      return { state: 'thinking', station: 'meeting', activity: `messaging ${str(input.to) ?? 'team'}`, label: `send_message -> ${str(input.to) ?? '?'}` };
    case 'write_memory':
      return { state: 'editing', station: 'library', activity: `writing memory`, label: `write_memory "${str(input.title) ?? ''}"` };
    case 'read_memory':
      return { state: 'reading', station: 'library', activity: 'reading memory', label: `read_memory ${str(input.id) ?? str(input.query) ?? ''}`.trim() };
    case 'create_task':
      return { state: 'thinking', station: 'meeting', activity: 'planning tasks', label: `create_task "${str(input.title) ?? ''}"` };
    case 'update_task':
      return { state: 'thinking', station: 'desk', activity: `updating ${str(input.task_id) ?? 'task'}`, label: `update_task ${str(input.task_id) ?? ''} ${str(input.status) ?? ''}`.trim() };
    case 'request_merge':
      return { state: 'thinking', station: 'mergestation', activity: 'preparing merge review', label: `request_merge ${str(input.task_id) ?? ''}` };
    case 'report_status':
      return { state: 'thinking', station: 'desk', activity: str(input.activity)?.slice(0, 40) ?? 'reporting', label: `report_status ${str(input.activity) ?? ''}`.trim() };
    case 'list_tasks':
      return { state: 'reading', station: 'meeting', activity: 'checking the task wall', label: 'list_tasks' };
    default:
      return { state: 'running', station: 'terminal', activity: name.slice(0, 40), label: name };
  }
}
