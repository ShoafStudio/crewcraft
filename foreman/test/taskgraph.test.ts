import { afterEach, describe, expect, it } from 'vitest';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
let home: string;

function setup(): Harness {
  home = tempDir();
  h = makeForeman(home, ['--backend', 'sim']);
  return h;
}

afterEach(async () => {
  await h?.fm.close();
  rmrf(home);
});

describe('TaskGraph', () => {
  it('creates tasks with sequential ids and validates deps', () => {
    const { fm } = setup();
    const a = fm.tasks.create({ title: 'A', createdBy: 'marlow' });
    const b = fm.tasks.create({ title: 'B', deps: [a.id], createdBy: 'marlow' });
    expect([a.id, b.id]).toEqual(['t1', 't2']);
    expect(b.deps).toEqual(['t1']);
    expect(() => fm.tasks.create({ title: 'C', deps: ['t99'], createdBy: 'marlow' })).toThrow(/unknown dependency/);
    expect(() => fm.tasks.create({ title: '   ', createdBy: 'marlow' })).toThrow(/empty/);
  });

  it('rejects self-deps and cycles', () => {
    const { fm } = setup();
    const a = fm.tasks.create({ title: 'A', createdBy: 'marlow' });
    const b = fm.tasks.create({ title: 'B', deps: [a.id], createdBy: 'marlow' });
    const c = fm.tasks.create({ title: 'C', deps: [b.id], createdBy: 'marlow' });
    expect(() => fm.tasks.update(a.id, { deps: [a.id] })).toThrow(/itself/);
    expect(() => fm.tasks.update(a.id, { deps: [c.id] })).toThrow(/cycle/);
    expect(fm.tasks.wouldCycle(a.id, [c.id])).toBe(true);
    expect(fm.tasks.wouldCycle(c.id, [a.id])).toBe(false);
  });

  it('only starts a task when it has an assignee and its deps are done', () => {
    const { fm } = setup();
    const a = fm.tasks.create({ title: 'A', createdBy: 'marlow' });
    const b = fm.tasks.create({ title: 'B', deps: [a.id], createdBy: 'marlow' });
    expect(() => fm.tasks.setStatus(a.id, 'doing')).toThrow(/no assignee/);
    fm.tasks.update(b.id, { assignee: 'kit' });
    expect(() => fm.tasks.setStatus(b.id, 'doing')).toThrow(/waiting on t1/);
    expect(fm.tasks.ready().map((t) => t.id)).toEqual(['t1']);
    fm.tasks.update(a.id, { assignee: 'juniper' });
    fm.tasks.setStatus(a.id, 'doing');
    fm.tasks.setStatus(a.id, 'review');
    fm.tasks.setStatus(a.id, 'done');
    expect(fm.tasks.ready().map((t) => t.id)).toEqual(['t2']);
    fm.tasks.setStatus(b.id, 'doing');
    expect(fm.tasks.get(b.id)!.status).toBe('doing');
  });

  it('enforces the transition table', () => {
    const { fm } = setup();
    const a = fm.tasks.create({ title: 'A', assignee: 'kit', createdBy: 'marlow' });
    fm.tasks.setStatus(a.id, 'doing');
    fm.tasks.setStatus(a.id, 'review');
    fm.tasks.setStatus(a.id, 'done');
    expect(() => fm.tasks.setStatus(a.id, 'review')).toThrow(/cannot move/);
    fm.tasks.setStatus(a.id, 'todo'); // retry
    fm.tasks.setStatus(a.id, 'cancelled');
    expect(() => fm.tasks.setStatus(a.id, 'doing')).toThrow(/cannot move/);
  });

  it('a task with a worktree becomes done only via an approved merge', () => {
    const { fm } = setup();
    const a = fm.tasks.create({ title: 'A', assignee: 'kit', createdBy: 'marlow' });
    fm.tasks.update(a.id, { worktree: 'kit-t1' });
    fm.tasks.setStatus(a.id, 'doing');
    fm.tasks.setStatus(a.id, 'review');
    expect(() => fm.tasks.setStatus(a.id, 'done')).toThrow(/approved merge/);
    fm.tasks.setStatus(a.id, 'done', { viaMerge: true });
    expect(fm.tasks.get(a.id)!.status).toBe('done');
  });

  it('blocked tasks record a reason that clears on unblock', () => {
    const { fm } = setup();
    const a = fm.tasks.create({ title: 'A', createdBy: 'marlow' });
    fm.tasks.setStatus(a.id, 'blocked', { reason: 'needs credentials' });
    expect(fm.tasks.get(a.id)!.blockedReason).toBe('needs credentials');
    fm.tasks.setStatus(a.id, 'todo');
    expect(fm.tasks.get(a.id)!.blockedReason).toBeUndefined();
  });

  it('orders ready tasks by priority then age, and computes goal progress', () => {
    const { fm } = setup();
    const g = fm.createGoal('test goal');
    fm.setGoal(g.id, { status: 'active' });
    const a = fm.tasks.create({ title: 'A', goalId: g.id, createdBy: 'marlow', assignee: 'kit' });
    const b = fm.tasks.create({ title: 'B', goalId: g.id, createdBy: 'marlow', priority: 5 });
    const c = fm.tasks.create({ title: 'C', goalId: g.id, createdBy: 'marlow' });
    expect(fm.tasks.ready(g.id).map((t) => t.id)).toEqual([b.id, a.id, c.id]);
    fm.tasks.setStatus(a.id, 'doing');
    fm.tasks.setStatus(a.id, 'review');
    fm.tasks.setStatus(a.id, 'done');
    fm.tasks.setStatus(c.id, 'cancelled');
    expect(fm.tasks.progress(g.id)).toBe(0.5);
    expect(fm.tasks.goalComplete(g.id)).toBe(false);
    fm.tasks.update(b.id, { assignee: 'wren' });
    fm.tasks.setStatus(b.id, 'doing');
    fm.tasks.setStatus(b.id, 'done');
    expect(fm.tasks.goalComplete(g.id)).toBe(true);
  });

  it('broadcasts task.upsert and goal progress', async () => {
    const { fm, events } = setup();
    const g = fm.createGoal('broadcast goal');
    fm.setGoal(g.id, { status: 'active' });
    const a = fm.tasks.create({ title: 'A', goalId: g.id, createdBy: 'marlow', assignee: 'kit' });
    fm.tasks.setStatus(a.id, 'doing');
    fm.tasks.setStatus(a.id, 'done');
    await new Promise((r) => setTimeout(r, 10));
    expect(events.filter((e) => e.type === 'task.upsert').length).toBeGreaterThanOrEqual(3);
    const goalEvents = events.filter((e) => e.type === 'goal.upsert');
    const last = goalEvents[goalEvents.length - 1];
    expect(last && last.type === 'goal.upsert' && last.goal.status).toBe('done');
  });
});
