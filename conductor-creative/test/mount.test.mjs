/* mount.test.mjs — mountBoard's lifecycle (mount, update, destroy) and
 * attachFocalDrag's reporting, against linkedom. No layout engine is present,
 * so the display-scale math is not asserted here beyond "it does not throw
 * and produces a number"; the structural DOM changes update()/destroy() make
 * are what this file checks.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseHTML } from 'linkedom';
import { mountBoard, attachFocalDrag, enabledPlacements } from '../mount.js';

const { window } = parseHTML('<!doctype html><html><head></head><body></body></html>');
globalThis.document = window.document;

function makeContainer() {
  const el = document.createElement('div');
  // linkedom has no layout engine, so give the container deterministic
  // measurements mountBoard can divide by.
  el.getBoundingClientRect = () => ({ left: 0, top: 0, width: 216, height: 384 });
  document.body.appendChild(el);
  return el;
}

test('mountBoard: renders a board into the container and resolves `ready`', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'Plan the week in *one sentence*.' },
    brand: {},
    placementKey: '9x16',
  });
  await handle.ready;
  assert.ok(container.querySelector('.cc-board'));
  assert.equal(handle.board.className.includes('cc-r-9x16'), true);
});

test('mountBoard: update() re-renders with the patched creative, no new container needed', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'First *headline*.' },
    brand: {},
    placementKey: '4x5',
  });
  await handle.ready;
  assert.equal(handle.board.querySelector('.cc-headline').textContent, 'First headline.');

  await handle.update({ creative: { layout: 'bleed', headline: 'Second *headline*.' } });
  assert.equal(handle.board.querySelector('.cc-headline').textContent, 'Second headline.');
  // Still exactly one board mounted, not stacked on top of the old one.
  assert.equal(container.querySelectorAll('.cc-board').length, 1);
});

test('mountBoard: update() can switch placement or brand', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'x *y*.' },
    brand: { tokens: { accent: '#111111' } },
    placementKey: '1x1',
  });
  await handle.ready;
  assert.equal(handle.board.style.getPropertyValue('--cc-accent'), '#111111');

  await handle.update({ placementKey: '9x16', brand: { tokens: { accent: '#222222' } } });
  assert.ok(handle.board.className.includes('cc-r-9x16'));
  assert.equal(handle.board.style.getPropertyValue('--cc-accent'), '#222222');
});

test('mountBoard: destroy() removes the mounted board from the container', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'x *y*.' },
    brand: {},
    placementKey: '9x16',
  });
  await handle.ready;
  assert.ok(container.querySelector('.cc-board'));
  handle.destroy();
  assert.equal(container.querySelector('.cc-board'), null);
  assert.equal(handle.board, null);
});

test('mountBoard: a sequence creative renders the requested sequenceIndex frame', async () => {
  const container = makeContainer();
  const creative = {
    layout: 'bleed',
    headline: 'Concept *headline*.',
    sequenceKind: 'story',
    sequence: [{ headline: 'Beat one *here*.' }, { headline: 'Beat two *here*.' }],
  };
  const handle = mountBoard(container, { creative, brand: {}, placementKey: 'story', sequenceIndex: 1 });
  await handle.ready;
  assert.equal(handle.board.querySelector('.cc-headline').textContent, 'Beat two here.');
});

test('enabledPlacements is re-exported from mount.js', () => {
  assert.deepEqual(
    enabledPlacements({}, ['1x1'], { '1x1': { default: true }, '9x16': { default: true } }),
    ['1x1']
  );
});

/* ── attachFocalDrag ───────────────────────────────────────────────────── */

test('attachFocalDrag: reports an "x% y%" string as the pointer moves within the shell', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'x *y*.' },
    brand: {},
    placementKey: '1x1',
  });
  await handle.ready;
  // Deterministic geometry for the drag math, since linkedom does no layout.
  handle.shell.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 200 });

  const reported = [];
  const drag = attachFocalDrag(handle, (value) => reported.push(value));

  const down = new window.Event('pointerdown');
  down.pointerId = 1; down.clientX = 50; down.clientY = 100;
  handle.shell.dispatchEvent(down);
  // Pressing alone is not a drag: the focal point must not jump to the press.
  assert.deepEqual(reported, []);

  const move = new window.Event('pointermove');
  move.clientX = 150; move.clientY = 20;
  handle.shell.dispatchEvent(move);
  assert.deepEqual(reported, ['75% 10%']);
  assert.equal(handle.board.style.getPropertyValue('--cc-focal'), '75% 10%');

  const up = new window.Event('pointerup');
  handle.shell.dispatchEvent(up);
  const moveAfterUp = new window.Event('pointermove');
  moveAfterUp.clientX = 0; moveAfterUp.clientY = 0;
  handle.shell.dispatchEvent(moveAfterUp);
  assert.equal(reported.length, 1, 'no report after pointerup');

  drag.detach();
});

test('attachFocalDrag: a click (no movement past the threshold) calls onClick and leaves the focal point', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, { creative: { layout: 'bleed', headline: 'x *y*.' }, brand: {}, placementKey: '4x5' });
  await handle.ready;
  handle.shell.getBoundingClientRect = () => ({ left: 0, top: 0, width: 200, height: 250 });
  const reported = [];
  let clicks = 0;
  const drag = attachFocalDrag(handle, (v) => reported.push(v), { onClick: () => { clicks += 1; } });

  const down = new window.Event('pointerdown'); down.pointerId = 1; down.clientX = 40; down.clientY = 40;
  handle.shell.dispatchEvent(down);
  const jitter = new window.Event('pointermove'); jitter.clientX = 42; jitter.clientY = 41;
  handle.shell.dispatchEvent(jitter);
  handle.shell.dispatchEvent(new window.Event('pointerup'));

  assert.equal(clicks, 1);
  assert.deepEqual(reported, []);
  drag.detach();
});

// A draw awaits document.fonts.ready; the editor can destroy or update the board while it waits (switching
// kits remounts every placement). The superseded draw must not fit a board that is gone or replaced.
function withSlowFonts() {
  let release;
  const ready = new Promise((r) => { release = r; });
  const previous = document.fonts;
  document.fonts = { ready };
  return { release, restore: () => { document.fonts = previous; } };
}

test('mountBoard: destroy() while fonts are still loading does not throw', async () => {
  const fonts = withSlowFonts();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'Gone *soon*.' },
      brand: {},
      placementKey: '1x1',
    });
    await Promise.resolve();
    handle.destroy();
    fonts.release();
    await handle.ready;
    assert.equal(container.querySelectorAll('.cc-board').length, 0);
  } finally {
    fonts.restore();
  }
});

/* ── MOTION: seek/play/pause/onTime ───────────────────────────────────────
 * linkedom has no real `requestAnimationFrame`; mount.js falls back to a
 * setTimeout-based shim when the global is absent, but these tests want
 * deterministic, hand-driven frames, so they install a fake rAF that just
 * records the callback and lets the test invoke it with a chosen timestamp. */

function withFakeRaf() {
  const previousRaf = globalThis.requestAnimationFrame;
  const previousCaf = globalThis.cancelAnimationFrame;
  let nextId = 1;
  const pending = new Map();
  globalThis.requestAnimationFrame = (cb) => {
    const id = nextId++;
    pending.set(id, cb);
    return id;
  };
  globalThis.cancelAnimationFrame = (id) => {
    pending.delete(id);
  };
  return {
    // Runs the single most-recently-scheduled frame callback at timestamp `ts`, then waits a tick so
    // any awaited work inside it (seekBackgroundVideo's Promise, in particular) settles.
    async tick(ts) {
      const ids = [...pending.keys()];
      const id = ids[ids.length - 1];
      const cb = pending.get(id);
      pending.delete(id);
      if (cb) await cb(ts);
      await Promise.resolve();
    },
    pendingCount() {
      return pending.size;
    },
    restore() {
      globalThis.requestAnimationFrame = previousRaf;
      globalThis.cancelAnimationFrame = previousCaf;
    },
  };
}

test('mountBoard: seek() applies motion to the current board without rebuilding it', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'Plan the week in *one sentence*.', motion: { durationSec: 8 } },
    brand: {},
    placementKey: '9x16',
  });
  await handle.ready;
  const headline = handle.board.querySelector('.cc-headline');
  // draw() already applied motion at t=0.
  assert.equal(headline.style.opacity, '0');

  await handle.seek(0.9);
  assert.equal(headline.style.opacity, '1');
  assert.equal(handle.board.querySelector('.cc-headline'), headline, 'same board, not rebuilt');
});

test('mountBoard: seek() is a no-op for a STILL creative (no `motion`)', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'x *y*.' },
    brand: {},
    placementKey: '9x16',
  });
  await handle.ready;
  const headline = handle.board.querySelector('.cc-headline');
  assert.equal(headline.style.opacity, '', 'STILL never gets an opacity style at all');
  await handle.seek(2);
  assert.equal(headline.style.opacity, '', 'seek() on a STILL creative changes nothing');
});

test('mountBoard: play() advances currentT via requestAnimationFrame and calls onTime listeners', async () => {
  const raf = withFakeRaf();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'x *y*.', motion: { durationSec: 8 } },
      brand: {},
      placementKey: '9x16',
    });
    await handle.ready;

    const times = [];
    const unsubscribe = handle.onTime((t) => times.push(t));

    handle.play();
    await raf.tick(0); // first frame establishes the baseline timestamp, no delta yet
    assert.deepEqual(times, [0]);

    await raf.tick(500); // +0.5s
    assert.equal(times.length, 2);
    assert.ok(Math.abs(times[1] - 0.5) < 1e-6, `expected ~0.5, got ${times[1]}`);

    handle.pause();
    unsubscribe();
  } finally {
    raf.restore();
  }
});

test('mountBoard: play() loops back to 0 at durationSec', async () => {
  const raf = withFakeRaf();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'x *y*.', motion: { durationSec: 1 } },
      brand: {},
      placementKey: '9x16',
    });
    await handle.ready;
    const times = [];
    handle.onTime((t) => times.push(t));

    handle.play();
    await raf.tick(0);
    await raf.tick(1500); // +1.5s, past the 1s duration -> should wrap
    assert.ok(times[times.length - 1] < 1, `expected a wrapped time < 1, got ${times[times.length - 1]}`);
    handle.pause();
  } finally {
    raf.restore();
  }
});

test('mountBoard: pause() stops scheduling further frames', async () => {
  const raf = withFakeRaf();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'x *y*.', motion: { durationSec: 8 } },
      brand: {},
      placementKey: '9x16',
    });
    await handle.ready;
    handle.play();
    assert.equal(raf.pendingCount(), 1);
    handle.pause();
    assert.equal(raf.pendingCount(), 0, 'pause() cancels the outstanding frame');
    await raf.tick(0); // nothing left to run; should not throw
  } finally {
    raf.restore();
  }
});

test('mountBoard: play() is a no-op for a STILL creative', async () => {
  const raf = withFakeRaf();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'x *y*.' },
      brand: {},
      placementKey: '9x16',
    });
    await handle.ready;
    handle.play();
    assert.equal(raf.pendingCount(), 0, 'no motion, no playback loop scheduled');
  } finally {
    raf.restore();
  }
});

test('mountBoard: an edit (update) keeps the scrub position instead of jumping back to 0', async () => {
  const container = makeContainer();
  const handle = mountBoard(container, {
    creative: { layout: 'bleed', headline: 'First *one*.', motion: { durationSec: 8 } },
    brand: {},
    placementKey: '9x16',
  });
  await handle.ready;
  await handle.seek(5);
  assert.equal(handle.board.querySelector('.cc-headline').style.opacity, '1');

  await handle.update({ creative: { layout: 'bleed', headline: 'Second *one*.', motion: { durationSec: 8 } } });
  assert.equal(handle.board.querySelector('.cc-headline').style.opacity, '1', 'still at t=5 after the rebuild');
});

// The editor calls play() straight after mounting, before the first board has drawn; that call used to
// be dropped, leaving a "playing" preview frozen at 0:00.
test('mountBoard: play() before the first draw starts playback once the board exists', async () => {
  const raf = withFakeRaf();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'x *y*.', motion: { durationSec: 8 } },
      brand: {},
      placementKey: '9x16',
    });
    handle.play();
    await handle.ready;
    assert.equal(raf.pendingCount(), 1, 'the loop is scheduled after the draw');
  } finally {
    raf.restore();
  }
});

test('mountBoard: playback survives an update() (every keystroke rebuilds the board)', async () => {
  const raf = withFakeRaf();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'x *y*.', motion: { durationSec: 8 } },
      brand: {},
      placementKey: '9x16',
    });
    await handle.ready;
    handle.play();
    await handle.update({ creative: { layout: 'bleed', headline: 'x *yz*.', motion: { durationSec: 8 } } });
    assert.equal(raf.pendingCount(), 1, 'still playing after the rebuild');
    handle.pause();
    await handle.update({ creative: { layout: 'bleed', headline: 'x *yzz*.', motion: { durationSec: 8 } } });
    assert.equal(raf.pendingCount(), 0, 'a paused preview stays paused after a rebuild');
  } finally {
    raf.restore();
  }
});

test('mountBoard: destroy() while playing stops the loop', async () => {
  const raf = withFakeRaf();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'x *y*.', motion: { durationSec: 8 } },
      brand: {},
      placementKey: '9x16',
    });
    await handle.ready;
    handle.play();
    handle.destroy();
    assert.equal(raf.pendingCount(), 0);
  } finally {
    raf.restore();
  }
});

test('mountBoard: an update() during a pending draw leaves one board showing the latest input', async () => {
  const fonts = withSlowFonts();
  try {
    const container = makeContainer();
    const handle = mountBoard(container, {
      creative: { layout: 'bleed', headline: 'First *draft*.' },
      brand: {},
      placementKey: '4x5',
    });
    const second = handle.update({ creative: { layout: 'bleed', headline: 'Second *draft*.' } });
    fonts.release();
    await Promise.all([handle.ready, second]);
    assert.equal(container.querySelectorAll('.cc-board').length, 1);
    assert.equal(handle.board.querySelector('.cc-headline').textContent, 'Second draft.');
  } finally {
    fonts.restore();
  }
});
