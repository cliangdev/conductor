/* render-dom.test.mjs — renderBoard's DOM structure, using linkedom as a
 * lightweight DOM shim (this package has no browser test runner; linkedom is
 * a devDependency used only here). linkedom does not run layout, so tests
 * here check structure (classes, attributes, custom properties, presence or
 * absence of nodes), never measured sizes — fitBoard's actual auto-fit math
 * is exercised in a real browser by the frontend's own tests and, later, by
 * the T3 Playwright job.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseHTML } from 'linkedom';
import { resolveAd, renderBoard } from '../render.js';
import { placements as PLACEMENTS } from '../placements.js';
import { layouts as LAYOUTS } from '../layouts/index.js';

const { window } = parseHTML('<!doctype html><html><head></head><body></body></html>');
globalThis.document = window.document;

function board(creative, placementKey, brand) {
  const ad = resolveAd(creative, PLACEMENTS, LAYOUTS);
  return renderBoard(ad, placementKey || '9x16', PLACEMENTS, LAYOUTS, brand || {});
}

/* ── the P0-2 invariant: no brand, no lockup, no badge ────────────────────── */

test('renderBoard: a brand with no logos and no ctaClaim renders no logo image and no cta row', () => {
  const b = board({ layout: 'bleed', headline: 'Plan the week in *one sentence*.' }, '4x5', {});
  assert.equal(b.querySelector('.cc-lockup'), null);
  assert.equal(b.querySelector('img.cc-lockup__icon, img.cc-lockup__mark'), null);
  assert.equal(b.querySelector('.cc-cta'), null);
});

test('renderBoard: with no logo, a top-UI placement keeps an invisible lockup-sized spacer', () => {
  // 9:16 has platform UI across its top edge; without the spacer the headline rises into it.
  const b = board({ layout: 'bleed', headline: 'Plan the week in *one sentence*.' }, '9x16', {});
  const spacer = b.querySelector('.cc-lockup--spacer');
  assert.ok(spacer);
  assert.equal(spacer.getAttribute('aria-hidden'), 'true');
  assert.equal(b.querySelector('img.cc-lockup__icon, img.cc-lockup__mark'), null);
  assert.ok(spacer.compareDocumentPosition(b.querySelector('.cc-headline')) & 4, 'spacer sits before the headline');
});

test('renderBoard: a brand with a mark and a wordmark renders the lockup', () => {
  const b = board(
    { layout: 'bleed', headline: 'x *y*.' }, '9x16',
    { logos: { mark: 'https://cdn.example/mark.png', wordmarkLight: 'https://cdn.example/wordmark-light.png' } }
  );
  const lockup = b.querySelector('.cc-lockup');
  assert.ok(lockup);
  assert.equal(lockup.querySelector('.cc-lockup__icon').getAttribute('src'), 'https://cdn.example/mark.png');
  assert.equal(lockup.querySelector('.cc-lockup__mark').getAttribute('src'), 'https://cdn.example/wordmark-light.png');
});

test('renderBoard: a mark with no wordmark still renders a lockup (icon only)', () => {
  const b = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', { logos: { mark: 'https://cdn.example/mark.png' } });
  const lockup = b.querySelector('.cc-lockup');
  assert.ok(lockup);
  assert.ok(lockup.querySelector('.cc-lockup__icon'));
  assert.equal(lockup.querySelector('.cc-lockup__mark'), null);
});

test('renderBoard: dark theme prefers wordmarkLight, light theme prefers wordmarkDark', () => {
  const brand = { logos: { wordmarkDark: 'https://cdn.example/dark.png', wordmarkLight: 'https://cdn.example/light.png' } };
  const dark = board({ layout: 'bleed', headline: 'x *y*.', theme: 'dark' }, '9x16', brand);
  assert.equal(dark.querySelector('.cc-lockup__mark').getAttribute('src'), 'https://cdn.example/light.png');

  const light = board({ layout: 'card', headline: 'x *y*.', theme: 'light' }, '9x16', brand);
  assert.equal(light.querySelector('.cc-lockup__mark').getAttribute('src'), 'https://cdn.example/dark.png');
});

test('renderBoard: a single wordmark is used regardless of theme when the other is absent', () => {
  const brand = { logos: { wordmarkLight: 'https://cdn.example/only.png' } };
  const light = board({ layout: 'card', headline: 'x *y*.', theme: 'light' }, '9x16', brand);
  assert.equal(light.querySelector('.cc-lockup__mark').getAttribute('src'), 'https://cdn.example/only.png');
});

/* ── lockup: 'chip' (fidelity-gap port of nexus's white-pill lockup) ──────── */

test('renderBoard: lockup "chip" adds cc-lockup--chip; plain (default) does not', () => {
  const brand = { logos: { mark: 'https://cdn.example/mark.png' } };
  const plain = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', brand);
  assert.equal(plain.querySelector('.cc-lockup--chip'), null);

  const chip = board({ layout: 'bleed', headline: 'x *y*.', lockup: 'chip' }, '9x16', brand);
  assert.ok(chip.querySelector('.cc-lockup--chip'));
});

test('renderBoard: lockup "chip" forces the dark-ink wordmark even on a dark theme', () => {
  const brand = { logos: { wordmarkDark: 'https://cdn.example/dark.png', wordmarkLight: 'https://cdn.example/light.png' } };
  const chip = board({ layout: 'bleed', headline: 'x *y*.', theme: 'dark', lockup: 'chip' }, '9x16', brand);
  assert.equal(chip.querySelector('.cc-lockup__mark').getAttribute('src'), 'https://cdn.example/dark.png');
});

test('renderBoard: a badge with no ctaClaim renders the badge alone, and vice versa', () => {
  const badgeOnly = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', { logos: { badge: 'https://cdn.example/badge.png' } });
  assert.ok(badgeOnly.querySelector('.cc-cta'));
  assert.ok(badgeOnly.querySelector('.cc-badge'));
  assert.equal(badgeOnly.querySelector('.cc-cta__note'), null);

  const claimOnly = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', { ctaClaim: 'Cancel any time.' });
  assert.ok(claimOnly.querySelector('.cc-cta'));
  assert.equal(claimOnly.querySelector('.cc-badge'), null);
  assert.equal(claimOnly.querySelector('.cc-cta__note').textContent, 'Cancel any time.');
});

/* ── accent phrase markup ──────────────────────────────────────────────── */

test('renderBoard: *phrase* becomes an <em> inside the headline', () => {
  const b = board({ layout: 'bleed', headline: 'Plan the week in *one sentence*.' });
  const h = b.querySelector('.cc-headline');
  const em = h.querySelector('em');
  assert.ok(em);
  assert.equal(em.textContent, 'one sentence');
  assert.equal(h.textContent, 'Plan the week in one sentence.');
});

test('renderBoard: a headline with no asterisks renders as plain text, no <em>', () => {
  const b = board({ layout: 'bleed', headline: 'No accent phrase here.' });
  const h = b.querySelector('.cc-headline');
  assert.equal(h.querySelector('em'), null);
  assert.equal(h.textContent, 'No accent phrase here.');
});

/* ── brand tokens land as scoped custom properties, never :root ──────────── */

test('renderBoard: brand tokens are set as inline custom properties on the board itself', () => {
  const b = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', { tokens: { accent: '#123456' } });
  assert.equal(b.style.getPropertyValue('--cc-accent'), '#123456');
});

test('renderBoard: two boards with two different kits do not share tokens', () => {
  const brandA = { tokens: { accent: '#111111' } };
  const brandB = { tokens: { accent: '#222222' } };
  const a = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', brandA);
  const bb = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', brandB);
  assert.equal(a.style.getPropertyValue('--cc-accent'), '#111111');
  assert.equal(bb.style.getPropertyValue('--cc-accent'), '#222222');
});

test('renderBoard: no brand tokens at all sets no --cc-accent inline (tokens.css supplies the fallback)', () => {
  const b = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16', {});
  assert.equal(b.style.getPropertyValue('--cc-accent'), '');
});

/* ── photo handling ────────────────────────────────────────────────────── */

test('renderBoard: bleed (background photo) sets --cc-photo from photoUrl', () => {
  const b = board({ layout: 'bleed', headline: 'x *y*.', photoUrl: 'https://cdn.example/photo.jpg' });
  assert.equal(b.style.getPropertyValue('--cc-photo'), 'url("https://cdn.example/photo.jpg")');
});

test('renderBoard: no photoUrl on a band layout renders no <img>, no crash', () => {
  const b = board({ layout: 'stacked', headline: 'x *y*.' }, '9x16');
  assert.equal(b.querySelector('.cc-board__band img'), null);
});

/* ── structural classes and placement wiring ──────────────────────────── */

test('renderBoard: board carries the ratio and layout classes, and layoutFor\'s perPlacement override', () => {
  const b1x1 = board({ layout: 'stacked', headline: 'x *y*.' }, '1x1');
  assert.ok(b1x1.className.includes('cc-r-1x1'));
  assert.ok(b1x1.className.includes('cc-board--bleed')); // stacked's 1x1 override
});

test('renderBoard: light theme adds cc-board--light', () => {
  const b = board({ layout: 'card', headline: 'x *y*.', theme: 'light' }, '1x1');
  assert.ok(b.className.includes('cc-board--light'));
});

test('renderBoard: safe-zone dataset attributes mirror the placement', () => {
  const b = board({ layout: 'bleed', headline: 'x *y*.' }, '9x16');
  assert.equal(b.dataset.safeTop, '220');
  assert.equal(b.dataset.safeBottom, '430');
  assert.equal(b.dataset.safeRight, '140');
});

test('renderBoard: sequence frames carry cc-board--seq and cc-board--story', () => {
  const ad = { ...resolveAd({ layout: 'bleed', headline: 'x *y*.' }, PLACEMENTS, LAYOUTS), isSequence: true, isStory: true };
  const b = renderBoard(ad, 'story', PLACEMENTS, LAYOUTS, {});
  assert.ok(b.className.includes('cc-board--seq'));
  assert.ok(b.className.includes('cc-board--story'));
});
