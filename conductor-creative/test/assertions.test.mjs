/* assertions.test.mjs — the pure math half of assertions.js: contrast,
 * relative luminance, spill and safe-zone intrusion, exercised with fixture
 * rects/colors, no DOM. `runAssertions` itself needs a real browser
 * (getBoundingClientRect layout, getComputedStyle, Image loading,
 * document.fonts) and is exercised indirectly by test/job.test.mjs's
 * end-to-end render instead.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { relativeLuminance, parseRgb, contrastRatio, spillDetect, safeZoneIntrusion } from '../assertions.js';

/* ── relativeLuminance / contrastRatio ─────────────────────────────────── */

test('relativeLuminance: black is 0, white is 1', () => {
  assert.equal(relativeLuminance([0, 0, 0]), 0);
  assert.equal(Math.round(relativeLuminance([255, 255, 255]) * 1000) / 1000, 1);
});

test('contrastRatio: black on white is 21:1, the WCAG maximum', () => {
  assert.equal(contrastRatio([0, 0, 0], [255, 255, 255]), 21);
});

test('contrastRatio: identical colors are 1:1', () => {
  assert.equal(contrastRatio([100, 100, 100], [100, 100, 100]), 1);
});

test('contrastRatio: order of fg/bg does not matter', () => {
  assert.equal(contrastRatio([20, 20, 20], [230, 230, 230]), contrastRatio([230, 230, 230], [20, 20, 20]));
});

test('contrastRatio: a real near-failing pair rounds to one decimal place', () => {
  // rgb(255,90,95) coral-ish accent on a dark rgb(28,28,30) background.
  const ratio = contrastRatio([255, 90, 95], [28, 28, 30]);
  assert.ok(ratio > 3, `expected > 3:1, got ${ratio}`);
  assert.equal(Math.round(ratio * 10), ratio * 10);
});

test('contrastRatio: light-on-light fails the 3:1 floor', () => {
  const ratio = contrastRatio([240, 240, 245], [255, 255, 255]);
  assert.ok(ratio < 3, `expected < 3:1, got ${ratio}`);
});

/* ── parseRgb ──────────────────────────────────────────────────────────── */

test('parseRgb: parses rgb() and rgba(), ignoring alpha', () => {
  assert.deepEqual(parseRgb('rgb(255, 90, 95)'), [255, 90, 95]);
  assert.deepEqual(parseRgb('rgba(255, 90, 95, 0.5)'), [255, 90, 95]);
});

test('parseRgb: returns null for anything else', () => {
  assert.equal(parseRgb('transparent'), null);
  assert.equal(parseRgb(''), null);
  assert.equal(parseRgb(undefined), null);
  assert.equal(parseRgb('#ff5a5f'), null); // resolveCssColor's job, not parseRgb's
});

/* ── spillDetect ───────────────────────────────────────────────────────── */

const board = { top: 0, bottom: 1920, right: 1080 };

test('spillDetect: a node fully inside the board is clean', () => {
  const nodes = [{ name: 'cc-headline', rect: { top: 100, bottom: 400, right: 900 } }];
  assert.deepEqual(spillDetect(board, nodes), []);
});

test('spillDetect: catches over-top, past-bottom and past-right independently', () => {
  const nodes = [
    { name: 'cc-lockup', rect: { top: -10, bottom: 50, right: 200 } },
    { name: 'cc-cta', rect: { top: 1800, bottom: 1950, right: 900 } },
    { name: 'cc-body', rect: { top: 200, bottom: 400, right: 1200 } },
  ];
  const issues = spillDetect(board, nodes);
  assert.equal(issues.length, 3);
  assert.match(issues[0], /cc-lockup over top by 10px/);
  assert.match(issues[1], /cc-cta past bottom by 30px/);
  assert.match(issues[2], /cc-body past right by 120px/);
});

test('spillDetect: a 1px tolerance absorbs subpixel rounding', () => {
  const nodes = [{ name: 'cc-headline', rect: { top: -0.5, bottom: 1920.5, right: 1080.5 } }];
  assert.deepEqual(spillDetect(board, nodes), []);
});

/* ── safeZoneIntrusion ─────────────────────────────────────────────────── */

test('safeZoneIntrusion: no bottom reserve means nothing can intrude', () => {
  const nodes = [{ name: 'cc-cta', rect: { top: 1800, bottom: 1919, right: 900 } }];
  assert.deepEqual(safeZoneIntrusion(board, nodes, { bottom: 0 }), []);
});

test('safeZoneIntrusion: copy that clears the reserved zone is clean', () => {
  // Board bottom 1920, safe.bottom 430 -> the zone starts at y=1490.
  const nodes = [{ name: 'cc-cta', rect: { top: 1300, bottom: 1480, right: 900 } }];
  assert.deepEqual(safeZoneIntrusion(board, nodes, { bottom: 430 }), []);
});

test('safeZoneIntrusion: copy crossing into the reserved zone is reported with its depth', () => {
  const nodes = [{ name: 'cc-cta', rect: { top: 1400, bottom: 1520, right: 900 } }]; // 30px into the 430px zone
  const issues = safeZoneIntrusion(board, nodes, { bottom: 430 });
  assert.equal(issues.length, 1);
  assert.match(issues[0], /cc-cta intrudes 30px into the bottom safe zone \(reserves 430px\)/);
});

test('safeZoneIntrusion: a 1px tolerance absorbs subpixel rounding at the boundary', () => {
  const nodes = [{ name: 'cc-cta', rect: { top: 1400, bottom: 1490.5, right: 900 } }];
  assert.deepEqual(safeZoneIntrusion(board, nodes, { bottom: 430 }), []);
});
