// Task graph: tasks with dependencies, statuses and assignment.
//
// Rules
//  - deps must reference existing tasks; no self-deps; no cycles (checked on create and update)
//  - a task can only move to `doing` when all deps are `done` and it has an assignee
//  - `done` for a task that owns a worktree happens only through an approved merge (`viaMerge`)
//  - `cancelled` is terminal for scheduling but can be retried (-> todo)
import type { Ctx } from './context.js';
import type { CiStatus, Task, TaskStatus } from './protocol.js';

export class TaskError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TaskError';
  }
}

export interface CreateTaskInput {
  title: string;
  description?: string;
  deps?: string[];
  assignee?: string;
  repoId?: string;
  goalId?: string;
  priority?: number;
  /** the worker's branch starts from this branch (e.g. a fetched pull request) instead of the base */
  startBranch?: string;
  createdBy: string;
}

export interface TaskPatch {
  title?: string;
  description?: string;
  deps?: string[];
  assignee?: string | null;
  priority?: number;
  branch?: string;
  worktree?: string;
  ci?: CiStatus;
  summary?: string;
  blockedReason?: string | null;
  repoId?: string;
}

const TRANSITIONS: Record<TaskStatus, TaskStatus[]> = {
  todo: ['doing', 'blocked', 'cancelled', 'done'],
  doing: ['review', 'blocked', 'todo', 'done', 'cancelled'],
  review: ['done', 'doing', 'blocked', 'todo', 'cancelled'],
  blocked: ['todo', 'doing', 'cancelled', 'review'],
  done: ['todo'],
  cancelled: ['todo'],
};

export class TaskGraph {
  constructor(private ctx: Ctx) {}

  private get tasks(): Task[] {
    return this.ctx.store.data.tasks;
  }

  list(): Task[] {
    return this.tasks;
  }

  get(id: string): Task | undefined {
    return this.tasks.find((t) => t.id === id);
  }

  require(id: string): Task {
    const t = this.get(id);
    if (!t) throw new TaskError(`no task ${id}`);
    return t;
  }

  forGoal(goalId: string): Task[] {
    return this.tasks.filter((t) => t.goalId === goalId);
  }

  create(input: CreateTaskInput): Task {
    const title = input.title.trim();
    if (!title) throw new TaskError('task title is empty');
    const deps = [...new Set(input.deps ?? [])];
    for (const d of deps) if (!this.get(d)) throw new TaskError(`unknown dependency ${d}`);
    const now = this.ctx.now();
    const task: Task = {
      id: this.ctx.store.nextId('t'),
      title,
      status: 'todo',
      deps,
      priority: input.priority ?? 0,
      ci: 'unknown',
      createdBy: input.createdBy,
      createdAt: now,
      updatedAt: now,
    };
    if (input.description) task.description = input.description;
    if (input.assignee) task.assignee = input.assignee;
    if (input.repoId) task.repoId = input.repoId;
    if (input.goalId) task.goalId = input.goalId;
    if (input.startBranch) task.startBranch = input.startBranch;
    this.tasks.push(task);
    this.touch(task);
    return task;
  }

  /** Patch non-status fields. */
  update(id: string, patch: TaskPatch): Task {
    const t = this.require(id);
    if (patch.deps) {
      const deps = [...new Set(patch.deps)];
      for (const d of deps) {
        if (d === id) throw new TaskError(`task ${id} cannot depend on itself`);
        if (!this.get(d)) throw new TaskError(`unknown dependency ${d}`);
      }
      if (this.wouldCycle(id, deps)) throw new TaskError(`dependency cycle via ${id}`);
      t.deps = deps;
    }
    if (patch.title !== undefined) t.title = patch.title;
    if (patch.description !== undefined) t.description = patch.description;
    if (patch.assignee !== undefined) {
      if (patch.assignee === null) delete t.assignee;
      else t.assignee = patch.assignee;
    }
    if (patch.priority !== undefined) t.priority = patch.priority;
    if (patch.branch !== undefined) t.branch = patch.branch;
    if (patch.worktree !== undefined) t.worktree = patch.worktree;
    if (patch.ci !== undefined) t.ci = patch.ci;
    if (patch.summary !== undefined) t.summary = patch.summary;
    if (patch.repoId !== undefined) t.repoId = patch.repoId;
    if (patch.blockedReason !== undefined) {
      if (patch.blockedReason === null) delete t.blockedReason;
      else t.blockedReason = patch.blockedReason;
    }
    this.touch(t);
    return t;
  }

  canTransition(t: Task, to: TaskStatus): string | undefined {
    if (t.status === to) return undefined;
    if (!TRANSITIONS[t.status].includes(to)) return `cannot move ${t.id} from ${t.status} to ${to}`;
    if (to === 'doing') {
      if (!t.assignee) return `${t.id} has no assignee`;
      const unmet = this.unmetDeps(t);
      if (unmet.length) return `${t.id} is waiting on ${unmet.join(', ')}`;
    }
    return undefined;
  }

  setStatus(
    id: string,
    to: TaskStatus,
    opts: { reason?: string; summary?: string; viaMerge?: boolean; force?: boolean } = {},
  ): Task {
    const t = this.require(id);
    if (!opts.force) {
      const err = this.canTransition(t, to);
      if (err) throw new TaskError(err);
      if (to === 'done' && t.worktree && !opts.viaMerge) {
        throw new TaskError(`${t.id} has a worktree; it becomes done only through an approved merge`);
      }
    }
    t.status = to;
    if (to === 'blocked') t.blockedReason = opts.reason ?? t.blockedReason ?? 'blocked';
    else delete t.blockedReason;
    if (opts.summary) t.summary = opts.summary;
    this.touch(t);
    return t;
  }

  unmetDeps(t: Task): string[] {
    return t.deps.filter((d) => this.get(d)?.status !== 'done');
  }

  depsSatisfied(t: Task): boolean {
    return this.unmetDeps(t).length === 0;
  }

  /** Tasks that can start now: todo + deps done. Highest priority first, then oldest. */
  ready(goalId?: string): Task[] {
    return this.tasks
      .filter((t) => t.status === 'todo' && (!goalId || t.goalId === goalId) && this.depsSatisfied(t))
      .sort((a, b) => b.priority - a.priority || a.createdAt - b.createdAt || a.id.localeCompare(b.id, 'en', { numeric: true }));
  }

  dependents(id: string): Task[] {
    return this.tasks.filter((t) => t.deps.includes(id));
  }

  /** Would giving task `id` these deps create a cycle? */
  wouldCycle(id: string, deps: string[]): boolean {
    const seen = new Set<string>();
    const stack = [...deps];
    while (stack.length) {
      const cur = stack.pop()!;
      if (cur === id) return true;
      if (seen.has(cur)) continue;
      seen.add(cur);
      const t = this.get(cur);
      if (t) stack.push(...t.deps);
    }
    return false;
  }

  /** Goal progress in 0..1: done=1, review=0.75, doing=0.4, blocked/todo=0 (cancelled excluded). */
  progress(goalId: string): number {
    const ts = this.forGoal(goalId).filter((t) => t.status !== 'cancelled');
    if (!ts.length) return 0;
    const w: Record<TaskStatus, number> = { done: 1, review: 0.75, doing: 0.4, todo: 0, blocked: 0, cancelled: 0 };
    return Math.round((ts.reduce((s, t) => s + w[t.status], 0) / ts.length) * 1000) / 1000;
  }

  /** All (non-cancelled) tasks of the goal are done. Blocked tasks that nobody can do count as open. */
  goalComplete(goalId: string): boolean {
    const ts = this.forGoal(goalId).filter((t) => t.status !== 'cancelled');
    return ts.length > 0 && ts.every((t) => t.status === 'done');
  }

  private touch(t: Task): void {
    t.updatedAt = this.ctx.now();
    this.ctx.store.markDirty();
    this.ctx.emit({ type: 'task.upsert', task: { ...t, deps: [...t.deps] } });
  }
}
