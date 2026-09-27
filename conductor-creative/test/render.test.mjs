/* render.test.mjs — resolveSequence, layoutFor, leadingFor/trackingFor,
 * enabledPlacements, tokensToCssVars: the pure parts of render.js, exercised
 * against the real placements/layouts registries. No DOM involved
 * (renderBoard, fitBoard are covered by test/mount.test.mjs's DOM shim).
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  resolveAd, resolveSequence, layoutFor, leadingFor, trackingFor,
  enabledPlacements, tokensToCssVars,
} from '../render.js';
import { placements as PLACEMENTS } from '../placements.js';
import { layouts as LAYOUTS } from '../layouts/index.js';

/* ── resolveAd ──────────────────────────────────────────────────────────── */

test('resolveAd: focalOverride wins over focal, which wins over the default', () => {
  const withDefault = resolveAd({ layout: 'bleed', headline: 'A *thing*.' }, PLACEMENTS, LAYOUTS);
  assert.equal(withDefault.focal['9x16'], '50% 50%');

  const withFocal = resolveAd(
    { layout: 'bleed', headline: 'A *thing*.', focal: { '9x16': '20% 30%' } },
    PLACEMENTS, LAYOUTS
  );
  assert.equal(withFocal.focal['9x16'], '20% 30%');

  const withOverride = resolveAd(
    { layout: 'bleed', headline: 'A *thing*.', focal: { '9x16': '20% 30%' }, focalOverride: { '9x16': '80% 10%' } },
    PLACEMENTS, LAYOUTS
  );
  assert.equal(withOverride.focal['9x16'], '80% 10%');
});

test('resolveAd: typeOverrides becomes the pinned `type`, absent means auto-fit', () => {
  const pinned = resolveAd({ layout: 'stacked', headline: 'x *y*.', typeOverrides: { '9x16': [80, 1, -2] } }, PLACEMENTS, LAYOUTS);
  assert.deepEqual(pinned.type, { '9x16': [80, 1, -2] });

  const autofit = resolveAd({ layout: 'stacked', headline: 'x *y*.' }, PLACEMENTS, LAYOUTS);
  assert.equal(autofit.type, null);
});

test('resolveAd: defaults layout to stacked and theme to dark', () => {
  const ad = resolveAd({ headline: 'x *y*.' }, PLACEMENTS, LAYOUTS);
  assert.equal(ad.layout, 'stacked');
  assert.equal(ad.theme, 'dark');
});

test('resolveAd: defaults lockup to "plain"', () => {
  const ad = resolveAd({ headline: 'x *y*.' }, PLACEMENTS, LAYOUTS);
  assert.equal(ad.lockup, 'plain');
  const chip = resolveAd({ headline: 'x *y*.', lockup: 'chip' }, PLACEMENTS, LAYOUTS);
  assert.equal(chip.lockup, 'chip');
});

test('resolveAd: layoutOverrides.band overrides the layout\'s own per-placement band default', () => {
  const stacked = resolveAd({ layout: 'stacked', headline: 'x *y*.' }, PLACEMENTS, LAYOUTS);
  assert.equal(stacked.band['9x16'], LAYOUTS.stacked.band['9x16']);

  const overridden = resolveAd(
    { layout: 'stacked', headline: 'x *y*.', layoutOverrides: { band: { '9x16': 1200 } } },
    PLACEMENTS, LAYOUTS
  );
  assert.equal(overridden.band['9x16'], 1200);
  // A placement not named in the override keeps the layout's own default.
  assert.equal(overridden.band['4x5'], LAYOUTS.stacked.band['4x5']);
});

test('resolveAd: layoutOverrides.padBottom overrides the placement\'s own safe-bottom default', () => {
  const withDefault = resolveAd({ layout: 'stacked', headline: 'x *y*.' }, PLACEMENTS, LAYOUTS);
  assert.equal(withDefault.padBottom['9x16'], PLACEMENTS['9x16'].safe.bottom);

  const overridden = resolveAd(
    { layout: 'stacked', headline: 'x *y*.', layoutOverrides: { padBottom: { '9x16': 500 } } },
    PLACEMENTS, LAYOUTS
  );
  assert.equal(overridden.padBottom['9x16'], 500);
  assert.equal(overridden.padBottom['4x5'], PLACEMENTS['4x5'].safe.bottom);
});

/* ── resolveSequence ────────────────────────────────────────────────────── */

test('resolveSequence: CTA lands on the last frame only, by default', () => {
  const raw = {
    layout: 'bleed', headline: 'A *thing*.', sequenceKind: 'story',
    sequence: [
      { headline: 'One *thing*.' },
      { headline: 'Two *things*.' },
      { headline: 'Three *things*.' },
    ],
  };
  const frames = resolveSequence(raw, PLACEMENTS, LAYOUTS);
  assert.deepEqual(frames.map((f) => f.showCta), [false, false, true]);
});

test('resolveSequence: a per-frame cta override wins over the last-frame default', () => {
  const raw = {
    layout: 'bleed', headline: 'A *thing*.', sequenceKind: 'story',
    sequence: [
      { headline: 'One *thing*.', cta: true }, // forced on, not the last frame
      { headline: 'Two *things*.', cta: false }, // forced off, the last frame
    ],
  };
  const frames = resolveSequence(raw, PLACEMENTS, LAYOUTS);
  assert.deepEqual(frames.map((f) => f.showCta), [true, false]);
});

test('resolveSequence: body is per-frame opt-in, not inherited from the creative', () => {
  const raw = {
    layout: 'bleed', headline: 'A *thing*.', body: 'Creative-level body.', sequenceKind: 'story',
    sequence: [
      { headline: 'Hook *only*.' },
      { headline: 'Detail *here*.', body: 'Frame-level body.' },
    ],
  };
  const frames = resolveSequence(raw, PLACEMENTS, LAYOUTS);
  assert.equal(frames[0].body, '');
  assert.equal(frames[1].body, 'Frame-level body.');
});

test('resolveSequence: seqIndex/seqTotal count the frame\'s position', () => {
  const raw = {
    layout: 'bleed', headline: 'A *thing*.', sequenceKind: 'carousel',
    sequence: [{ headline: 'One *a*.' }, { headline: 'Two *b*.' }, { headline: 'Three *c*.' }],
  };
  const frames = resolveSequence(raw, PLACEMENTS, LAYOUTS);
  assert.deepEqual(frames.map((f) => [f.seqIndex, f.seqTotal]), [[1, 3], [2, 3], [3, 3]]);
});

test('resolveSequence: isStory/isCarousel reflect sequenceKind', () => {
  const story = resolveSequence(
    { layout: 'bleed', sequenceKind: 'story', sequence: [{ headline: 'a *b*.' }, { headline: 'c *d*.' }] },
    PLACEMENTS, LAYOUTS
  );
  assert.equal(story[0].isStory, true);
  assert.equal(story[0].isCarousel, false);

  const carousel = resolveSequence(
    { layout: 'bleed', sequenceKind: 'carousel', sequence: [{ headline: 'a *b*.' }, { headline: 'c *d*.' }] },
    PLACEMENTS, LAYOUTS
  );
  assert.equal(carousel[0].isStory, false);
  assert.equal(carousel[0].isCarousel, true);
});

test('resolveSequence: no sequenceKind or empty sequence resolves to nothing', () => {
  assert.deepEqual(resolveSequence({ layout: 'bleed', sequence: [{ headline: 'a *b*.' }] }, PLACEMENTS, LAYOUTS), []);
  assert.deepEqual(resolveSequence({ layout: 'bleed', sequenceKind: 'story', sequence: [] }, PLACEMENTS, LAYOUTS), []);
});

/* ── layoutFor ──────────────────────────────────────────────────────────── */

test('layoutFor: stacked at 1x1 becomes bleed, via perPlacement', () => {
  const r = layoutFor({ layout: 'stacked' }, '1x1', LAYOUTS);
  assert.equal(r.kind, 'bleed');
  assert.equal(r.panel, 'bottom');
});

test('layoutFor: card at 1x1 stays card (no perPlacement override)', () => {
  const r = layoutFor({ layout: 'card' }, '1x1', LAYOUTS);
  assert.equal(r.kind, 'card');
});

test('layoutFor: bleed at 9x16 stays bleed with its default panel', () => {
  const r = layoutFor({ layout: 'bleed' }, '9x16', LAYOUTS);
  assert.equal(r.kind, 'bleed');
  assert.equal(r.panel, LAYOUTS.bleed.panel);
});

/* ── leadingFor / trackingFor: monotonic in size ────────────────────────── */

test('leadingFor: leading increases as size steps down', () => {
  assert.ok(leadingFor(120) < leadingFor(80));
  assert.ok(leadingFor(80) < leadingFor(50));
});

test('trackingFor: tracking gets tighter (more negative) as size grows', () => {
  assert.ok(trackingFor(120) < trackingFor(60));
  assert.ok(trackingFor(60) < trackingFor(30));
});

/* ── enabledPlacements ──────────────────────────────────────────────────── */

test('enabledPlacements: falls back to the registry defaults with no kit setting', () => {
  assert.deepEqual(enabledPlacements({}, undefined, PLACEMENTS).sort(), ['1x1', '4x5', '9x16']);
});

test('enabledPlacements: a kit\'s enabledPlacements replaces the registry default', () => {
  assert.deepEqual(enabledPlacements({}, ['1x1'], PLACEMENTS), ['1x1']);
});

test('enabledPlacements: a creative\'s own placements[] adds extras on top', () => {
  const result = enabledPlacements({ placements: ['story', '2x3'] }, ['1x1'], PLACEMENTS);
  assert.deepEqual(result, ['1x1', 'story', '2x3']);
});

test('enabledPlacements: extras never duplicate an already-enabled key', () => {
  const result = enabledPlacements({ placements: ['1x1', 'story'] }, ['1x1'], PLACEMENTS);
  assert.deepEqual(result, ['1x1', 'story']);
});

/* ── tokensToCssVars ────────────────────────────────────────────────────── */

test('tokensToCssVars: maps every known brand token to its CSS custom property', () => {
  const vars = tokensToCssVars({
    accent: '#111111', accent2: '#222222', darkBg: '#333333', darkInk: '#444444',
    lightBg: '#555555', lightCard: '#666666', ink: '#777777', ink2: '#888888',
  });
  assert.deepEqual(vars, {
    '--cc-accent': '#111111',
    '--cc-accent-2': '#222222',
    '--cc-d-bg': '#333333',
    '--cc-d-ink': '#444444',
    '--cc-l-bg': '#555555',
    '--cc-l-card': '#666666',
    '--cc-ink': '#777777',
    '--cc-ink-2': '#888888',
  });
});

test('tokensToCssVars: an unset token key is simply absent, no empty-string fallback', () => {
  const vars = tokensToCssVars({ accent: '#FF0000' });
  assert.deepEqual(vars, { '--cc-accent': '#FF0000' });
});

test('tokensToCssVars: no tokens at all yields an empty map (tokens.css supplies the neutral defaults)', () => {
  assert.deepEqual(tokensToCssVars(undefined), {});
  assert.deepEqual(tokensToCssVars(null), {});
});
