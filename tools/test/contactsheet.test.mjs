import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { PNG } from 'pngjs';
import { contactSheet, COLORS } from '../lib/contactsheet.mjs';

test('contact sheet: grid size, scaled thumbnails, placeholder tiles', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ac-sheet-'));
  try {
    // a 192x108 "shot": left half red, right half blue
    const img = new PNG({ width: 192, height: 108 });
    for (let y = 0; y < 108; y++) for (let x = 0; x < 192; x++) {
      const i = (y * 192 + x) * 4;
      img.data[i] = x < 96 ? 220 : 10; img.data[i + 1] = 10; img.data[i + 2] = x < 96 ? 10 : 220; img.data[i + 3] = 255;
    }
    const shot = path.join(dir, 'a.png');
    fs.writeFileSync(shot, PNG.sync.write(img));
    const out = path.join(dir, 'sheet.png');
    const res = contactSheet({
      out,
      title: 'test run',
      subtitle: 'ok 1 skipped 1 failed 1',
      columns: 2,
      thumbWidth: 320,
      tiles: [
        { name: 'qa01_x', path: shot, status: 'ok', note: 'anchor cam_x' },
        { name: 'qa02_y', status: 'skipped', title: 'A shot', note: "no camera: anchor 'cam_y' not available" },
        { name: 'qa03_z', status: 'failed', note: 'dev.camera failed' },
      ],
    });
    const sheet = PNG.sync.read(fs.readFileSync(out));
    assert.equal(sheet.width, res.width);
    assert.equal(sheet.width, 16 + 2 * (320 + 16));
    assert.equal(sheet.height, 76 + 2 * (180 + 52 + 16) + 16);
    const px = (x, y) => [...sheet.data.subarray((y * sheet.width + x) * 4, (y * sheet.width + x) * 4 + 3)];
    // first tile starts at (16, 76): left quarter red, right quarter blue (scaled)
    assert.deepEqual(px(16 + 40, 76 + 90), [220, 10, 10]);
    assert.deepEqual(px(16 + 280, 76 + 90), [10, 10, 220]);
    // background is Ink
    assert.deepEqual(px(2, sheet.height - 2), COLORS.bg);
  } finally {
    fs.rmSync(dir, { recursive: true, force: true });
  }
});
