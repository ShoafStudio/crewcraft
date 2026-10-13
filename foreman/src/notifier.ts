// Notifier: tells the user (outside the game) that a decision is waiting.
//
//  - Windows toast via PowerShell + Windows.UI.Notifications; macOS notification via osascript;
//    Linux notification via notify-send (libnotify)
//  - console bell on the Foreman's terminal (only when stdout is a TTY)
//  - rate-limited and coalesced: at most one toast per `minIntervalMs`; decisions that arrive
//    inside the window are summarised in the next toast
//  - disabled with config.notify=false / AGENTCRAFT_NOTIFY=0; tests inject a fake `spawnToast`
import { spawn } from 'node:child_process';
import type { Logger } from './context.js';
import { truncate } from './util/text.js';

export interface NotifierOptions {
  enabled: boolean;
  /** toast plays the default notification sound unless silent */
  silent?: boolean;
  minIntervalMs?: number;
  bell?: boolean;
  log?: Logger;
  /** injectable for tests; default shows a native desktop notification */
  spawnToast?: (title: string, body: string, silent: boolean) => Promise<boolean>;
  now?: () => number;
}

function xmlEscape(s: string): string {
  return s.replace(/[<>&'"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', "'": '&apos;', '"': '&quot;' })[c]!);
}

const POWERSHELL_AUMID = '{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe';

/** Show a Windows toast. Resolves true if PowerShell exited 0. Never steals focus. */
export function showWindowsToast(title: string, body: string, silent: boolean): Promise<boolean> {
  if (process.platform !== 'win32') return Promise.resolve(false);
  const xml = `<toast><visual><binding template="ToastGeneric"><text>${xmlEscape(title)}</text><text>${xmlEscape(body)}</text></binding></visual>${silent ? '<audio silent="true"/>' : '<audio src="ms-winsoundevent:Notification.Reminder"/>'}</toast>`;
  // Pass the XML base64-encoded to avoid any quoting issues.
  const b64 = Buffer.from(xml, 'utf8').toString('base64');
  const script = [
    '$ErrorActionPreference = "Stop"',
    '[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null',
    '[Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom.XmlDocument, ContentType = WindowsRuntime] | Out-Null',
    `$xml = [System.Text.Encoding]::UTF8.GetString([System.Convert]::FromBase64String("${b64}"))`,
    '$doc = New-Object Windows.Data.Xml.Dom.XmlDocument',
    '$doc.LoadXml($xml)',
    '$toast = New-Object Windows.UI.Notifications.ToastNotification $doc',
    '$toast.Tag = "agentcraft"',
    '$toast.Group = "agentcraft"',
    `[Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier("${POWERSHELL_AUMID}").Show($toast)`,
  ].join('; ');
  const encoded = Buffer.from(script, 'utf16le').toString('base64');
  return runNotifier('powershell.exe', ['-NoProfile', '-NonInteractive', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded]);
}

/** Resolves true if the notifier command exited 0; never rejects. */
function runNotifier(command: string, args: string[]): Promise<boolean> {
  return new Promise((resolve) => {
    const child = spawn(command, args, { windowsHide: true, stdio: 'ignore' });
    const timer = setTimeout(() => child.kill(), 15_000);
    child.on('error', () => {
      clearTimeout(timer);
      resolve(false);
    });
    child.on('close', (code) => {
      clearTimeout(timer);
      resolve(code === 0);
    });
  });
}

/** Display a macOS Notification Center banner without passing text through a shell. */
export function showMacNotification(title: string, body: string, silent: boolean): Promise<boolean> {
  if (process.platform !== 'darwin') return Promise.resolve(false);
  const script = silent
    ? 'display notification (item 2 of argv) with title (item 1 of argv)'
    : 'display notification (item 2 of argv) with title (item 1 of argv) sound name "Glass"';
  return runNotifier('osascript', ['-e', 'on run argv', '-e', script, '-e', 'end run', '--', title, body]);
}

/** Desktop notification through the freedesktop notification daemon (notify-send from libnotify). */
export function showLinuxNotification(title: string, body: string, silent: boolean): Promise<boolean> {
  if (process.platform !== 'linux') return Promise.resolve(false);
  const args = ['--app-name=AgentCraft', '--urgency=normal', ...(silent ? ['--hint=boolean:suppress-sound:true'] : ['--hint=string:sound-name:message-new-instant']), '--', title, body];
  return runNotifier('notify-send', args);
}

export function showDesktopNotification(title: string, body: string, silent: boolean): Promise<boolean> {
  if (process.platform === 'win32') return showWindowsToast(title, body, silent);
  if (process.platform === 'darwin') return showMacNotification(title, body, silent);
  if (process.platform === 'linux') return showLinuxNotification(title, body, silent);
  return Promise.resolve(false);
}

export class Notifier {
  private lastShown = 0;
  private pending: string[] = [];
  private timer: NodeJS.Timeout | undefined;
  readonly sent: Array<{ title: string; body: string; ts: number }> = [];
  private readonly minInterval: number;
  private readonly now: () => number;

  constructor(private opts: NotifierOptions) {
    this.minInterval = opts.minIntervalMs ?? 20_000;
    this.now = opts.now ?? Date.now;
  }

  get enabled(): boolean {
    return this.opts.enabled;
  }

  setEnabled(on: boolean): void {
    this.opts.enabled = on;
  }

  /** Queue a "you are needed" notification. */
  needUser(text: string): void {
    if (this.opts.bell !== false && process.stdout.isTTY) process.stdout.write('\x07');
    if (!this.opts.enabled) return;
    this.pending.push(truncate(text, 160));
    this.schedule();
  }

  private schedule(): void {
    if (this.timer) return;
    const wait = Math.max(0, this.lastShown + this.minInterval - this.now());
    this.timer = setTimeout(() => {
      this.timer = undefined;
      void this.flush();
    }, wait);
    this.timer.unref?.();
  }

  async flush(): Promise<void> {
    if (!this.pending.length) return;
    const items = this.pending.splice(0);
    const title = items.length === 1 ? 'AgentCraft needs you' : `AgentCraft: ${items.length} decisions waiting`;
    const body = items.length === 1 ? items[0]! : items.slice(-3).map((t) => `• ${t}`).join('\n');
    this.lastShown = this.now();
    this.sent.push({ title, body, ts: this.lastShown });
    const show = this.opts.spawnToast ?? showDesktopNotification;
    try {
      const ok = await show(title, body, this.opts.silent ?? false);
      if (!ok) this.opts.log?.warn('desktop notification failed');
    } catch (e) {
      this.opts.log?.warn(`toast notification failed: ${(e as Error).message}`);
    }
  }

  dispose(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = undefined;
  }
}
