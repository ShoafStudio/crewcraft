// String helpers shared across modules.

export function slugify(text: string, max = 32): string {
  const s = text
    .toLowerCase()
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '');
  const cut = s.slice(0, max).replace(/-+$/g, '');
  return cut || 'item';
}

export function truncate(text: string, max: number): string {
  if (text.length <= max) return text;
  return text.slice(0, Math.max(0, max - 1)) + '…';
}

/** First non-empty line, trimmed and truncated. */
export function firstLine(text: string, max = 120): string {
  const line = text.split(/\r?\n/).find((l) => l.trim()) ?? '';
  return truncate(line.trim(), max);
}

/** Keep the last `maxLines` lines and at most `maxChars` characters. */
export function tailLines(text: string, maxLines: number, maxChars = 4000): string {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  while (lines.length && !lines[lines.length - 1]!.trim()) lines.pop();
  let out = lines.slice(-maxLines).join('\n');
  if (out.length > maxChars) out = out.slice(out.length - maxChars);
  return out;
}

export function headLines(text: string, maxLines: number, maxChars = 4000): string {
  const lines = text.replace(/\r\n/g, '\n').split('\n');
  let out = lines.slice(0, maxLines).join('\n');
  if (lines.length > maxLines) out += `\n… (${lines.length - maxLines} more lines)`;
  if (out.length > maxChars) out = out.slice(0, maxChars) + '…';
  return out;
}

export function capitalize(s: string): string {
  return s ? s[0]!.toUpperCase() + s.slice(1) : s;
}

/** Strip ANSI escape codes. */
export function stripAnsi(s: string): string {
  // eslint-disable-next-line no-control-regex
  return s.replace(/\u001b\[[0-9;?]*[ -/]*[@-~]/g, '');
}
