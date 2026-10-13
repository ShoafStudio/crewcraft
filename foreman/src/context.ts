// Shared plumbing handed to every subsystem: the store, an outbound-message sink and a clock.
import type { Outbound } from './protocol.js';
import type { Store } from './store.js';

export interface Logger {
  info(msg: string): void;
  warn(msg: string): void;
  error(msg: string): void;
  debug(msg: string): void;
}

export interface Ctx {
  store: Store;
  /** Broadcast a protocol message to all connected clients. */
  emit(msg: Outbound): void;
  now(): number;
  log: Logger;
}

export function consoleLogger(prefix = 'foreman', opts: { debug?: boolean; quiet?: boolean } = {}): Logger {
  const stamp = () => new Date().toTimeString().slice(0, 8); // local time
  return {
    info: (m) => {
      if (!opts.quiet) console.log(`${stamp()} [${prefix}] ${m}`);
    },
    warn: (m) => {
      if (!opts.quiet) console.warn(`${stamp()} [${prefix}] WARN ${m}`);
    },
    error: (m) => console.error(`${stamp()} [${prefix}] ERROR ${m}`),
    debug: (m) => {
      if (opts.debug) console.log(`${stamp()} [${prefix}] debug ${m}`);
    },
  };
}

export const silentLogger: Logger = { info() {}, warn() {}, error() {}, debug() {} };
