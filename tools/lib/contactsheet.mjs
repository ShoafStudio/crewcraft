// Contact sheet: one PNG montage of a QA run (thumbnails + shot names + status), pure JS (pngjs).
//
//   import { contactSheet } from './lib/contactsheet.mjs';
//   contactSheet({ out: 'sheet.png', title: 'QA run 2026...', tiles: [{ name, path?, status, note? }] });
//
// Text uses a built-in 5x7 bitmap font (upper case ASCII), scaled 2x/3x, palette from
// docs/visual-bar.md (Ink background, Cream text, status colors).

import fs from 'node:fs';
import { PNG } from 'pngjs';

const hex = (h) => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
export const COLORS = {
  bg: hex('#1F1E1D'),      // Ink
  tile: hex('#2B2926'),
  frame: hex('#3B2A20'),   // Walnut
  text: hex('#F4EFE6'),    // Cream
  dim: hex('#9C9488'),     // idle grey
  ok: hex('#8FA98B'),      // Sage
  skipped: hex('#C9A227'), // Brass
  failed: hex('#C2413B'),
  warn: hex('#D97757'),    // Clay
};

// 5x7 glyphs, one number per row, bit 4 = leftmost pixel.
const G = {
  ' ': [0, 0, 0, 0, 0, 0, 0],
  A: [0x0e, 0x11, 0x11, 0x1f, 0x11, 0x11, 0x11], B: [0x1e, 0x11, 0x11, 0x1e, 0x11, 0x11, 0x1e],
  C: [0x0e, 0x11, 0x10, 0x10, 0x10, 0x11, 0x0e], D: [0x1c, 0x12, 0x11, 0x11, 0x11, 0x12, 0x1c],
  E: [0x1f, 0x10, 0x10, 0x1e, 0x10, 0x10, 0x1f], F: [0x1f, 0x10, 0x10, 0x1e, 0x10, 0x10, 0x10],
  G: [0x0e, 0x11, 0x10, 0x17, 0x11, 0x11, 0x0f], H: [0x11, 0x11, 0x11, 0x1f, 0x11, 0x11, 0x11],
  I: [0x0e, 0x04, 0x04, 0x04, 0x04, 0x04, 0x0e], J: [0x07, 0x02, 0x02, 0x02, 0x02, 0x12, 0x0c],
  K: [0x11, 0x12, 0x14, 0x18, 0x14, 0x12, 0x11], L: [0x10, 0x10, 0x10, 0x10, 0x10, 0x10, 0x1f],
  M: [0x11, 0x1b, 0x15, 0x15, 0x11, 0x11, 0x11], N: [0x11, 0x11, 0x19, 0x15, 0x13, 0x11, 0x11],
  O: [0x0e, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0e], P: [0x1e, 0x11, 0x11, 0x1e, 0x10, 0x10, 0x10],
  Q: [0x0e, 0x11, 0x11, 0x11, 0x15, 0x12, 0x0d], R: [0x1e, 0x11, 0x11, 0x1e, 0x14, 0x12, 0x11],
  S: [0x0f, 0x10, 0x10, 0x0e, 0x01, 0x01, 0x1e], T: [0x1f, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04],
  U: [0x11, 0x11, 0x11, 0x11, 0x11, 0x11, 0x0e], V: [0x11, 0x11, 0x11, 0x11, 0x11, 0x0a, 0x04],
  W: [0x11, 0x11, 0x11, 0x15, 0x15, 0x15, 0x0a], X: [0x11, 0x11, 0x0a, 0x04, 0x0a, 0x11, 0x11],
  Y: [0x11, 0x11, 0x11, 0x0a, 0x04, 0x04, 0x04], Z: [0x1f, 0x01, 0x02, 0x04, 0x08, 0x10, 0x1f],
  0: [0x0e, 0x11, 0x13, 0x15, 0x19, 0x11, 0x0e], 1: [0x04, 0x0c, 0x04, 0x04, 0x04, 0x04, 0x0e],
  2: [0x0e, 0x11, 0x01, 0x02, 0x04, 0x08, 0x1f], 3: [0x1f, 0x02, 0x04, 0x02, 0x01, 0x11, 0x0e],
  4: [0x02, 0x06, 0x0a, 0x12, 0x1f, 0x02, 0x02], 5: [0x1f, 0x10, 0x1e, 0x01, 0x01, 0x11, 0x0e],
  6: [0x06, 0x08, 0x10, 0x1e, 0x11, 0x11, 0x0e], 7: [0x1f, 0x01, 0x02, 0x04, 0x08, 0x08, 0x08],
  8: [0x0e, 0x11, 0x11, 0x0e, 0x11, 0x11, 0x0e], 9: [0x0e, 0x11, 0x11, 0x0f, 0x01, 0x02, 0x0c],
  _: [0, 0, 0, 0, 0, 0, 0x1f], '-': [0, 0, 0, 0x1f, 0, 0, 0], '.': [0, 0, 0, 0, 0, 0x0c, 0x0c],
  ',': [0, 0, 0, 0, 0x0c, 0x04, 0x08], ':': [0, 0x0c, 0x0c, 0, 0x0c, 0x0c, 0], ';': [0, 0x0c, 0x0c, 0, 0x0c, 0x04, 0x08],
  '/': [0, 0x01, 0x02, 0x04, 0x08, 0x10, 0], '\\': [0, 0x10, 0x08, 0x04, 0x02, 0x01, 0],
  '(': [0x02, 0x04, 0x08, 0x08, 0x08, 0x04, 0x02], ')': [0x08, 0x04, 0x02, 0x02, 0x02, 0x04, 0x08],
  '[': [0x0e, 0x08, 0x08, 0x08, 0x08, 0x08, 0x0e], ']': [0x0e, 0x02, 0x02, 0x02, 0x02, 0x02, 0x0e],
  '#': [0x0a, 0x0a, 0x1f, 0x0a, 0x1f, 0x0a, 0x0a], '+': [0, 0x04, 0x04, 0x1f, 0x04, 0x04, 0],
  '=': [0, 0, 0x1f, 0, 0x1f, 0, 0], '%': [0x18, 0x19, 0x02, 0x04, 0x08, 0x13, 0x03],
  '!': [0x04, 0x04, 0x04, 0x04, 0x04, 0, 0x04], '?': [0x0e, 0x11, 0x01, 0x02, 0x04, 0, 0x04],
  "'": [0x0c, 0x04, 0x08, 0, 0, 0, 0], '"': [0x0a, 0x0a, 0x0a, 0, 0, 0, 0],
  '<': [0x02, 0x04, 0x08, 0x10, 0x08, 0x04, 0x02], '>': [0x08, 0x04, 0x02, 0x01, 0x02, 0x04, 0x08],
  '*': [0, 0x04, 0x15, 0x0e, 0x15, 0x04, 0], '@': [0x0e, 0x11, 0x01, 0x0d, 0x15, 0x15, 0x0e],
  '&': [0x0c, 0x12, 0x14, 0x08, 0x15, 0x12, 0x0d], '|': [0x04, 0x04, 0x04, 0x04, 0x04, 0x04, 0x04],
  '{': [0x02, 0x04, 0x04, 0x08, 0x04, 0x04, 0x02], '}': [0x08, 0x04, 0x04, 0x02, 0x04, 0x04, 0x08],
  '~': [0, 0, 0x08, 0x15, 0x02, 0, 0], $: [0x04, 0x0f, 0x14, 0x0e, 0x05, 0x1e, 0x04],
};

export class Canvas {
  constructor(width, height, color = COLORS.bg) {
    this.png = new PNG({ width, height });
    this.width = width;
    this.height = height;
    this.fill(0, 0, width, height, color);
  }

  fill(x, y, w, h, [r, g, b]) {
    const d = this.png.data;
    const x0 = Math.max(0, x), y0 = Math.max(0, y);
    const x1 = Math.min(this.width, x + w), y1 = Math.min(this.height, y + h);
    for (let yy = y0; yy < y1; yy++) {
      let i = (yy * this.width + x0) * 4;
      for (let xx = x0; xx < x1; xx++, i += 4) {
        d[i] = r; d[i + 1] = g; d[i + 2] = b; d[i + 3] = 255;
      }
    }
  }

  /** Draw text; returns the x after the last glyph. Text is upper-cased; unknown chars draw as '?'. */
  text(x, y, str, color = COLORS.text, scale = 2, maxWidth = Infinity) {
    const adv = 6 * scale;
    let cx = x;
    for (const ch0 of String(str).toUpperCase()) {
      if (cx + 5 * scale - x > maxWidth) break;
      const glyph = G[ch0] ?? G['?'];
      for (let row = 0; row < 7; row++) {
        const bits = glyph[row];
        for (let col = 0; col < 5; col++) {
          if (bits & (0x10 >> col)) this.fill(cx + col * scale, y + row * scale, scale, scale, color);
        }
      }
      cx += adv;
    }
    return cx;
  }

  /** Paste an RGBA image scaled into the box (w x h) with area averaging. */
  blitScaled(src, dx, dy, w, h) {
    const sx = src.width / w, sy = src.height / h;
    const s = src.data, d = this.png.data;
    for (let y = 0; y < h; y++) {
      const ys0 = Math.floor(y * sy), ys1 = Math.max(ys0 + 1, Math.floor((y + 1) * sy));
      const ty = dy + y;
      if (ty < 0 || ty >= this.height) continue;
      for (let x = 0; x < w; x++) {
        const xs0 = Math.floor(x * sx), xs1 = Math.max(xs0 + 1, Math.floor((x + 1) * sx));
        let r = 0, g = 0, b = 0, n = 0;
        for (let yy = ys0; yy < ys1 && yy < src.height; yy++) {
          let i = (yy * src.width + xs0) * 4;
          for (let xx = xs0; xx < xs1 && xx < src.width; xx++, i += 4) {
            r += s[i]; g += s[i + 1]; b += s[i + 2]; n++;
          }
        }
        const tx = dx + x;
        if (tx < 0 || tx >= this.width || !n) continue;
        const o = (ty * this.width + tx) * 4;
        d[o] = r / n; d[o + 1] = g / n; d[o + 2] = b / n; d[o + 3] = 255;
      }
    }
  }

  write(file) {
    fs.writeFileSync(file, PNG.sync.write(this.png));
  }
}

function wrap(text, maxChars) {
  const words = String(text).split(/\s+/);
  const lines = [];
  let cur = '';
  for (const w0 of words) {
    let w = w0;
    while (w.length > maxChars) {
      if (cur) { lines.push(cur); cur = ''; }
      lines.push(w.slice(0, maxChars));
      w = w.slice(maxChars);
    }
    if (!cur) cur = w;
    else if (cur.length + 1 + w.length <= maxChars) cur += ' ' + w;
    else { lines.push(cur); cur = w; }
  }
  if (cur) lines.push(cur);
  return lines;
}

/**
 * @param {{out:string, title:string, subtitle?:string, tiles:{name:string, title?:string, path?:string, status:'ok'|'skipped'|'failed', note?:string, noteColor?:number[]}[], columns?:number, thumbWidth?:number}} o
 */
export function contactSheet(o) {
  const cols = o.columns ?? 3;
  const tw = o.thumbWidth ?? 640;
  const th = Math.round((tw * 9) / 16);
  const pad = 16;
  const labelH = 52;
  const headerH = 76;
  const rows = Math.max(1, Math.ceil(o.tiles.length / cols));
  const W = pad + cols * (tw + pad);
  const H = headerH + rows * (th + labelH + pad) + pad;
  const c = new Canvas(W, H);
  c.text(pad, 18, o.title, COLORS.text, 3, W - 2 * pad);
  if (o.subtitle) c.text(pad, 48, o.subtitle, COLORS.dim, 2, W - 2 * pad);

  o.tiles.forEach((t, i) => {
    const col = i % cols, row = Math.floor(i / cols);
    const x = pad + col * (tw + pad);
    const y = headerH + row * (th + labelH + pad);
    const statusColor = COLORS[t.status] ?? COLORS.dim;
    c.fill(x - 2, y - 2, tw + 4, th + labelH + 4, COLORS.frame);
    c.fill(x, y, tw, th, COLORS.tile);
    let drew = false;
    if (t.path && fs.existsSync(t.path)) {
      try {
        const img = PNG.sync.read(fs.readFileSync(t.path));
        c.blitScaled(img, x, y, tw, th);
        drew = true;
      } catch (e) {
        t.note = `${t.note ? t.note + '; ' : ''}unreadable PNG: ${e.message}`;
      }
    }
    if (!drew) {
      const label = t.status === 'ok' ? 'NO IMAGE' : t.status.toUpperCase();
      c.text(x + 24, y + 24, label, statusColor, 4);
      const maxChars = Math.floor((tw - 48) / 12);
      let ly = y + 80;
      for (const ln of wrap(t.title ?? '', maxChars).slice(0, 3)) { c.text(x + 24, ly, ln, COLORS.dim, 2, tw - 48); ly += 22; }
      if (t.title) ly += 10;
      for (const ln of wrap(t.note ?? '', maxChars).slice(0, 8)) { c.text(x + 24, ly, ln, COLORS.text, 2, tw - 48); ly += 22; }
    }
    // label bar: status chip + name, then the note (warnings / camera source)
    c.fill(x, y + th, tw, labelH, COLORS.bg);
    c.fill(x, y + th, 8, labelH, statusColor);
    c.text(x + 18, y + th + 8, t.name, COLORS.text, 2, tw - 30);
    if (t.note && drew) c.text(x + 18, y + th + 30, t.note, t.noteColor ?? COLORS.warn, 2, tw - 30);
    else c.text(x + 18, y + th + 30, t.status, statusColor, 2, tw - 30);
  });
  c.write(o.out);
  return { path: o.out, width: W, height: H };
}
