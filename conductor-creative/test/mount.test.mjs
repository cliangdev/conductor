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

  assert.deepEqual(reported, ['25% 50%']);
  assert.equal(handle.board.style.getPropertyValue('--cc-focal'), '25% 50%');

  const move = new window.Event('pointermove');
  move.clientX = 150; move.clientY = 20;
  handle.shell.dispatchEvent(move);
  assert.deepEqual(reported, ['25% 50%', '75% 10%']);

  const up = new window.Event('pointerup');
  handle.shell.dispatchEvent(up);
  const moveAfterUp = new window.Event('pointermove');
  moveAfterUp.clientX = 0; moveAfterUp.clientY = 0;
  handle.shell.dispatchEvent(moveAfterUp);
  assert.equal(reported.length, 2, 'no report after pointerup');

  drag.detach();
});
