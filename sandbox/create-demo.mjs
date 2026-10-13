#!/usr/bin/env node
// (Re)creates a small but realistic git repo used by the Foreman's sim and claude backends.
//
//   node sandbox/create-demo.mjs                  -> sandbox/demo-app
//   node sandbox/create-demo.mjs --dir <path>     -> any directory (must be inside sandbox/ or the OS temp dir)
//   node sandbox/create-demo.mjs --force          -> delete and recreate if it already exists
//
// The repo is "pocket-notes": a zero-dependency TypeScript notes CLI. Node >= 22.18 runs .ts directly
// (type stripping), so `npm test` works with no install step. History is deterministic (fixed author
// and dates) so sim runs and diffs are reproducible.
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));

function parseArgs(argv) {
  const out = { dir: path.join(here, 'demo-app'), force: false, quiet: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dir') out.dir = path.resolve(argv[++i]);
    else if (a === '--force') out.force = true;
    else if (a === '--quiet') out.quiet = true;
    else if (a === '--help' || a === '-h') {
      console.log('usage: node sandbox/create-demo.mjs [--dir <path>] [--force] [--quiet]');
      process.exit(0);
    } else throw new Error(`unknown argument: ${a}`);
  }
  return out;
}

function isInside(child, parent) {
  const rel = path.relative(path.resolve(parent), path.resolve(child));
  return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
}

// ---------------------------------------------------------------------------------------------
// File contents. Keep these stable: the sim scenario (foreman/src/agents/sim/edits.ts) patches them.
// ---------------------------------------------------------------------------------------------

export const FILES_V1 = {
  '.gitignore': `node_modules/
*.log
.pocket-notes.json
`,
  'package.json': `{
  "name": "pocket-notes",
  "version": "0.2.0",
  "description": "A tiny, fast notes CLI for your terminal.",
  "type": "module",
  "bin": {
    "notes": "src/cli.ts"
  },
  "scripts": {
    "start": "node src/cli.ts",
    "test": "node --test"
  },
  "engines": {
    "node": ">=22.18"
  },
  "license": "MIT"
}
`,
  'src/notes.ts': `// Core note operations. Pure functions: callers own persistence.

export interface Note {
  id: number;
  text: string;
  done: boolean;
  createdAt: number;
}

export interface ListOptions {
  all?: boolean;
}

export function nextId(notes: readonly Note[]): number {
  return notes.reduce((max, n) => Math.max(max, n.id), 0) + 1;
}

export function addNote(notes: readonly Note[], text: string, now = Date.now()): [Note[], Note] {
  const trimmed = text.trim();
  if (!trimmed) throw new Error('note text is empty');
  const note: Note = { id: nextId(notes), text: trimmed, done: false, createdAt: now };
  return [[...notes, note], note];
}

export function completeNote(notes: readonly Note[], id: number): Note[] {
  if (!notes.some((n) => n.id === id)) throw new Error(\`no note with id \${id}\`);
  return notes.map((n) => (n.id === id ? { ...n, done: true } : n));
}

export function removeNote(notes: readonly Note[], id: number): Note[] {
  if (!notes.some((n) => n.id === id)) throw new Error(\`no note with id \${id}\`);
  return notes.filter((n) => n.id !== id);
}

export function listNotes(notes: readonly Note[], opts: ListOptions = {}): Note[] {
  const visible = opts.all ? notes : notes.filter((n) => !n.done);
  return [...visible].sort((a, b) => a.createdAt - b.createdAt);
}
`,
  'src/store.ts': `// JSON-file persistence for notes.
import fs from 'node:fs';
import path from 'node:path';
import type { Note } from './notes.ts';

interface NotesFile {
  version: 1;
  notes: Note[];
}

// Notes live next to where you run the CLI (like a project-local todo file); NOTES_FILE overrides.
export function defaultNotesPath(env: NodeJS.ProcessEnv = process.env, cwd = process.cwd()): string {
  return env.NOTES_FILE ?? path.join(cwd, '.pocket-notes.json');
}

export function loadNotes(file: string): Note[] {
  if (!fs.existsSync(file)) return [];
  const data = JSON.parse(fs.readFileSync(file, 'utf8')) as NotesFile;
  if (data.version !== 1 || !Array.isArray(data.notes)) {
    throw new Error(\`unrecognised notes file: \${file}\`);
  }
  return data.notes;
}

export function saveNotes(file: string, notes: readonly Note[]): void {
  const data: NotesFile = { version: 1, notes: [...notes] };
  const tmp = \`\${file}.tmp\`;
  fs.writeFileSync(tmp, JSON.stringify(data, null, 2) + '\\n');
  fs.renameSync(tmp, file);
}
`,
  'src/format.ts': `// Human-friendly rendering of notes for the terminal.
import type { Note } from './notes.ts';

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export function relativeTime(then: number, now = Date.now()): string {
  const diff = Math.max(0, now - then);
  if (diff < MINUTE) return 'just now';
  if (diff < HOUR) return \`\${Math.floor(diff / MINUTE)}m ago\`;
  if (diff < DAY) return \`\${Math.floor(diff / HOUR)}h ago\`;
  return \`\${Math.floor(diff / DAY)}d ago\`;
}

export function formatNote(note: Note, now = Date.now()): string {
  const box = note.done ? '[x]' : '[ ]';
  const id = \`#\${note.id}\`.padEnd(4);
  return \`\${box} \${id} \${note.text}  (\${relativeTime(note.createdAt, now)})\`;
}

export function formatList(notes: readonly Note[], now = Date.now()): string {
  if (notes.length === 0) return 'No notes yet. Add one with: notes add "buy oat milk"';
  return notes.map((n) => formatNote(n, now)).join('\\n');
}
`,
  'src/cli.ts': `#!/usr/bin/env node
// Entry point: notes <command> [args]
import { addNote, completeNote, listNotes, removeNote } from './notes.ts';
import { defaultNotesPath, loadNotes, saveNotes } from './store.ts';
import { formatList, formatNote } from './format.ts';

export interface Io {
  env: NodeJS.ProcessEnv;
  out: (line: string) => void;
  err: (line: string) => void;
  now?: () => number;
}

const HELP = \`pocket-notes - a tiny notes CLI

usage:
  notes add <text...>     add a note
  notes list [--all]      list open notes (--all includes done)
  notes done <id>         mark a note as done
  notes rm <id>           delete a note
  notes help              show this help\`;

function parseId(raw: string | undefined): number {
  const id = Number(raw);
  if (!Number.isInteger(id) || id <= 0) throw new Error(\`invalid note id: \${raw ?? '(missing)'}\`);
  return id;
}

export function run(argv: string[], io: Io): number {
  const [cmd, ...rest] = argv;
  const file = defaultNotesPath(io.env);
  const now = io.now ?? Date.now;
  try {
    switch (cmd) {
      case 'add': {
        const [notes, note] = addNote(loadNotes(file), rest.join(' '), now());
        saveNotes(file, notes);
        io.out(\`added \${formatNote(note, now())}\`);
        return 0;
      }
      case 'list':
      case 'ls': {
        const all = rest.includes('--all');
        io.out(formatList(listNotes(loadNotes(file), { all }), now()));
        return 0;
      }
      case 'done': {
        const id = parseId(rest[0]);
        saveNotes(file, completeNote(loadNotes(file), id));
        io.out(\`done #\${id}\`);
        return 0;
      }
      case 'rm': {
        const id = parseId(rest[0]);
        saveNotes(file, removeNote(loadNotes(file), id));
        io.out(\`removed #\${id}\`);
        return 0;
      }
      case undefined:
      case 'help':
      case '--help':
      case '-h':
        io.out(HELP);
        return 0;
      default:
        io.err(\`unknown command: \${cmd}\\n\\n\${HELP}\`);
        return 2;
    }
  } catch (e) {
    io.err(\`error: \${(e as Error).message}\`);
    return 1;
  }
}

if (import.meta.main) {
  process.exitCode = run(process.argv.slice(2), {
    env: process.env,
    out: (l) => console.log(l),
    err: (l) => console.error(l),
  });
}
`,
  'test/notes.test.ts': `import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addNote, completeNote, listNotes, removeNote } from '../src/notes.ts';

test('addNote assigns increasing ids and trims text', () => {
  const [a] = addNote([], '  first  ', 1);
  const [b, second] = addNote(a, 'second', 2);
  assert.equal(a[0].text, 'first');
  assert.equal(second.id, 2);
  assert.equal(b.length, 2);
});

test('addNote rejects empty text', () => {
  assert.throws(() => addNote([], '   '), /empty/);
});

test('completeNote and listNotes hide done notes by default', () => {
  const [notes] = addNote([], 'water plants', 1);
  const done = completeNote(notes, 1);
  assert.equal(listNotes(done).length, 0);
  assert.equal(listNotes(done, { all: true }).length, 1);
});

test('removeNote throws for unknown ids', () => {
  assert.throws(() => removeNote([], 7), /no note with id 7/);
});
`,
  'test/format.test.ts': `import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatList, formatNote, relativeTime } from '../src/format.ts';

const now = 10 * 24 * 3_600_000;

test('relativeTime buckets', () => {
  assert.equal(relativeTime(now - 5_000, now), 'just now');
  assert.equal(relativeTime(now - 5 * 60_000, now), '5m ago');
  assert.equal(relativeTime(now - 3 * 3_600_000, now), '3h ago');
  assert.equal(relativeTime(now - 2 * 86_400_000, now), '2d ago');
});

test('formatNote shows checkbox, id and age', () => {
  const line = formatNote({ id: 3, text: 'call mum', done: false, createdAt: now - 60_000 }, now);
  assert.equal(line, '[ ] #3   call mum  (1m ago)');
});

test('formatList has a friendly empty state', () => {
  assert.match(formatList([], now), /No notes yet/);
});
`,
  'test/cli.test.ts': `import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { run } from '../src/cli.ts';

function harness() {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pocket-notes-'));
  const out: string[] = [];
  const err: string[] = [];
  const io = {
    env: { NOTES_FILE: path.join(dir, 'notes.json') },
    out: (l: string) => out.push(l),
    err: (l: string) => err.push(l),
    now: () => 1_000_000,
  };
  return { io, out, err };
}

test('add then list round-trips through the store', () => {
  const h = harness();
  assert.equal(run(['add', 'buy', 'oat', 'milk'], h.io), 0);
  assert.equal(run(['list'], h.io), 0);
  assert.match(h.out.at(-1) ?? '', /buy oat milk/);
});

test('done hides a note from the default list', () => {
  const h = harness();
  run(['add', 'stretch'], h.io);
  assert.equal(run(['done', '1'], h.io), 0);
  run(['list'], h.io);
  assert.match(h.out.at(-1) ?? '', /No notes yet/);
});

test('unknown commands exit 2 with help', () => {
  const h = harness();
  assert.equal(run(['frobnicate'], h.io), 2);
  assert.match(h.err.join('\\n'), /unknown command/);
});
`,
  'README.md': `# pocket-notes

A tiny, fast notes CLI for your terminal. Zero dependencies, plain JSON storage.

\`\`\`sh
notes add "buy oat milk"
notes list
notes done 1
\`\`\`

## Commands

| command | what it does |
| --- | --- |
| \`notes add <text...>\` | add a note |
| \`notes list [--all]\` | list open notes (\`--all\` includes done ones) |
| \`notes done <id>\` | mark a note as done |
| \`notes rm <id>\` | delete a note |

Notes live in \`.pocket-notes.json\` in the current directory (override with \`NOTES_FILE\`).

## Development

Requires Node 22.18+ (runs TypeScript directly).

\`\`\`sh
npm test
\`\`\`
`,
};

// Commits replay a plausible history. Each entry lists the files (from FILES_V1) it introduces.
const HISTORY = [
  { msg: 'Initial notes core and JSON store', files: ['.gitignore', 'package.json', 'src/notes.ts', 'src/store.ts', 'test/notes.test.ts'], date: '2026-09-21T10:12:00+02:00' },
  { msg: 'Add terminal formatting with relative ages', files: ['src/format.ts', 'test/format.test.ts'], date: '2026-09-23T16:40:00+02:00' },
  { msg: 'CLI entry point with add/list/done/rm', files: ['src/cli.ts', 'test/cli.test.ts'], date: '2026-09-26T09:05:00+02:00' },
  { msg: 'README', files: ['README.md'], date: '2026-09-27T18:30:00+02:00' },
];

function git(cwd, args, date) {
  const env = { ...process.env };
  if (date) {
    env.GIT_AUTHOR_DATE = date;
    env.GIT_COMMITTER_DATE = date;
  }
  return execFileSync(
    'git',
    ['-c', 'user.name=Demo Author', '-c', 'user.email=demo@example.com', '-c', 'commit.gpgsign=false', '-c', 'core.autocrlf=false', ...args],
    { cwd, env, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] },
  );
}

export function createDemo({ dir, force = false, quiet = false } = {}) {
  const target = path.resolve(dir ?? path.join(here, 'demo-app'));
  const allowedRoots = [here, os.tmpdir()];
  if (!allowedRoots.some((r) => isInside(target, r))) {
    throw new Error(`refusing to create demo repo outside sandbox/ or the OS temp dir: ${target}`);
  }
  if (fs.existsSync(target)) {
    if (!force) throw new Error(`${target} already exists (use --force to recreate)`);
    // Worktrees registered against the old repo would dangle; removing the dir is enough because
    // worktree admin data lives inside <repo>/.git/worktrees.
    fs.rmSync(target, { recursive: true, force: true, maxRetries: 5, retryDelay: 200 });
  }
  fs.mkdirSync(target, { recursive: true });
  git(target, ['init', '-q', '-b', 'main']);
  // a throwaway repo with its own identity: approved merges (made as "the user") never pick up
  // the real user's identity or signing setup, so unattended runs never try to sign
  for (const [k, v] of [['user.name', 'Demo Author'], ['user.email', 'demo@example.com'], ['commit.gpgsign', 'false']]) git(target, ['config', '--local', k, v]);
  for (const step of HISTORY) {
    for (const f of step.files) {
      const p = path.join(target, f);
      fs.mkdirSync(path.dirname(p), { recursive: true });
      fs.writeFileSync(p, FILES_V1[f]);
    }
    git(target, ['add', '-A']);
    git(target, ['commit', '-q', '-m', step.msg], step.date);
  }
  const head = git(target, ['rev-parse', '--short', 'HEAD']).trim();
  if (!quiet) console.log(`demo repo ready: ${target} (main @ ${head}, ${HISTORY.length} commits)`);
  return { dir: target, head };
}

const invokedDirectly = process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url);
if (invokedDirectly) {
  try {
    createDemo(parseArgs(process.argv.slice(2)));
  } catch (e) {
    console.error(`create-demo: ${e.message}`);
    process.exit(1);
  }
}
