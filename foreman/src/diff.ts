// Parse `git diff` unified output into the protocol's structured DiffFile[].
import type { DiffFile, DiffHunk, DiffLine } from './protocol.js';

export interface ParseLimits {
  maxLinesPerFile?: number;
  maxTotalLines?: number;
}

export interface ParsedDiff {
  files: DiffFile[];
  truncated: boolean;
  stats: { files: number; additions: number; deletions: number };
}

function unquote(p: string): string {
  if (p.startsWith('"') && p.endsWith('"')) {
    return p
      .slice(1, -1)
      .replace(/\\t/g, '\t')
      .replace(/\\n/g, '\n')
      .replace(/\\(["\\])/g, '$1');
  }
  return p;
}

function stripPrefix(p: string): string {
  const u = unquote(p.trim());
  if (u === '/dev/null') return u;
  return u.replace(/^[ab]\//, '');
}

const HUNK_RE = /^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@(.*)$/;

export function parseUnifiedDiff(text: string, limits: ParseLimits = {}): ParsedDiff {
  const maxPerFile = limits.maxLinesPerFile ?? 600;
  const maxTotal = limits.maxTotalLines ?? 5000;
  const files: DiffFile[] = [];
  let truncated = false;
  let total = 0;
  let file: DiffFile | undefined;
  let hunk: DiffHunk | undefined;
  let fileLines = 0;
  let oldNo = 0;
  let newNo = 0;
  // remaining line budget of the current hunk (so content lines like "--- x" are not mistaken for headers)
  let remOld = 0;
  let remNew = 0;

  const lines = text.replace(/\r\n/g, '\n').split('\n');
  for (const line of lines) {
    const inHunkBody = hunk !== undefined && (remOld > 0 || remNew > 0);
    if (inHunkBody && file && hunk) {
      if (line.startsWith('\\')) continue; // "\ No newline at end of file"
      const ch = line[0];
      let dl: DiffLine;
      if (ch === '+') {
        file.additions++;
        remNew--;
        dl = { kind: 'add', text: line.slice(1), newNo: newNo++ };
      } else if (ch === '-') {
        file.deletions++;
        remOld--;
        dl = { kind: 'del', text: line.slice(1), oldNo: oldNo++ };
      } else {
        remOld--;
        remNew--;
        dl = { kind: 'ctx', text: line.slice(1), oldNo: oldNo++, newNo: newNo++ };
      }
      if (fileLines >= maxPerFile || total >= maxTotal) {
        truncated = true;
        continue;
      }
      hunk.lines.push(dl);
      fileLines++;
      total++;
      continue;
    }
    if (line.startsWith('\\')) continue;
    if (line.startsWith('diff --git ')) {
      const m = /^diff --git (".*?"|\S+) (".*?"|\S+)$/.exec(line);
      const a = m ? stripPrefix(m[1]!) : '';
      const b = m ? stripPrefix(m[2]!) : '';
      file = { path: b || a, status: 'modified', binary: false, additions: 0, deletions: 0, hunks: [] };
      files.push(file);
      hunk = undefined;
      fileLines = 0;
      continue;
    }
    if (!file) continue;
    if (line.startsWith('new file mode')) file.status = 'added';
    else if (line.startsWith('deleted file mode')) file.status = 'deleted';
    else if (line.startsWith('rename from ')) {
      file.oldPath = unquote(line.slice(12));
      file.status = 'renamed';
    } else if (line.startsWith('rename to ')) {
      file.path = unquote(line.slice(10));
      file.status = 'renamed';
    } else if (line.startsWith('Binary files ')) file.binary = true;
    else if (line.startsWith('--- ')) {
      const p = stripPrefix(line.slice(4));
      if (p !== '/dev/null' && file.status !== 'renamed') file.path = p;
    } else if (line.startsWith('+++ ')) {
      const p = stripPrefix(line.slice(4));
      if (p !== '/dev/null') file.path = p;
    } else {
      const hm = HUNK_RE.exec(line);
      if (hm) {
        oldNo = Number(hm[1]);
        newNo = Number(hm[3]);
        const oldLines = hm[2] === undefined ? 1 : Number(hm[2]);
        const newLines = hm[4] === undefined ? 1 : Number(hm[4]);
        remOld = oldLines;
        remNew = newLines;
        hunk = { header: line, oldStart: oldNo, oldLines, newStart: newNo, newLines, lines: [] };
        file.hunks.push(hunk);
      }
    }
  }
  const stats = {
    files: files.length,
    additions: files.reduce((s, f) => s + f.additions, 0),
    deletions: files.reduce((s, f) => s + f.deletions, 0),
  };
  return { files, truncated, stats };
}

/** Render a compact unified-ish text of a parsed diff (for agent prompts and log entries). */
export function renderDiffText(files: DiffFile[], maxChars = 12_000): string {
  const out: string[] = [];
  for (const f of files) {
    out.push(`=== ${f.status} ${f.oldPath ? `${f.oldPath} -> ` : ''}${f.path} (+${f.additions} -${f.deletions})`);
    if (f.binary) out.push('(binary)');
    for (const h of f.hunks) {
      out.push(h.header);
      for (const l of h.lines) out.push((l.kind === 'add' ? '+' : l.kind === 'del' ? '-' : ' ') + l.text);
    }
  }
  let text = out.join('\n');
  if (text.length > maxChars) text = text.slice(0, maxChars) + '\n\u2026 (diff truncated)';
  return text;
}
