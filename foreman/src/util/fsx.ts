// Small filesystem helpers: atomic writes that survive crashes mid-write (and Windows AV locks).
import fs from 'node:fs';
import path from 'node:path';

let tmpCounter = 0;

function sleepSync(ms: number): void {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms);
}

/** Write to a temp file in the same directory, fsync, then rename over the target. */
export function writeFileAtomic(file: string, data: string | Uint8Array): void {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.${++tmpCounter}.tmp`;
  const fd = fs.openSync(tmp, 'w');
  try {
    fs.writeSync(fd, typeof data === 'string' ? Buffer.from(data, 'utf8') : data);
    fs.fsyncSync(fd);
  } finally {
    fs.closeSync(fd);
  }
  // On Windows a rename can fail transiently (EPERM/EBUSY) if a scanner holds the target open.
  for (let attempt = 0; ; attempt++) {
    try {
      fs.renameSync(tmp, file);
      return;
    } catch (e) {
      const code = (e as NodeJS.ErrnoException).code;
      if (attempt < 8 && (code === 'EPERM' || code === 'EBUSY' || code === 'EACCES')) {
        sleepSync(25 * (attempt + 1));
        continue;
      }
      try {
        fs.rmSync(tmp, { force: true });
      } catch {
        /* ignore */
      }
      throw e;
    }
  }
}

export function writeJsonAtomic(file: string, value: unknown): void {
  writeFileAtomic(file, JSON.stringify(value, null, 2) + '\n');
}

export function readJson<T>(file: string): T | undefined {
  if (!fs.existsSync(file)) return undefined;
  const raw = fs.readFileSync(file, 'utf8');
  if (!raw.trim()) return undefined;
  return JSON.parse(raw) as T;
}

export function ensureDir(dir: string): string {
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** True if `child` is `parent` or inside it (case-insensitive on Windows). */
export function isInsideOrEqual(child: string, parent: string): boolean {
  const norm = (p: string) => {
    let r = path.resolve(p);
    if (process.platform === 'win32') r = r.toLowerCase();
    return r.replace(/[\/]+$/, '');
  };
  const c = norm(child);
  const p = norm(parent);
  if (c === p) return true;
  const rel = path.relative(p, c);
  return rel !== '' && !rel.startsWith('..') && !path.isAbsolute(rel);
}
