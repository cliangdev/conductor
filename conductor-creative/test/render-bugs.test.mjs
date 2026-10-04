/* render-bugs.test.mjs — regression tests for four rendering bugs found by end-to-end testing of the
 * content-team skill. Each one is measured in a real Chromium (what the browser laid out, not what the DOM
 * claims), except the pure helpers. Skips when no local Chromium is installed.
 *
 *   A  motion text lost the space before the accent phrase ("slightdelay") mid-animation
 *   B  a MOTION photo background tiled / showed a duplicate strip
 *   C  a board that lost its photo, logo or headline still passed every check
 *   D  stacked silently dropped the body at 4:5
 */
import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { parseHTML } from 'linkedom';
import { chromiumReady, startBrowser, PHOTOS, BRAND, LOGO } from './browser-fixture.mjs';
import { backgroundLayerRect, backgroundMotionState } from '../motion.js';
import { expectedElements } from '../assertions.js';
import { resolveAd, resolveSequence } from '../render.js';
import { placements as PLACEMENTS } from '../placements.js';
import { layouts as LAYOUTS } from '../layouts/index.js';

let fx = null;
let ready = false;
before(async () => {
  ready = await chromiumReady();
  if (ready) fx = await startBrowser();
});
after(async () => {
  if (fx) await fx.close();
});
const skip = (t) => {
  if (ready) return false;
  t.skip('no local Chromium (run: npx playwright install chromium)');
  return true;
};

const PRESETS = ['fade-up', 'word-by-word', 'accent-pop', 'none'];

function motionSpec({ creative, motion, placementKey, brand = {} }) {
  return { creative: { kind: 'MOTION', motion, ...creative }, brand, placementKey, motion, time: 0 };
}

/* ── A: spaces survive every preset at every moment ─────────────────────────────────────────────── */

/* The visual gap between two words of the headline, in px: measured with a Range over each word's text, which
 * reports where the glyphs are drawn (transforms included), so a word scaled into its neighbour's space shows
 * up as a gap that shrinks to zero or goes negative. */
async function visualGap(page, left, right) {
  return page.evaluate(([l, r]) => {
    const h = document.querySelector('.cc-headline');
    const rectOf = (word) => {
      const walker = document.createTreeWalker(h, NodeFilter.SHOW_TEXT);
      for (let n = walker.nextNode(); n; n = walker.nextNode()) {
        const i = n.textContent.indexOf(word);
        if (i >= 0) {
          const range = document.createRange();
          range.setStart(n, i);
          range.setEnd(n, i + word.length);
          return range.getBoundingClientRect();
        }
      }
      throw new Error('no text node holds "' + word + '"');
    };
    const a = rectOf(l);
    const b = rectOf(r);
    // Same line: the gap is horizontal. Wrapped onto the next line: they cannot touch.
    return b.top >= a.bottom - 2 ? Infinity : b.left - a.right;
  }, [left, right]);
}

const HEADLINES = [
  { headline: 'omw. slight *delay*.', pairs: [['omw.', 'slight'], ['slight', 'delay']] },
  { headline: 'Planning dinners by hand is *four chores*', pairs: [['is', 'four'], ['four', 'chores']] },
];

for (const preset of PRESETS) {
  test(`A: ${preset} keeps a visible space between the plain words and the accent phrase at every moment`, async (t) => {
    if (skip(t)) return;
    for (const { headline, pairs } of HEADLINES) {
      for (const placementKey of ['9x16', '1x1']) {
        const motion = { preset, durationSec: 8, background: { source: 'photo', motion: 'zoom-in' } };
        const page = await fx.open('frame.html', motionSpec({
          creative: { layout: 'stacked', headline, photoUrl: PHOTOS.landscape },
          motion, placementKey, brand: BRAND,
        }));
        try {
          for (const time of [0, 0.6, 0.85, 1.0, 1.5, 7.9]) {
            await page.evaluate((tt) => window.__seekMotion(tt), time);
            assert.equal(
              await page.evaluate(() => document.querySelector('.cc-headline').textContent),
              headline.replace(/\*/g, ''),
              `${preset} ${placementKey} t=${time}: text content lost a space`,
            );
            for (const [l, r] of pairs) {
              const gap = await visualGap(page, l, r);
              assert.ok(gap >= 3, `${preset} ${placementKey} t=${time}: "${l}" and "${r}" are ${gap}px apart (words run together)`);
            }
          }
        } finally {
          await page.close();
        }
      }
    }
  });
}

test('A: the headline text is identical at every moment for every preset (no DOM shim, no layout)', async () => {
  const { window } = parseHTML('<!doctype html><html><body></body></html>');
  globalThis.document = window.document;
  const { applyMotion } = await import('../motion.js');
  const { renderBoard } = await import('../render.js');
  for (const preset of PRESETS) {
    const ad = resolveAd({ layout: 'bleed', headline: 'omw. slight *delay*.' }, PLACEMENTS, LAYOUTS);
    const board = renderBoard(ad, '9x16', PLACEMENTS, LAYOUTS, {});
    for (const time of [0, 0.6, 1, 4, 7.9]) {
      applyMotion(board, { preset }, time, { durationSec: 8 });
      assert.equal(board.querySelector('.cc-headline').textContent, 'omw. slight delay.', `${preset} t=${time}`);
    }
  }
});

test('A: an italic accent after a space gets an italic correction; one glued to punctuation does not', () => {
  const { window } = parseHTML('<!doctype html><html><body></body></html>');
  globalThis.document = window.document;
  return import('../render.js').then(({ renderBoard }) => {
    const em = (headline) => {
      const ad = resolveAd({ layout: 'bleed', headline }, PLACEMENTS, LAYOUTS);
      return renderBoard(ad, '1x1', PLACEMENTS, LAYOUTS, {}).querySelector('.cc-headline em');
    };
    const mid = em('omw. slight *delay* today');
    assert.ok(mid.classList.contains('cc-accent-after-space'));
    assert.ok(mid.classList.contains('cc-accent-before-space'));
    const glued = em('*Delay*, again');
    assert.ok(!glued.classList.contains('cc-accent-after-space'), 'no correction at the start of the headline');
    assert.ok(!glued.classList.contains('cc-accent-before-space'), 'no correction against glued punctuation');
  });
});

test('A: the gap before an italic accent is at least as wide as a plain word space (still and motion)', async (t) => {
  if (skip(t)) return;
  for (const kind of ['STILL', 'MOTION']) {
    const motion = { preset: 'accent-pop', durationSec: 8, background: { source: 'photo', motion: 'zoom-in' } };
    const spec = kind === 'MOTION'
      ? motionSpec({ creative: { layout: 'bleed', headline: 'omw. slight *delay*.', photoUrl: PHOTOS.landscape }, motion, placementKey: '1x1', brand: BRAND })
      : { creative: { kind: 'STILL', layout: 'bleed', headline: 'omw. slight *delay*.', photoUrl: PHOTOS.landscape }, brand: BRAND, placementKey: '1x1' };
    const page = await fx.open('frame.html', spec);
    try {
      if (kind === 'MOTION') await page.evaluate(() => window.__seekMotion(7.9));
      const plain = await visualGap(page, 'omw.', 'slight');
      const accent = await visualGap(page, 'slight', 'delay');
      if (plain !== Infinity && accent !== Infinity) {
        assert.ok(accent >= plain, `${kind}: accent gap ${accent}px is narrower than a word space ${plain}px`);
      }
    } finally {
      await page.close();
    }
  }
});

/* ── B: the photo layer always covers its frame, never repeats ──────────────────────────────────── */

const BG_MOTIONS = ['zoom-in', 'zoom-out', 'pan-left', 'pan-right', 'none'];
const EPS = 0.51; // sub-pixel rounding in the browser's own layout

test('B: backgroundLayerRect covers the box at every moment, for every motion', () => {
  for (const kind of BG_MOTIONS) {
    for (let time = 0; time <= 8; time += 0.25) {
      const state = backgroundMotionState({ background: { motion: kind } }, time, 8);
      const r = backgroundLayerRect(1080, 1920, state);
      const tag = `${kind} t=${time} ${JSON.stringify(state)}`;
      assert.ok(r.left <= 1e-9 && r.top <= 1e-9, `${tag}: layer starts inside the box (${r.left}, ${r.top})`);
      assert.ok(r.right >= 1080 - 1e-9 && r.bottom >= 1920 - 1e-9, `${tag}: layer ends inside the box (${r.right}, ${r.bottom})`);
    }
  }
});

function covers(inner, outer) {
  return inner.left <= outer.left + EPS && inner.top <= outer.top + EPS
    && inner.right >= outer.right - EPS && inner.bottom >= outer.bottom - EPS;
}

test('B: a bleed photo layer covers the board with no repeat, for every aspect, photo shape, motion and moment', async (t) => {
  if (skip(t)) return;
  for (const placementKey of ['9x16', '4x5', '1x1', 'story']) {
    for (const [shape, photoUrl] of Object.entries(PHOTOS)) {
      const motion = { preset: 'none', durationSec: 8, background: { source: 'photo', motion: 'zoom-in' } };
      const page = await fx.open('frame.html', motionSpec({
        creative: { layout: 'bleed', headline: 'Photo *cover*', photoUrl, focal: { [placementKey]: '30% 70%' } },
        motion, placementKey,
      }));
      try {
        const style = await page.evaluate(() => {
          const cs = getComputedStyle(document.querySelector('.cc-bg-photo'));
          return { repeat: cs.backgroundRepeat, size: cs.backgroundSize, position: cs.backgroundPosition };
        });
        assert.equal(style.repeat, 'no-repeat', `${placementKey} ${shape}`);
        assert.equal(style.size, 'cover', `${placementKey} ${shape}`);
        assert.equal(style.position, '30% 70%', `${placementKey} ${shape}: the focal point is the layer's position`);
        for (const bgMotion of BG_MOTIONS) {
          await page.evaluate((m) => { window.__bgMotion = m; }, bgMotion);
          for (const time of [0, 0.6, 2, 4, 6, 7.9]) {
            const rects = await page.evaluate(async ([m, tt, d]) => {
              const board = document.querySelector('.cc-board');
              const { applyMotion } = await import('/motion.js');
              applyMotion(board, { preset: 'none', durationSec: d, background: { source: 'photo', motion: m } }, tt, { durationSec: d });
              const r = (n) => { const b = n.getBoundingClientRect(); return { left: b.left, top: b.top, right: b.right, bottom: b.bottom }; };
              return { layer: r(document.querySelector('.cc-bg-photo')), board: r(board) };
            }, [bgMotion, time, 8]);
            assert.ok(covers(rects.layer, rects.board),
              `${placementKey} ${shape} ${bgMotion} t=${time}: photo layer ${JSON.stringify(rects.layer)} does not cover ${JSON.stringify(rects.board)}`);
          }
        }
      } finally {
        await page.close();
      }
    }
  }
});

test('B: a band, card or split photo (an <img>) covers its frame at every moment', async (t) => {
  if (skip(t)) return;
  for (const [layout, placementKey, frameSel] of [
    ['stacked', '9x16', '.cc-board__band'],
    ['stacked', '4x5', '.cc-board__band'],
    ['card', '4x5', '.cc-board__card'],
    ['split', '9x16', '.cc-board__band'],
  ]) {
    for (const [shape, photoUrl] of Object.entries(PHOTOS)) {
      const motion = { preset: 'none', durationSec: 8, background: { source: 'photo', motion: 'zoom-in' } };
      const page = await fx.open('frame.html', motionSpec({
        creative: { layout, theme: layout === 'card' ? 'light' : 'dark', headline: 'Photo *cover*', photoUrl },
        motion, placementKey,
      }));
      try {
        assert.equal(await page.evaluate((s) => getComputedStyle(document.querySelector(s + ' img')).objectFit, frameSel), 'cover');
        for (const bgMotion of BG_MOTIONS) {
          for (const time of [0, 3, 7.9]) {
            const rects = await page.evaluate(async ([m, tt, d, sel]) => {
              const board = document.querySelector('.cc-board');
              const { applyMotion } = await import('/motion.js');
              applyMotion(board, { preset: 'none', durationSec: d, background: { source: 'photo', motion: m } }, tt, { durationSec: d });
              const r = (n) => { const b = n.getBoundingClientRect(); return { left: b.left, top: b.top, right: b.right, bottom: b.bottom }; };
              return { layer: r(document.querySelector(sel + ' img')), frame: r(document.querySelector(sel)) };
            }, [bgMotion, time, 8, frameSel]);
            assert.ok(covers(rects.layer, rects.frame), `${layout} ${placementKey} ${shape} ${bgMotion} t=${time}`);
          }
        }
      } finally {
        await page.close();
      }
    }
  }
});

/* ── C: a board that lost a required element fails its checks ───────────────────────────────────── */

/* Loads a plain STILL frame, lets `mutate` damage the board the way a broken render would, and runs the
 * real runAssertions with what the spec requires. */
async function checksAfter(creative, brand, mutate, placementKey = '9x16') {
  const page = await fx.open('frame.html', { creative: { layout: 'bleed', headline: 'Ready *now*', photoUrl: PHOTOS.landscape, ...creative }, brand, placementKey });
  try {
    return await page.evaluate(async ([fn, key]) => {
      const board = document.querySelector('.cc-board');
      // eslint-disable-next-line no-new-func
      new Function('board', fn)(board);
      const { runAssertions, expectedElements: expected } = await import('/assertions.js');
      const { resolveAd } = await import('/render.js');
      const { placements } = await import('/placements.js');
      const { layouts } = await import('/layouts/index.js');
      const spec = window.__RENDER_SPEC__;
      const ad = resolveAd(spec.creative, placements, layouts);
      const out = await runAssertions(board, placements[key], spec.brand, { expect: expected(ad, spec.brand) });
      return out.checks;
    }, [mutate, placementKey]);
  } finally {
    await page.close();
  }
}

const errorRules = (checks) => checks.filter((c) => c.severity === 'error').map((c) => c.rule);

test('C: an intact frame passes (no missing* check)', async (t) => {
  if (skip(t)) return;
  assert.deepEqual(errorRules(await checksAfter({}, BRAND, '')), []);
});

test('C: a bleed frame whose photo layer is gone fails missingPhoto', async (t) => {
  if (skip(t)) return;
  const checks = await checksAfter({}, BRAND, "board.querySelector('.cc-bg-photo').remove()");
  assert.ok(errorRules(checks).includes('missingPhoto'), JSON.stringify(checks));
});

test('C: a photo layer that is collapsed, hidden or has no image fails missingPhoto', async (t) => {
  if (skip(t)) return;
  for (const mutate of [
    "board.querySelector('.cc-bg-photo').style.display = 'none'",
    "board.querySelector('.cc-bg-photo').style.visibility = 'hidden'",
    "const l = board.querySelector('.cc-bg-photo'); l.style.inset = 'auto'; l.style.width = '0px'; l.style.height = '0px'",
    "board.querySelector('.cc-bg-photo').style.backgroundImage = 'none'",
  ]) {
    assert.ok(errorRules(await checksAfter({}, BRAND, mutate)).includes('missingPhoto'), mutate);
  }
});

test('C: a band frame whose photo <img> is gone, or never loaded, fails missingPhoto', async (t) => {
  if (skip(t)) return;
  const creative = { layout: 'stacked' };
  assert.ok(errorRules(await checksAfter(creative, BRAND, "board.querySelector('.cc-board__band img').remove()")).includes('missingPhoto'));
  assert.ok(errorRules(await checksAfter(creative, BRAND, "board.querySelector('.cc-board__band img').src = 'data:image/png;base64,AAAA'")).includes('missingPhoto'));
});

test('C: a frame with a logo kit but no lockup fails missingLockup; a kit without logos does not', async (t) => {
  if (skip(t)) return;
  const checks = await checksAfter({}, BRAND, "board.querySelector('.cc-lockup').remove()");
  assert.ok(errorRules(checks).includes('missingLockup'), JSON.stringify(checks));
  // The invisible spacer a logo-less kit renders is not a lockup, and a logo-less kit does not require one.
  assert.deepEqual(errorRules(await checksAfter({}, {}, '')), []);
  assert.ok(errorRules(await checksAfter({}, BRAND, "board.querySelector('.cc-lockup').classList.add('cc-lockup--spacer')")).includes('missingLockup'));
});

test('C: a frame with no headline text fails missingHeadline', async (t) => {
  if (skip(t)) return;
  assert.ok(errorRules(await checksAfter({}, BRAND, "board.querySelector('.cc-headline').textContent = ''")).includes('missingHeadline'));
  assert.ok(errorRules(await checksAfter({}, BRAND, "board.querySelector('.cc-headline').remove()")).includes('missingHeadline'));
});

test('C: a spec without a photo URL does not require a photo layer', async (t) => {
  if (skip(t)) return;
  assert.deepEqual(errorRules(await checksAfter({ photoUrl: null }, BRAND, '')), []);
});

test('C: the same checks run on the MOTION end-card frame', async (t) => {
  if (skip(t)) return;
  const motion = { preset: 'word-by-word', durationSec: 8, background: { source: 'photo', motion: 'zoom-in' } };
  const page = await fx.open('frame.html', motionSpec({
    creative: { layout: 'bleed', headline: 'Ready *now*', photoUrl: PHOTOS.landscape }, motion, placementKey: '9x16', brand: BRAND,
  }));
  try {
    await page.evaluate(() => window.__seekMotion(7.9));
    assert.deepEqual(errorRules((await page.evaluate(() => window.__assertBoard())).checks), []);
    await page.evaluate(() => { document.querySelector('.cc-bg-photo').remove(); document.querySelector('.cc-lockup').remove(); });
    const rules = errorRules((await page.evaluate(() => window.__assertBoard())).checks);
    assert.ok(rules.includes('missingPhoto') && rules.includes('missingLockup'), rules.join(','));
  } finally {
    await page.close();
  }
});

test('C: expectedElements derives what a spec requires', () => {
  assert.deepEqual(expectedElements({ photoUrl: 'x', headline: 'h' }, BRAND), { photo: true, lockup: true, headline: true });
  assert.deepEqual(expectedElements({ photoUrl: null, headline: '  ' }, {}), { photo: false, lockup: false, headline: false });
  assert.equal(expectedElements({}, { logos: { wordmarkDark: 'w' } }).lockup, true);
  assert.equal(expectedElements({}, { logos: { badge: 'b' } }).lockup, false, 'a badge alone is the CTA, not a lockup');
});

test('C: a sequence beat with null fields inherits the creative (a null photoUrl no longer drops the photo)', () => {
  const frames = resolveSequence({
    layout: 'bleed', headline: 'Main', photoUrl: 'https://cdn.example/p.jpg', sequenceKind: 'story',
    sequence: [{ headline: 'One', photoUrl: null, focal: null }, { headline: null, photoUrl: null }],
  }, PLACEMENTS, LAYOUTS);
  assert.equal(frames[0].photoUrl, 'https://cdn.example/p.jpg');
  assert.equal(frames[1].photoUrl, 'https://cdn.example/p.jpg');
  assert.equal(frames[1].headline, 'Main');
});

test('C: the contact sheet waits for every photo and logo before it reports ready', async (t) => {
  if (skip(t)) return;
  // A server that is slow to hand over the picture: a sheet that does not wait is "ready" with none of it.
  const finished = [];
  const svg = Buffer.from(decodeURIComponent(LOGO.split(',')[1]));
  const slow = createServer((req, res) => {
    setTimeout(() => {
      res.writeHead(200, { 'content-type': 'image/svg+xml', 'cache-control': 'no-store', 'access-control-allow-origin': '*' });
      res.end(svg);
      finished.push(req.url);
    }, 400);
  });
  await new Promise((resolve) => slow.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${slow.address().port}`;
  try {
    const page = await fx.open('sheet.html', {
      creative: { layout: 'bleed', headline: 'Ready *now*', photoUrl: `${base}/photo.svg` },
      brand: { logos: { mark: `${base}/mark.svg`, wordmarkLight: `${base}/word.svg` } },
      placementKey: 'sheet', placementKeys: ['9x16'],
    });
    try {
      const state = await page.evaluate(() => ({
        imgsDone: [...document.images].every((i) => i.complete && i.naturalWidth > 0),
        photo: getComputedStyle(document.querySelector('.cc-bg-photo')).backgroundImage,
      }));
      assert.equal(state.imgsDone, true, 'logos had not loaded when the sheet reported ready');
      assert.ok(finished.some((u) => u.includes('photo.svg')), `the photo had not finished loading when the sheet reported ready (${finished})`);
    } finally {
      await page.close();
    }
  } finally {
    slow.close();
  }
});

/* ── D: a dropped body is a visible warning ─────────────────────────────────────────────────────── */

async function frameChecks(creative, placementKey, sequenceIndex) {
  const page = await fx.open('frame.html', { creative, brand: BRAND, placementKey, sequenceIndex });
  try {
    return await page.evaluate(() => window.__RENDER_RESULT);
  } finally {
    await page.close();
  }
}

test('D: stacked at 4x5 with a body warns bodyDropped (a warning, not an error)', async (t) => {
  if (skip(t)) return;
  const result = await frameChecks({ layout: 'stacked', headline: 'Hello *there*', body: 'Words that will not show.', photoUrl: PHOTOS.landscape }, '4x5');
  assert.equal(result.ok, true, 'a dropped body must not block the render');
  assert.deepEqual(result.errors, []);
  const warning = result.checks.find((c) => c.rule === 'bodyDropped');
  assert.ok(warning, JSON.stringify(result.checks));
  assert.equal(warning.severity, 'warning');
  assert.equal(warning.placementKey, '4x5');
  assert.match(warning.message, /stacked/);
  assert.match(warning.message, /4x5/);
  assert.ok(result.warnings.some((m) => /stacked/.test(m) && /4x5/.test(m)), 'also in the plain warnings list');
});

test('D: no warning when the body shows, or when there was no body to drop', async (t) => {
  if (skip(t)) return;
  const shown = await frameChecks({ layout: 'stacked', headline: 'Hello *there*', body: 'Visible.', photoUrl: PHOTOS.landscape }, '9x16');
  assert.equal(shown.checks.some((c) => c.rule === 'bodyDropped'), false);
  const none = await frameChecks({ layout: 'stacked', headline: 'Hello *there*', photoUrl: PHOTOS.landscape }, '4x5');
  assert.equal(none.checks.some((c) => c.rule === 'bodyDropped'), false);
});

test('D: every 4:5 carousel card whose body is dropped warns, with its index, through the preview checks', async (t) => {
  if (skip(t)) return;
  const { run } = await import('../job/render.mjs');
  const calls = {};
  const transport = {
    async getSpec() {
      return {
        renderId: 'd1', previewOnly: true,
        creative: {
          layout: 'stacked', headline: 'Swipe *through*', photoUrl: PHOTOS.landscape, sequenceKind: 'carousel',
          sequence: [{ headline: 'One', body: 'First body' }, { headline: 'Two' }, { headline: 'Three', body: 'Third body' }],
        },
        brand: BRAND, placements: ['4x5'],
      };
    },
    async putFrame() {},
    async complete(warnings, checks) { calls.checks = checks; },
    async fail(message) { calls.fail = message; },
  };
  assert.equal(await run({ transport, checkPlacements: true, log: () => {} }), true, calls.fail);
  const dropped = calls.checks.filter((c) => c.rule === 'bodyDropped');
  assert.deepEqual(dropped.map((c) => c.index), [0, 2]);
  for (const c of dropped) {
    assert.equal(c.severity, 'warning');
    assert.equal(c.placementKey, '4x5');
    assert.match(c.message, /stacked.*4x5/);
  }
  assert.deepEqual(calls.checks.filter((c) => c.severity === 'error'), []);
});
