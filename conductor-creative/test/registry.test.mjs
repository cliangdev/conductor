/* registry.test.mjs — the data registries agree with each other: placements
 * and layouts each ship as a .json file (for a plain Node/backend reader) and
 * a .js module (for a bundler); this asserts the two never drift, plus the
 * shape every placement must have and that a layout's perPlacement/dropBody
 * only ever names real placements.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = join(HERE, '..');

const { placements: PLACEMENTS_JS } = await import(join(ROOT, 'placements.js'));
const PLACEMENTS_JSON = JSON.parse(readFileSync(join(ROOT, 'placements.json'), 'utf8'));
const { layoutNames: LAYOUT_NAMES_JS, layouts: LAYOUTS_JS } = await import(join(ROOT, 'layouts', 'index.js'));
const LAYOUT_NAMES_JSON = JSON.parse(readFileSync(join(ROOT, 'layouts', 'index.json'), 'utf8'));

/* ── placements.json / placements.js agree ─────────────────────────────── */

test('placements.json and placements.js export the exact same data', () => {
  assert.deepEqual(PLACEMENTS_JSON, PLACEMENTS_JS);
});

for (const [key, p] of Object.entries(PLACEMENTS_JS)) {
  test(`placement "${key}" has the full required shape`, () => {
    assert.equal(typeof p.w, 'number');
    assert.equal(typeof p.h, 'number');
    assert.equal(typeof p.label, 'string');
    assert.equal(typeof p.platform, 'string');
    assert.ok(p.safe && typeof p.safe === 'object');
    for (const k of ['top', 'bottom', 'right']) assert.equal(typeof p.safe[k], 'number', `${key}.safe.${k}`);
    assert.ok(p.fit && typeof p.fit === 'object');
    for (const k of ['max', 'min', 'lines']) assert.equal(typeof p.fit[k], 'number', `${key}.fit.${k}`);
    assert.equal(typeof p.badgeHeight, 'number');
    assert.equal(typeof p.scale, 'object');
  });
}

test('exactly 9x16, 4x5 and 1x1 are the defaults', () => {
  const defaults = Object.keys(PLACEMENTS_JS).filter((k) => PLACEMENTS_JS[k].default).sort();
  assert.deepEqual(defaults, ['1x1', '4x5', '9x16']);
});

/* ── layouts/index.json / layouts/index.js agree ───────────────────────── */

test('layouts/index.json and layouts/index.js name the same layouts', () => {
  assert.deepEqual(LAYOUT_NAMES_JSON, LAYOUT_NAMES_JS);
});

test('every layout in layouts/index.js has matching layout.json content', () => {
  for (const name of LAYOUT_NAMES_JS) {
    const onDisk = JSON.parse(readFileSync(join(ROOT, 'layouts', name, 'layout.json'), 'utf8'));
    assert.deepEqual(onDisk, LAYOUTS_JS[name], `layouts/${name}/layout.json drifted from layouts/index.js`);
  }
});

for (const name of LAYOUT_NAMES_JS) {
  test(`layout "${name}" has a folder with layout.json and layout.css`, () => {
    assert.ok(existsSync(join(ROOT, 'layouts', name, 'layout.json')), `layouts/${name}/layout.json missing`);
    assert.ok(existsSync(join(ROOT, 'layouts', name, 'layout.css')), `layouts/${name}/layout.css missing`);
  });
}

for (const name of LAYOUT_NAMES_JS) {
  const manifest = LAYOUTS_JS[name];
  test(`layout "${name}" perPlacement and dropBody name real placements`, () => {
    for (const key of Object.keys(manifest.perPlacement || {})) {
      assert.ok(PLACEMENTS_JS[key], `${name}.perPlacement references unknown placement "${key}"`);
    }
    for (const key of manifest.dropBody || []) {
      assert.ok(PLACEMENTS_JS[key], `${name}.dropBody references unknown placement "${key}"`);
    }
  });
}

/* ── no brand residue in the registries ─────────────────────────────────── */

test('placements.json carries no brand-specific colors or copy', () => {
  const raw = readFileSync(join(ROOT, 'placements.json'), 'utf8');
  assert.doesNotMatch(raw, /#FF5A5F|coral|rexipe|rex\b/i);
});

/* ── styles.css is the concatenation of its sources ────────────────────── */

// frame.html and sheet.html (so every render) load styles.css, not the individual files, and it is
// concatenated by hand: a layout.css edit that never reached it changed nothing on the page.
for (const name of ['tokens.css', 'frame.css', ...LAYOUT_NAMES_JSON.map((n) => `layouts/${n}/layout.css`)]) {
  test(`styles.css contains ${name} verbatim`, () => {
    const bundle = readFileSync(join(ROOT, 'styles.css'), 'utf8');
    assert.ok(bundle.includes(readFileSync(join(ROOT, name), 'utf8')), `${name} has drifted from styles.css; re-concatenate`);
  });
}
