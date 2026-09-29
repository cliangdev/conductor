/* motion.test.mjs — motion.js's pure math (easing, motionKeyTimes,
 * backgroundMotionState) needs no DOM at all; applyMotion's element-state
 * checks use linkedom (like mount.test.mjs/render-dom.test.mjs) since it
 * reads/writes real `.style`/class DOM. No layout engine is exercised here —
 * only inline style/CSS-var values, never measured pixels.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseHTML } from 'linkedom';
import {
  applyMotion, motionKeyTimes, easeOutCubic, backgroundMotionState, seekBackgroundVideo, normalizeMotionCreative,
} from '../motion.js';
import { resolveAd, renderBoard } from '../render.js';
import { placements as PLACEMENTS } from '../placements.js';
import { layouts as LAYOUTS } from '../layouts/index.js';

const { window } = parseHTML('<!doctype html><html><head></head><body></body></html>');
globalThis.document = window.document;

const BRAND = {
  logos: { mark: 'https://cdn.example/mark.png', wordmarkLight: 'https://cdn.example/wm.png', badge: 'https://cdn.example/badge.png' },
  ctaClaim: 'Free forever',
  tokens: { accent: '#ff0000' },
};

function board(creative, placementKey = '9x16', brand = BRAND) {
  const ad = resolveAd(creative, PLACEMENTS, LAYOUTS);
  return renderBoard(ad, placementKey, PLACEMENTS, LAYOUTS, brand);
}

const CREATIVE = { layout: 'bleed', headline: 'Plan the week in *one sentence*.', body: 'Body copy here.' };

function opacityOf(el) {
  return parseFloat(el.style.opacity);
}

/* ── easeOutCubic ──────────────────────────────────────────────────────── */

test('easeOutCubic: 0 at 0, 1 at 1, monotonically increasing, always front-loaded (>= linear)', () => {
  assert.equal(easeOutCubic(0), 0);
  assert.equal(easeOutCubic(1), 1);
  assert.equal(easeOutCubic(-1), 0); // clamps
  assert.equal(easeOutCubic(2), 1); // clamps
  let prev = -1;
  for (let x = 0; x <= 1; x += 0.1) {
    const y = easeOutCubic(x);
    assert.ok(y > prev, `easeOutCubic(${x.toFixed(1)}) = ${y} should exceed the previous step`);
    assert.ok(y >= x - 1e-9, 'easeOutCubic should never fall behind linear progress');
    prev = y;
  }
});

/* ── motionKeyTimes ────────────────────────────────────────────────────── */

test('motionKeyTimes: defaults to an 8s duration, three increasing moments', () => {
  const times = motionKeyTimes({});
  assert.equal(times.length, 3);
  assert.deepEqual(times, [0.6, 4, 7.5]);
});

test('motionKeyTimes: scales with durationSec, still increasing, within [0, durationSec]', () => {
  const times = motionKeyTimes({ durationSec: 3 });
  assert.deepEqual(times, [0.6, 1.5, 2.5]);
  assert.ok(times[0] < times[1] && times[1] < times[2]);
  assert.ok(times[2] <= 3);
});

/* ── backgroundMotionState ─────────────────────────────────────────────── */

test('backgroundMotionState: zoom-in grows from scale 1 to 1.12 over the duration, no pan', () => {
  const motion = { background: { motion: 'zoom-in' } };
  assert.equal(backgroundMotionState(motion, 0, 8).scale, 1);
  assert.equal(backgroundMotionState(motion, 8, 8).scale, 1.12);
  const mid = backgroundMotionState(motion, 4, 8).scale;
  assert.ok(mid > 1 && mid < 1.12);
  assert.equal(backgroundMotionState(motion, 0, 8).panPct, 0);
});

test('backgroundMotionState: zoom-out shrinks from 1.12 to 1', () => {
  const motion = { background: { motion: 'zoom-out' } };
  assert.equal(backgroundMotionState(motion, 0, 8).scale, 1.12);
  assert.equal(backgroundMotionState(motion, 8, 8).scale, 1);
});

test('backgroundMotionState: pan-left/pan-right hold a fixed 1.08 scale and pan within +/-4%', () => {
  const left = { background: { motion: 'pan-left' } };
  assert.equal(backgroundMotionState(left, 0, 8).scale, 1.08);
  assert.equal(backgroundMotionState(left, 0, 8).panPct, 4);
  assert.equal(backgroundMotionState(left, 8, 8).panPct, -4);

  const right = { background: { motion: 'pan-right' } };
  assert.equal(backgroundMotionState(right, 0, 8).panPct, -4);
  assert.equal(backgroundMotionState(right, 8, 8).panPct, 4);
});

test('backgroundMotionState: "none" and the default ("zoom-in" when unset) behave as documented', () => {
  assert.deepEqual(backgroundMotionState({ background: { motion: 'none' } }, 4, 8), { scale: 1, panPct: 0 });
  assert.equal(backgroundMotionState({}, 8, 8).scale, 1.12); // default is zoom-in
});

/* ── applyMotion: background CSS vars land on the board regardless of preset */

test('applyMotion: sets --cc-bg-transform/--cc-bg-size/--cc-bg-pos from the background state', () => {
  const b = board({ ...CREATIVE, motion: { background: { motion: 'zoom-in' } } });
  applyMotion(b, { background: { motion: 'zoom-in' } }, 8, { durationSec: 8 });
  assert.equal(b.style.getPropertyValue('--cc-bg-transform'), 'scale(1.12) translateX(0%)');
  assert.equal(b.style.getPropertyValue('--cc-bg-size'), '112%');
  assert.match(b.style.getPropertyValue('--cc-bg-pos'), /^50% /);
});

/* ── applyMotion: fade-up (default) ───────────────────────────────────── */

test('applyMotion: fade-up headline is hidden pre-window, mid-fade inside it, final after it', () => {
  const b = board(CREATIVE);
  const headline = b.querySelector('.cc-headline');

  applyMotion(b, {}, 0, { durationSec: 8 });
  assert.equal(opacityOf(headline), 0);
  assert.equal(headline.style.transform, 'translateY(24px)');

  applyMotion(b, {}, 0.6, { durationSec: 8 });
  const mid = opacityOf(headline);
  assert.ok(mid > 0 && mid < 1, `mid-window opacity ${mid} should be strictly between 0 and 1`);

  applyMotion(b, {}, 0.9, { durationSec: 8 });
  assert.equal(opacityOf(headline), 1);
  assert.equal(headline.style.transform, 'none');
});

test('applyMotion: lockup, body and cta each fade up in their own later windows', () => {
  const b = board(CREATIVE);
  const lockup = b.querySelector('.cc-lockup');
  const body = b.querySelector('.cc-body');
  const cta = b.querySelector('.cc-cta');
  assert.ok(lockup && body && cta, 'fixture brand/creative should render a lockup, body and cta');

  applyMotion(b, {}, 0, { durationSec: 8 });
  assert.equal(opacityOf(lockup), 0);
  assert.equal(opacityOf(body), 0);
  assert.equal(opacityOf(cta), 0);
  // lockup has no translateY (contract: "0.2-0.6 fade", not fade-up).
  assert.equal(lockup.style.transform, '');

  applyMotion(b, {}, 0.6, { durationSec: 8 });
  assert.equal(opacityOf(lockup), 1, 'lockup window (0.2-0.6) should be done by 0.6s');
  assert.equal(opacityOf(body), 0, 'body window (1.2-1.7) has not started yet');

  applyMotion(b, {}, 1.7, { durationSec: 8 });
  assert.equal(opacityOf(body), 1);
  assert.equal(opacityOf(cta), 0, 'cta window (1.8-2.3) has not started yet');

  applyMotion(b, {}, 2.3, { durationSec: 8 });
  assert.equal(opacityOf(cta), 1);
});

/* ── applyMotion: 'none' preset ───────────────────────────────────────── */

test('applyMotion: preset "none" shows everything at t=0, no transforms', () => {
  const b = board(CREATIVE);
  applyMotion(b, { preset: 'none' }, 0, { durationSec: 8 });
  for (const sel of ['.cc-headline', '.cc-lockup', '.cc-body', '.cc-cta']) {
    const el = b.querySelector(sel);
    assert.equal(opacityOf(el), 1, sel);
    assert.equal(el.style.transform, 'none', sel);
  }
});

/* ── applyMotion: word-by-word ────────────────────────────────────────── */

test('applyMotion: word-by-word wraps the headline into .cc-word spans, staggered', () => {
  const b = board(CREATIVE);
  const headline = b.querySelector('.cc-headline');

  applyMotion(b, { preset: 'word-by-word' }, 0, { durationSec: 8 });
  const words = [...headline.querySelectorAll('.cc-word')];
  assert.ok(words.length > 3, 'the headline has several words');
  assert.equal(headline.textContent.replace(/\s+/g, ' ').trim(), 'Plan the week in one sentence.');
  words.forEach((w) => assert.equal(opacityOf(w), 0));

  // A word deep inside the accent phrase stays wrapped in <em> (keeps its accent styling).
  const em = headline.querySelector('em');
  assert.ok(em.querySelector('.cc-word'), 'words inside the accent phrase are wrapped too, inside <em>');

  applyMotion(b, { preset: 'word-by-word' }, 1.5, { durationSec: 8 });
  const first = opacityOf(words[0]);
  const last = opacityOf(words[words.length - 1]);
  assert.ok(first >= last, 'an earlier word should never lag behind a later one');
  assert.equal(last, 1, 'by the end of the stagger window (1.5s) the last word should be visible');

  // Idempotent: re-applying at a different time does not re-wrap (no nested .cc-word).
  applyMotion(b, { preset: 'word-by-word' }, 0.4, { durationSec: 8 });
  assert.equal(headline.querySelectorAll('.cc-word').length, words.length);
});

/* ── applyMotion: accent-pop ──────────────────────────────────────────── */

test('applyMotion: accent-pop fades the headline then scales/brightens each accent word', () => {
  const b = board(CREATIVE);
  const headline = b.querySelector('.cc-headline');
  const em = headline.querySelector('em');

  applyMotion(b, { preset: 'accent-pop' }, 0, { durationSec: 8 });
  assert.equal(opacityOf(headline), 0);
  // Transforms don't apply to inline text: the accent's words are wrapped in inline-block spans.
  const words = em.querySelectorAll('.cc-word');
  assert.ok(words.length >= 1);

  applyMotion(b, { preset: 'accent-pop' }, 0.8, { durationSec: 8 });
  assert.equal(opacityOf(headline), 1, 'headline fade window is 0.3-0.8');
  assert.match(words[0].style.transform, /scale\(1\.18\)/);

  applyMotion(b, { preset: 'accent-pop' }, 1.0, { durationSec: 8 });
  assert.match(words[0].style.filter, /brightness\(1\.\d+\)/, 'brightness peaks mid-pop');

  applyMotion(b, { preset: 'accent-pop' }, 1.1, { durationSec: 8 });
  assert.equal(words[0].style.transform, 'none', 'settled at scale 1');
  assert.equal(words[0].style.filter, 'none', 'brightness back to 1, so the end card starts without a jump');
});

/* ── end card ──────────────────────────────────────────────────────────── */

test('applyMotion: the last 2s hold every element visible, even a cta whose own window has not started', () => {
  // A 3s creative: the end-card window is [1, 3]. The cta's own window (1.8-2.3) would not have
  // started at t=1 without the hold forcing it early.
  const b = board(CREATIVE);
  const cta = b.querySelector('.cc-cta');
  const headline = b.querySelector('.cc-headline');

  const summary = applyMotion(b, {}, 1, { durationSec: 3 });
  assert.equal(summary.inEndCard, true);
  assert.equal(opacityOf(cta), 1);
  assert.equal(opacityOf(headline), 1);
  assert.equal(headline.style.transform, 'none');
});

test('applyMotion: endCard: false does not force an early hold', () => {
  const b = board(CREATIVE);
  const cta = b.querySelector('.cc-cta');
  const summary = applyMotion(b, { endCard: false }, 1, { durationSec: 3 });
  assert.equal(summary.inEndCard, false);
  assert.equal(opacityOf(cta), 0, 'without the hold, the cta window (1.8-2.3) has not started at t=1');
});

test('applyMotion: end-card word-by-word forces every word visible regardless of its own stagger slot', () => {
  const b = board(CREATIVE);
  const headline = b.querySelector('.cc-headline');
  applyMotion(b, { preset: 'word-by-word' }, 6, { durationSec: 8 }); // inside [6,8] end-card window
  const words = [...headline.querySelectorAll('.cc-word')];
  words.forEach((w) => {
    assert.equal(opacityOf(w), 1);
    assert.equal(w.style.transform, 'none');
  });
});

/* ── applyMotion is a pure function of t: scrubbing backward is exact ───── */

test('applyMotion: re-applying an earlier t after a later one produces the exact same state as a fresh call', () => {
  const a = board(CREATIVE);
  const bb = board(CREATIVE);
  applyMotion(a, {}, 8, { durationSec: 8 }); // fast-forward to the end
  applyMotion(a, {}, 0.6, { durationSec: 8 }); // scrub back
  applyMotion(bb, {}, 0.6, { durationSec: 8 }); // fresh board, same t directly
  const headlineA = a.querySelector('.cc-headline');
  const headlineB = bb.querySelector('.cc-headline');
  assert.equal(headlineA.style.opacity, headlineB.style.opacity);
  assert.equal(headlineA.style.transform, headlineB.style.transform);
});

/* ── seekBackgroundVideo ───────────────────────────────────────────────── */

test('seekBackgroundVideo: resolves immediately when the board has no .cc-bg-video', async () => {
  const div = document.createElement('div');
  await seekBackgroundVideo(div, 2, 0); // does not hang
});

test('seekBackgroundVideo: sets currentTime to clipStartSec + tSec and resolves once "seeked" fires', async () => {
  const div = document.createElement('div');
  const video = document.createElement('video');
  video.className = 'cc-bg-video';
  div.appendChild(video);

  const done = seekBackgroundVideo(div, 2.5, 1);
  assert.equal(video.currentTime, 3.5);
  video.dispatchEvent(new window.Event('seeked'));
  await done;
});

test('normalizeMotionCreative: maps an API clip background onto the engine fields', () => {
  const c = normalizeMotionCreative({ motion: { background: { source: 'clip', clipUrl: 'https://x/c.mp4', clipStartSec: 2 } } });
  assert.equal(c.backgroundVideoUrl, 'https://x/c.mp4');
  assert.equal(c.clipStartSec, 2);
  const photo = { motion: { background: { source: 'photo' } } };
  assert.equal(normalizeMotionCreative(photo), photo);
});
