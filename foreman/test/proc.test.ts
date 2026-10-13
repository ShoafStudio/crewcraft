// Process-tree helpers used to clean up after a stopped agent turn: only our own descendants are
// ever selected, a reused pid is never treated as ours, and the real process table is readable.
import { spawn } from 'node:child_process';
import { describe, expect, it } from 'vitest';
import { descendantsOf, killSnapshot, orphansOf, processTable, type ProcEntry } from '../src/util/proc.js';

const e = (pid: number, ppid: number, createdMs: number): ProcEntry => ({ pid, ppid, created: String(createdMs), createdMs });

describe('process tree helpers', () => {
  const t0 = 1_000_000;
  const table = [e(1, 0, 0), e(100, 1, t0), e(101, 100, t0 + 500), e(102, 101, t0 + 900), e(200, 1, t0 + 100), e(300, 100, t0 - 60_000)];

  it('descendantsOf walks the whole tree below a pid', () => {
    expect(descendantsOf(table, 100).map((x) => x.pid).sort()).toEqual([101, 102, 300]);
    expect(descendantsOf(table, 200)).toEqual([]);
  });

  it('orphansOf keeps only processes started after we spawned the CLI, and nothing if its pid was reused', () => {
    // CLI 100 exited (gone from the table); its children still name it as their parent
    const exited = table.filter((x) => x.pid !== 100);
    expect(orphansOf(exited, 100, t0).map((x) => x.pid).sort()).toEqual([101, 102]); // 300 is older: not ours
    // pid 100 now belongs to a newer process: never touch its children
    const reused = [...exited.filter((x) => x.pid !== 101 && x.pid !== 102), e(100, 1, t0 + 30_000), e(105, 100, t0 + 31_000)];
    expect(orphansOf(reused, 100, t0)).toEqual([]);
  });

  it('reads the real process table and kills only a process that is still the same one', async () => {
    const table = await processTable();
    expect(table && table.length).toBeGreaterThan(5);
    const me = table!.find((x) => x.pid === process.pid)!;
    expect(me).toBeDefined();
    expect(Math.abs(me.createdMs - (Date.now() - process.uptime() * 1000))).toBeLessThan(120_000);
    const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { stdio: 'ignore' });
    await new Promise((r) => setTimeout(r, 500));
    const now = (await processTable())!;
    const entry = now.find((x) => x.pid === child.pid)!;
    // a snapshot entry whose creation time does not match is never killed
    expect(await killSnapshot([{ ...entry, created: '1' }], now)).toEqual([]);
    expect(child.exitCode).toBeNull();
    const exited = new Promise((r) => child.once('exit', r));
    expect(await killSnapshot([entry], now)).toEqual([child.pid]);
    await exited;
  });
});
