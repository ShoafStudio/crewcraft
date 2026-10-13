// Real code changes the sim makes to the pocket-notes demo repo (sandbox/create-demo.mjs).
// Every patch is a find/replace (or a new file) so it is idempotent: re-applying after a Foreman
// restart is a no-op. Patches are applied to the agent's real git worktree, so diffs, test runs
// and merges are genuine.
import fs from 'node:fs';
import path from 'node:path';

export type Patch = { file: string; create: string } | { file: string; find: string; replace: string };

export class PatchError extends Error {}

/** Apply one patch in `root`. Returns old and new content (for diff logs). */
export function applyPatch(root: string, p: Patch): { before: string; after: string; created: boolean; changed: boolean } {
  const file = path.join(root, p.file);
  const exists = fs.existsSync(file);
  const before = exists ? fs.readFileSync(file, 'utf8').replace(/\r\n/g, '\n') : '';
  let after: string;
  if ('create' in p) {
    after = p.create;
  } else {
    if (before.includes(p.replace)) return { before, after: before, created: false, changed: false };
    if (!before.includes(p.find)) throw new PatchError(`anchor not found in ${p.file}: ${JSON.stringify(p.find.slice(0, 60))}`);
    after = before.replace(p.find, p.replace);
  }
  if (after === before) return { before, after, created: false, changed: false };
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, after);
  return { before, after, created: !exists, changed: true };
}

/** Compact line diff (LCS) with one line of context; for monitor log entries. */
export function miniDiff(before: string, after: string, maxLines = 14): string {
  const a = before ? before.split('\n') : [];
  const b = after.split('\n');
  const n = a.length;
  const m = b.length;
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) for (let j = m - 1; j >= 0; j--) dp[i]![j] = a[i] === b[j] ? dp[i + 1]![j + 1]! + 1 : Math.max(dp[i + 1]![j]!, dp[i]![j + 1]!);
  const ops: Array<[' ' | '-' | '+', string]> = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      ops.push([' ', a[i]!]);
      i++;
      j++;
    } else if (dp[i + 1]![j]! >= dp[i]![j + 1]!) ops.push(['-', a[i++]!]);
    else ops.push(['+', b[j++]!]);
  }
  while (i < n) ops.push(['-', a[i++]!]);
  while (j < m) ops.push(['+', b[j++]!]);
  const keep = new Set<number>();
  ops.forEach((o, k) => {
    if (o[0] !== ' ') for (let d = -1; d <= 1; d++) keep.add(k + d);
  });
  const out: string[] = [];
  let last = -2;
  for (let k = 0; k < ops.length; k++) {
    if (!keep.has(k)) continue;
    if (last >= 0 && k > last + 1) out.push('  ...');
    out.push(`${ops[k]![0]} ${ops[k]![1]}`);
    last = k;
  }
  if (out.length > maxLines) return [...out.slice(0, maxLines), `  ... (${out.length - maxLines} more lines)`].join('\n');
  return out.join('\n');
}

// ---------------------------------------------------------------------------------------------
// t2 (Kit): tag parser
// ---------------------------------------------------------------------------------------------

export const TAGS_V1 = `// Tag parsing for notes: "call mum #family #to-do" -> ["family", "to-do"].
// Tags are case-insensitive (stored lowercase) and de-duplicated, in order of appearance.

const TAG_RE = /#(\\w+)/g;

export function normalizeTag(raw: string): string {
  return raw.replace(/^#/, '').toLowerCase();
}

export function parseTags(text: string): string[] {
  const seen = new Set<string>();
  for (const m of text.matchAll(TAG_RE)) seen.add(normalizeTag(m[1]!));
  return [...seen];
}

export function hasTag(text: string, tag: string): boolean {
  return parseTags(text).includes(normalizeTag(tag));
}
`;

export const TAGS_TEST_V1 = `import { test } from 'node:test';
import assert from 'node:assert/strict';
import { hasTag, normalizeTag, parseTags } from '../src/tags.ts';

test('parseTags finds tags in order of appearance', () => {
  assert.deepEqual(parseTags('buy oat milk #errands #home'), ['errands', 'home']);
});

test('parseTags keeps hyphenated tags', () => {
  assert.deepEqual(parseTags('fix login #to-do'), ['to-do']);
});

test('parseTags is case-insensitive and de-duplicates', () => {
  assert.deepEqual(parseTags('#Work standup #work'), ['work']);
});

test('a # inside a word is not a tag', () => {
  assert.deepEqual(parseTags('see issue#12'), []);
});

test('hasTag accepts the tag with or without #', () => {
  assert.equal(hasTag('call mum #family', '#Family'), true);
  assert.equal(hasTag('call mum #family', 'work'), false);
  assert.equal(normalizeTag('#To-Do-'), 'to-do');
});
`;

export const T2_FIX: Patch[] = [
  {
    file: 'src/tags.ts',
    find: `const TAG_RE = /#(\\w+)/g;`,
    replace: `// A tag starts at the beginning of the text or after whitespace ("issue#12" is not a tag) and
// may contain letters, digits, "_" and "-" (a trailing "-" is dropped).
const TAG_RE = /(?:^|\\s)#(\\w[\\w-]*)/g;`,
  },
  {
    file: 'src/tags.ts',
    find: `  return raw.replace(/^#/, '').toLowerCase();`,
    replace: `  return raw.replace(/^#/, '').replace(/-+$/, '').toLowerCase();`,
  },
];

// ---------------------------------------------------------------------------------------------
// t4 (Wren): highlight tags in list output
// ---------------------------------------------------------------------------------------------

export const T4_FORMAT: Patch[] = [
  {
    file: 'src/format.ts',
    find: `const DAY = 24 * HOUR;
`,
    replace: `const DAY = 24 * HOUR;

// "#tag" at the start of the text or after whitespace (mirrors src/tags.ts).
const TAG_IN_TEXT = /(^|\\s)(#\\w[\\w-]*)/g;
const CYAN = '\\u001b[36m';
const RESET = '\\u001b[39m';

export interface FormatOptions {
  /** wrap #tags in ANSI cyan (only when writing to a terminal) */
  color?: boolean;
}

export function highlightTags(text: string, color = false): string {
  if (!color) return text;
  return text.replace(TAG_IN_TEXT, (_m, pre: string, tag: string) => pre + CYAN + tag + RESET);
}
`,
  },
  {
    file: 'src/format.ts',
    find: `export function formatNote(note: Note, now = Date.now()): string {
  const box = note.done ? '[x]' : '[ ]';
  const id = \`#\${note.id}\`.padEnd(4);
  return \`\${box} \${id} \${note.text}  (\${relativeTime(note.createdAt, now)})\`;
}`,
    replace: `export function formatNote(note: Note, now = Date.now(), opts: FormatOptions = {}): string {
  const box = note.done ? '[x]' : '[ ]';
  const id = \`#\${note.id}\`.padEnd(4);
  return \`\${box} \${id} \${highlightTags(note.text, opts.color)}  (\${relativeTime(note.createdAt, now)})\`;
}`,
  },
  {
    file: 'src/format.ts',
    find: `export function formatList(notes: readonly Note[], now = Date.now()): string {
  if (notes.length === 0) return 'No notes yet. Add one with: notes add "buy oat milk"';
  return notes.map((n) => formatNote(n, now)).join('\\n');
}`,
    replace: `export function formatList(notes: readonly Note[], now = Date.now(), opts: FormatOptions = {}): string {
  if (notes.length === 0) return 'No notes yet. Add one with: notes add "buy oat milk"';
  return notes.map((n) => formatNote(n, now, opts)).join('\\n');
}`,
  },
];

export const T4_TEST: Patch[] = [
  {
    file: 'test/format.test.ts',
    find: `import { formatList, formatNote, relativeTime } from '../src/format.ts';`,
    replace: `import { formatList, formatNote, highlightTags, relativeTime } from '../src/format.ts';`,
  },
  {
    file: 'test/format.test.ts',
    find: `test('formatList has a friendly empty state', () => {
  assert.match(formatList([], now), /No notes yet/);
});
`,
    replace: `test('formatList has a friendly empty state', () => {
  assert.match(formatList([], now), /No notes yet/);
});

test('highlightTags colors tags only when asked', () => {
  assert.equal(highlightTags('buy #oat-milk today', true), 'buy \\u001b[36m#oat-milk\\u001b[39m today');
  assert.equal(highlightTags('buy #oat-milk today'), 'buy #oat-milk today');
  assert.equal(highlightTags('issue#12', true), 'issue#12');
});
`,
  },
];

// ---------------------------------------------------------------------------------------------
// t3 (Juniper): `notes list --tag` + `notes tags`
// ---------------------------------------------------------------------------------------------

export const T3_NOTES: Patch[] = [
  {
    file: 'src/notes.ts',
    find: `// Core note operations. Pure functions: callers own persistence.
`,
    replace: `// Core note operations. Pure functions: callers own persistence.
import { hasTag, parseTags } from './tags.ts';
`,
  },
  {
    file: 'src/notes.ts',
    find: `export interface ListOptions {
  all?: boolean;
}`,
    replace: `export interface ListOptions {
  all?: boolean;
  /** only notes carrying this #tag (case-insensitive) */
  tag?: string;
}`,
  },
  {
    file: 'src/notes.ts',
    find: `  const visible = opts.all ? notes : notes.filter((n) => !n.done);
  return [...visible].sort((a, b) => a.createdAt - b.createdAt);
}`,
    replace: `  let visible = opts.all ? notes : notes.filter((n) => !n.done);
  if (opts.tag) visible = visible.filter((n) => hasTag(n.text, opts.tag!));
  return [...visible].sort((a, b) => a.createdAt - b.createdAt);
}

/** Tag usage counts, most used first (ties alphabetical). */
export function tagCounts(notes: readonly Note[], opts: { includeDone?: boolean } = {}): Array<[string, number]> {
  const counts = new Map<string, number>();
  for (const n of notes) {
    if (n.done && !opts.includeDone) continue;
    for (const t of parseTags(n.text)) counts.set(t, (counts.get(t) ?? 0) + 1);
  }
  return [...counts].sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
}`,
  },
];

export const T3_CLI_LIST: Patch[] = [
  {
    file: 'src/cli.ts',
    find: `import { addNote, completeNote, listNotes, removeNote } from './notes.ts';`,
    replace: `import { addNote, completeNote, listNotes, removeNote, tagCounts } from './notes.ts';`,
  },
  {
    file: 'src/cli.ts',
    find: `function parseId(raw: string | undefined): number {`,
    replace: `/** Value after a flag: --tag work / -t work */
function flagValue(args: string[], ...names: string[]): string | undefined {
  const i = args.findIndex((a) => names.includes(a));
  return i >= 0 ? args[i + 1] : undefined;
}

function parseId(raw: string | undefined): number {`,
  },
  {
    file: 'src/cli.ts',
    find: `        const all = rest.includes('--all');
        io.out(formatList(listNotes(loadNotes(file), { all }), now()));`,
    replace: `        const all = rest.includes('--all');
        const tag = flagValue(rest, '--tag', '-t');
        io.out(formatList(listNotes(loadNotes(file), { all, tag }), now()));`,
  },
];

/** `notes tags`; includeDone depends on the user's answer to the lead's question. */
export function t3CliTags(includeDoneByDefault: boolean): Patch[] {
  const expr = includeDoneByDefault ? 'true' : `rest.includes('--all')`;
  return [
    {
      file: 'src/cli.ts',
      find: `      case 'done': {`,
      replace: `      case 'tags': {
        const counts = tagCounts(loadNotes(file), { includeDone: ${expr} });
        io.out(counts.length ? counts.map(([t, n]) => \`#\${t}  \${n}\`).join('\\n') : 'No tags yet. Try: notes add "call mum #family"');
        return 0;
      }
      case 'done': {`,
    },
  ];
}

export function t3CliTests(includeDoneByDefault: boolean): Patch[] {
  const doneTest = includeDoneByDefault
    ? `test('tags counts completed notes too', () => {
  const h = harness();
  run(['add', 'old', 'thing', '#archive'], h.io);
  run(['done', '1'], h.io);
  run(['tags'], h.io);
  assert.equal(h.out.at(-1), '#archive  1');
});
`
    : `test('tags ignores completed notes unless --all', () => {
  const h = harness();
  run(['add', 'old', 'thing', '#archive'], h.io);
  run(['done', '1'], h.io);
  run(['tags'], h.io);
  assert.match(h.out.at(-1) ?? '', /No tags yet/);
  run(['tags', '--all'], h.io);
  assert.equal(h.out.at(-1), '#archive  1');
});
`;
  return [
    {
      file: 'test/cli.test.ts',
      find: `test('unknown commands exit 2 with help', () => {`,
      replace: `test('list --tag shows only notes with that tag', () => {
  const h = harness();
  run(['add', 'call', 'mum', '#family'], h.io);
  run(['add', 'ship', 'v0.3', '#work'], h.io);
  assert.equal(run(['list', '--tag', 'work'], h.io), 0);
  assert.match(h.out.at(-1) ?? '', /ship v0\\.3/);
  assert.doesNotMatch(h.out.at(-1) ?? '', /call mum/);
});

test('tags prints usage counts, most used first', () => {
  const h = harness();
  run(['add', '#work', 'standup'], h.io);
  run(['add', 'review', 'PR', '#work'], h.io);
  run(['add', 'laundry', '#home'], h.io);
  assert.equal(run(['tags'], h.io), 0);
  assert.equal(h.out.at(-1), '#work  2\\n#home  1');
});

${doneTest}
test('unknown commands exit 2 with help', () => {`,
    },
  ];
}

// ---------------------------------------------------------------------------------------------
// t5 (Kit): unicode + punctuation edge cases
// ---------------------------------------------------------------------------------------------

export const T5_TESTS: Patch[] = [
  {
    file: 'test/tags.test.ts',
    find: `test('hasTag accepts the tag with or without #', () => {`,
    replace: `test('tags may use any letters, not just ASCII', () => {
  assert.deepEqual(parseTags('#Crème-Brûlée for #café night'), ['crème-brûlée', 'café']);
});

test('punctuation ends a tag', () => {
  assert.deepEqual(parseTags('done with #work, finally.'), ['work']);
});

test('hasTag accepts the tag with or without #', () => {`,
  },
];

export const T5_FIX: Patch[] = [
  {
    file: 'src/tags.ts',
    find: `// may contain letters, digits, "_" and "-" (a trailing "-" is dropped).
const TAG_RE = /(?:^|\\s)#(\\w[\\w-]*)/g;`,
    replace: `// may contain letters (any script), digits, "_" and "-" (a trailing "-" is dropped).
const TAG_RE = /(?:^|\\s)#([\\p{L}\\p{N}_][\\p{L}\\p{N}_-]*)/gu;`,
  },
  {
    file: 'src/tags.ts',
    find: `  return raw.replace(/^#/, '').replace(/-+$/, '').toLowerCase();`,
    replace: `  return raw.replace(/^#/, '').replace(/-+$/, '').normalize('NFC').toLowerCase();`,
  },
];

// ---------------------------------------------------------------------------------------------
// t6 (Tove): docs, help text, color wiring
// ---------------------------------------------------------------------------------------------

export const T6_README: Patch[] = [
  {
    file: 'README.md',
    find: `| \`notes list [--all]\` | list open notes (\`--all\` includes done ones) |`,
    replace: `| \`notes list [--all] [--tag <name>]\` | list open notes (\`--all\` includes done ones), optionally only one #tag |
| \`notes tags\` | show your tags and how often you use them |`,
  },
  {
    file: 'README.md',
    find: `## Development`,
    replace: `## Tags

Put \`#tags\` anywhere in a note. Tags are case-insensitive, may use letters from any language,
digits, \`-\` and \`_\`, and are highlighted when printing to a terminal.

\`\`\`sh
notes add "call mum #family"
notes add "ship v0.3 #work #release"
notes list --tag work
notes tags
\`\`\`

## Development`,
  },
];

export const T6_CLI: Patch[] = [
  {
    file: 'src/cli.ts',
    find: `  notes add <text...>     add a note
  notes list [--all]      list open notes (--all includes done)`,
    replace: `  notes add <text...>     add a note (use #tags anywhere)
  notes list [--all]      list open notes (--all includes done)
             [--tag <t>]  only notes tagged #t
  notes tags              show tags and how often they are used`,
  },
  {
    file: 'src/cli.ts',
    find: `  now?: () => number;
}`,
    replace: `  now?: () => number;
  /** highlight #tags (set when stdout is a terminal) */
  color?: boolean;
}`,
  },
  {
    file: 'src/cli.ts',
    find: `        io.out(formatList(listNotes(loadNotes(file), { all, tag }), now()));`,
    replace: `        io.out(formatList(listNotes(loadNotes(file), { all, tag }), now(), { color: io.color }));`,
  },
  {
    file: 'src/cli.ts',
    find: `    env: process.env,
    out: (l) => console.log(l),`,
    replace: `    env: process.env,
    color: Boolean(process.stdout.isTTY) && !process.env.NO_COLOR,
    out: (l) => console.log(l),`,
  },
];

/**
 * A visible, honest response to "Request changes" in the sim: the worker records the reviewer's
 * note as a comment at the end of the task's main file (idempotent per round).
 */
export function appendReviewNote(root: string, file: string, note: string, round: number): { before: string; after: string; changed: boolean } {
  const p = path.join(root, file);
  const before = fs.existsSync(p) ? fs.readFileSync(p, 'utf8').replace(/\r\n/g, '\n') : '';
  const clean = note.replace(/\s+/g, ' ').trim().slice(0, 140) || 'address review feedback';
  const marker = `Review (round ${round}):`;
  if (before.includes(marker)) return { before, after: before, changed: false };
  const prefix = file.endsWith('.md') ? `<!-- ${marker} ${clean} -->` : `// ${marker} ${clean}`;
  const after = `${before.replace(/\n*$/, '\n')}${prefix}\n`;
  fs.writeFileSync(p, after);
  return { before, after, changed: true };
}
