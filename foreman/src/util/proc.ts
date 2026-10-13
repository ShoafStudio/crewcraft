// Child-process helpers. Everything is spawned with explicit argv (no shell) unless stated.
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';

export interface RunResult {
  code: number;
  stdout: string;
  stderr: string;
  timedOut: boolean;
}

export interface RunOptions {
  cwd?: string;
  env?: NodeJS.ProcessEnv;
  input?: string;
  timeoutMs?: number;
  /** Run through the platform shell (needed for npm/.cmd on Windows). `cmd` is then a full command line. */
  shell?: boolean;
  maxBuffer?: number;
}

/**
 * Kill a child we started together with everything it started (a shell's `npm test` and its
 * node grandchildren). Only ever targets our own child's PID and its descendants.
 */
export function killTree(child: ChildProcess): void {
  const pid = child.pid;
  if (!pid || child.exitCode !== null) return;
  if (process.platform === 'win32') {
    // taskkill /T walks the tree of *this* PID only
    spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
  } else {
    try {
      process.kill(-pid, 'SIGKILL'); // the child leads its own process group (detached)
    } catch {
      child.kill('SIGKILL');
    }
  }
}

export interface ProcEntry {
  pid: number;
  ppid: number;
  /** creation time as reported by the OS (identifies the process, not just its reusable pid) */
  created: string;
  /** the same as epoch ms (0 if unknown) */
  createdMs: number;
}

/**
 * The OS process table (pid, parent, creation time), or undefined if it could not be read (the
 * query is retried once: on a busy machine PowerShell/CIM can be slow).
 */
export async function processTable(): Promise<ProcEntry[] | undefined> {
  for (let attempt = 0; attempt < 2; attempt++) {
    try {
      const res =
        process.platform === 'win32'
          ? await run(
              'powershell',
              ['-NoProfile', '-NonInteractive', '-Command', "Get-CimInstance Win32_Process -Property ProcessId,ParentProcessId,CreationDate | ForEach-Object { '{0} {1} {2}' -f $_.ProcessId, $_.ParentProcessId, $(if ($_.CreationDate) { $_.CreationDate.ToFileTimeUtc() } else { 0 }) }"],
              { timeoutMs: 30_000 },
            )
          : await run('ps', ['-A', '-o', 'pid=,ppid=,lstart='], { timeoutMs: 30_000 });
      const table = parseTable(res.stdout);
      if (res.code === 0 && !res.timedOut && table.length) return table;
    } catch {
      /* retry */
    }
  }
  return undefined;
}

function parseTable(text: string): ProcEntry[] {
  const out: ProcEntry[] = [];
  for (const line of text.split(/\r?\n/)) {
    const m = /^\s*(\d+)\s+(\d+)\s+(.*?)\s*$/.exec(line);
    if (!m) continue;
    const created = m[3]!;
    // Windows: FILETIME (100 ns since 1601); POSIX: a date string
    const createdMs = /^\d{15,}$/.test(created) ? Number(created) / 10_000 - 11_644_473_600_000 : Date.parse(created) || 0;
    out.push({ pid: Number(m[1]), ppid: Number(m[2]), created, createdMs });
  }
  return out;
}

/**
 * Orphans of our own (now exited) child: processes whose parent chain leads to `rootPid` and that
 * were started after we spawned it. Windows keeps the parent pid of orphans, so they can still be
 * found after the child exited. If `rootPid` is alive again as a NEWER process (the pid was
 * reused), nothing is returned.
 */
export function orphansOf(table: ProcEntry[], rootPid: number, spawnedAtMs: number): ProcEntry[] {
  const root = table.find((e) => e.pid === rootPid);
  if (root && root.createdMs > spawnedAtMs + 2000) return []; // pid reused by someone else
  return descendantsOf(table, rootPid).filter((e) => e.createdMs >= spawnedAtMs - 2000);
}

/** Every process below `rootPid` in a process table (not the root itself). */
export function descendantsOf(table: ProcEntry[], rootPid: number): ProcEntry[] {
  const kids = new Map<number, ProcEntry[]>();
  for (const e of table) {
    if (e.pid === e.ppid) continue;
    const list = kids.get(e.ppid) ?? [];
    list.push(e);
    kids.set(e.ppid, list);
  }
  const out: ProcEntry[] = [];
  const seen = new Set<number>([rootPid]);
  const queue = [rootPid];
  while (queue.length) {
    for (const k of kids.get(queue.shift()!) ?? []) {
      if (seen.has(k.pid)) continue;
      seen.add(k.pid);
      out.push(k);
      queue.push(k.pid);
    }
  }
  return out;
}

/**
 * Kill processes from an earlier snapshot of OUR OWN child's tree that are still running. A pid
 * is only killed if the same process is still there (same creation time), so a pid the OS has
 * reused for something else is never touched. Returns the pids killed.
 */
export async function killSnapshot(snapshot: ProcEntry[], table?: ProcEntry[]): Promise<number[] | undefined> {
  if (!snapshot.length) return [];
  const now = table ?? (await processTable());
  if (!now) return undefined; // cannot verify which of them are still the same processes
  const alive = new Map(now.map((e) => [e.pid, e]));
  const killed: number[] = [];
  for (const e of snapshot) {
    const cur = alive.get(e.pid);
    if (!cur || cur.created !== e.created) continue;
    if (process.platform === 'win32') spawnSync('taskkill', ['/PID', String(e.pid), '/T', '/F'], { windowsHide: true, stdio: 'ignore' });
    else {
      try {
        process.kill(e.pid, 'SIGKILL');
      } catch {
        continue;
      }
    }
    killed.push(e.pid);
  }
  return killed;
}

export function run(cmd: string, args: string[], opts: RunOptions = {}): Promise<RunResult> {
  return new Promise((resolve, reject) => {
    const child = spawn(cmd, opts.shell ? [] : args, {
      cwd: opts.cwd,
      env: opts.env ?? process.env,
      shell: opts.shell ?? false,
      windowsHide: true,
      stdio: ['pipe', 'pipe', 'pipe'],
      // POSIX: own process group, so a timeout can kill the whole tree
      ...(process.platform !== 'win32' && opts.shell ? { detached: true } : {}),
    });
    const max = opts.maxBuffer ?? 8 * 1024 * 1024;
    let stdout = '';
    let stderr = '';
    let timedOut = false;
    child.stdout!.setEncoding('utf8');
    child.stderr!.setEncoding('utf8');
    child.stdout!.on('data', (d: string) => {
      if (stdout.length < max) stdout += d;
    });
    child.stderr!.on('data', (d: string) => {
      if (stderr.length < max) stderr += d;
    });
    let timer: NodeJS.Timeout | undefined;
    if (opts.timeoutMs) {
      timer = setTimeout(() => {
        timedOut = true;
        killTree(child);
      }, opts.timeoutMs);
    }
    child.on('error', (e) => {
      if (timer) clearTimeout(timer);
      reject(e);
    });
    child.on('close', (code) => {
      if (timer) clearTimeout(timer);
      resolve({ code: code ?? (timedOut ? 124 : 1), stdout, stderr, timedOut });
    });
    if (opts.input !== undefined) child.stdin!.end(opts.input);
    else child.stdin!.end();
  });
}

/** Run a shell command line (used for repo test commands like `npm test`). */
export function runShell(commandLine: string, opts: Omit<RunOptions, 'shell'> = {}): Promise<RunResult> {
  return run(commandLine, [], { ...opts, shell: true });
}
