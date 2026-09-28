#!/usr/bin/env node
/* One-off generator for the tiny fixture PNGs under scripts/fixtures/nexus-social/assets/.
 * Not used at test time - run this again by hand only if a fixture needs to change size or
 * colour. Keeps scripts/import-nexus-social.mjs itself free of any PNG-writing code (it only
 * ever *reads* image headers), and keeps the test suite dependency-free (no canvas/sharp/etc).
 */
import { deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';
import { join } from 'node:path';

function crc32(buf) {
  let c;
  const table = crc32.table || (crc32.table = (() => {
    const t = new Uint32Array(256);
    for (let n = 0; n < 256; n++) {
      c = n;
      for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
      t[n] = c;
    }
    return t;
  })());
  let crc = 0xffffffff;
  for (let i = 0; i < buf.length; i++) crc = table[(crc ^ buf[i]) & 0xff] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crc]);
}

export function makePng(width, height, [r, g, b]) {
  const sig = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  const ihdrData = Buffer.alloc(13);
  ihdrData.writeUInt32BE(width, 0);
  ihdrData.writeUInt32BE(height, 4);
  ihdrData[8] = 8; // bit depth
  ihdrData[9] = 2; // colour type: RGB
  ihdrData[10] = 0;
  ihdrData[11] = 0;
  ihdrData[12] = 0;
  const ihdr = chunk('IHDR', ihdrData);

  const rowBytes = 1 + width * 3; // filter byte + RGB
  const raw = Buffer.alloc(rowBytes * height);
  for (let y = 0; y < height; y++) {
    const rowStart = y * rowBytes;
    raw[rowStart] = 0; // filter: none
    for (let x = 0; x < width; x++) {
      const px = rowStart + 1 + x * 3;
      raw[px] = r; raw[px + 1] = g; raw[px + 2] = b;
    }
  }
  const idat = chunk('IDAT', deflateSync(raw));
  const iend = chunk('IEND', Buffer.alloc(0));
  return Buffer.concat([sig, ihdr, idat, iend]);
}

const BADGE_SVG = `<svg xmlns="http://www.w3.org/2000/svg" width="240" height="80"><rect width="240" height="80" fill="black"/></svg>\n`;

if (import.meta.url === `file://${process.argv[1]}`) {
  const dir = join(import.meta.dirname, 'nexus-social', 'assets');
  writeFileSync(join(dir, 'lifestyle', 'fixture-hero.png'), makePng(2200, 1400, [220, 90, 90]));
  writeFileSync(join(dir, 'lifestyle', 'fixture-story-1.png'), makePng(2200, 1400, [90, 150, 220]));
  writeFileSync(join(dir, 'lifestyle', 'fixture-story-2.png'), makePng(2200, 1400, [90, 220, 150]));
  writeFileSync(join(dir, 'lifestyle', 'fixture-variant.png'), makePng(2200, 1400, [220, 200, 90]));
  writeFileSync(join(dir, 'lifestyle', 'fixture-blocked.png'), makePng(2200, 1400, [90, 90, 90]));
  // Names below match the exact relative paths BRAND_IMAGE_SLOTS in import-nexus-social.mjs
  // looks for, so the fixture source directory works as a stand-in for the real nexus checkout.
  writeFileSync(join(dir, 'brand', 'rexipe-wordmark.png'), makePng(400, 100, [20, 20, 20]));
  writeFileSync(join(dir, 'brand', 'rexipe-wordmark-light.png'), makePng(400, 100, [250, 250, 250]));
  writeFileSync(join(dir, 'mascot', 'app-icon.png'), makePng(512, 512, [255, 90, 95]));
  writeFileSync(join(dir, 'appstore', 'app-store-badge-black-en-us.svg'), BADGE_SVG);
  console.log('Wrote fixture images under', dir);
}
