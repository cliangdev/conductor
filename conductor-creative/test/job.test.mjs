/* job.test.mjs — job/render.mjs's rendering core (`run()`), end to end
 * against a real Playwright Chromium and this package's own frame.html /
 * sheet.html, but with an in-memory FAKE TRANSPORT instead of HTTP — no
 * network, no fake backend server, and no coupling to today's external v2
 * `/marketing/creatives/{creativeId}/renders` shape (that shape is
 * transport.mjs's own concern, covered by test/transport.test.mjs). This is
 * deliberately how the render core is expected to keep working if the
 * delivery mechanism changes again: `run()` only ever calls the four methods
 * on `transport`.
 *
 * Needs a real Chromium (`npx playwright install chromium`); every test
 * skips itself when one is not available, rather than failing CI on a
 * machine that never ran that install step.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run, framesFor, isChromiumAvailable } from '../job/render.mjs';

let chromiumAvailable = null;
async function browserAvailable() {
  if (chromiumAvailable === null) chromiumAvailable = await isChromiumAvailable();
  return chromiumAvailable;
}

/* IHDR width/height live at fixed byte offsets in every PNG, per the spec:
 * signature (8) + length (4) + "IHDR" (4) + width (4, BE) + height (4, BE). */
function jpegDimensions(buf) {
  let offset = 2; // past the 0xFFD8 SOI marker
  while (offset + 4 <= buf.length) {
    if (buf[offset] !== 0xff) {
      offset += 1;
      continue;
    }
    const marker = buf[offset + 1];
    const isSof = marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc;
    if (isSof) {
      return { height: buf.readUInt16BE(offset + 5), width: buf.readUInt16BE(offset + 7) };
    }
    const segmentLength = buf.readUInt16BE(offset + 2);
    offset += 2 + segmentLength;
  }
  throw new Error('no SOF marker found in JPEG');
}

function fakeTransport() {
  const calls = { putFrame: [], complete: null, fail: null };
  return {
    calls,
    spec: null,
    async getSpec() {
      return this.spec;
    },
    async putFrame(placementKey, { index, width, height, bytes, contentType }) {
      calls.putFrame.push({ placementKey, index, width, height, bytes, contentType });
    },
    async complete(warnings) {
      calls.complete = warnings;
    },
    async fail(message, log) {
      calls.fail = { message, log };
    },
  };
}

const BRAND = {
  tokens: { accent: '#FF5A5F', darkBg: '#1C1C1E', darkInk: '#FFFFFF' },
  fontFamily: undefined, // no fontUrl to load in this test env; the font check no-ops when unset
};

test('run(): a valid single-placement creative renders, PUTs one frame, then completes', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const transport = fakeTransport();
  transport.spec = {
    renderId: 'r1',
    previewOnly: false,
    creative: { layout: 'stacked', theme: 'dark', headline: 'Plan the week in *one sentence*.', body: 'Body copy.' },
    brand: BRAND,
    placements: ['4x5'],
  };

  const ok = await run({ transport, log: () => {} });

  assert.equal(ok, true);
  assert.equal(transport.calls.putFrame.length, 1);
  const frame = transport.calls.putFrame[0];
  assert.equal(frame.placementKey, '4x5');
  assert.equal(frame.index, undefined);
  // 4x5 is 1080x1350; deviceScaleFactor 2 -> 2160x2700.
  assert.equal(frame.width, 2160);
  assert.equal(frame.height, 2700);
  assert.equal(frame.contentType, 'image/jpeg');
  const dims = jpegDimensions(frame.bytes);
  assert.equal(dims.width, 2160);
  assert.equal(dims.height, 2700);
  assert.ok(Array.isArray(transport.calls.complete));
  assert.equal(transport.calls.fail, null);
});

test('run(): multiple placements each produce one PUT, in order, before completing', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const transport = fakeTransport();
  transport.spec = {
    renderId: 'r2',
    previewOnly: false,
    creative: { layout: 'bleed', headline: 'Two *sizes*.' },
    brand: BRAND,
    placements: ['1x1', '9x16'],
  };

  const ok = await run({ transport, log: () => {} });

  assert.equal(ok, true);
  assert.deepEqual(transport.calls.putFrame.map((f) => f.placementKey), ['1x1', '9x16']);
  assert.equal(transport.calls.putFrame[0].width, 2160); // 1x1 -> 1080x1080 @2x
  assert.equal(transport.calls.putFrame[0].height, 2160);
  assert.equal(transport.calls.putFrame[1].width, 2160); // 9x16 -> 1080x1920 @2x
  assert.equal(transport.calls.putFrame[1].height, 3840);
  assert.ok(transport.calls.putFrame.every((f) => f.contentType === 'image/jpeg'));
});

test('run(): a story sequence uploads one indexed frame per beat at the story placement', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const transport = fakeTransport();
  transport.spec = {
    renderId: 'r3',
    previewOnly: false,
    creative: {
      layout: 'bleed',
      headline: 'A *thing*.',
      sequenceKind: 'story',
      sequence: [{ headline: 'One *thing*.' }, { headline: 'Two *things*.' }],
    },
    brand: BRAND,
    placements: ['story'],
  };

  const ok = await run({ transport, log: () => {} });

  assert.equal(ok, true);
  assert.deepEqual(
    transport.calls.putFrame.map((f) => [f.placementKey, f.index]),
    [['story', 0], ['story', 1]]
  );
});

test('run(): previewOnly renders exactly one contact-sheet frame named "sheet"', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const transport = fakeTransport();
  transport.spec = {
    renderId: 'r4',
    previewOnly: true,
    creative: { layout: 'stacked', headline: 'Preview *me*.' },
    brand: BRAND,
    placements: ['9x16', '4x5', '1x1'],
  };

  const ok = await run({ transport, log: () => {} });

  assert.equal(ok, true);
  assert.equal(transport.calls.putFrame.length, 1);
  assert.equal(transport.calls.putFrame[0].placementKey, 'sheet');
  // A 1x JPEG, so preview_creative can hand it to a chat under the ~1 MB inline limit.
  assert.equal(transport.calls.putFrame[0].contentType, 'image/jpeg');
  const sheet = transport.calls.putFrame[0];
  const dims = jpegDimensions(sheet.bytes);
  assert.deepEqual([dims.width, dims.height], [sheet.width, sheet.height]);
  assert.ok(sheet.bytes.length < 1_000_000, `sheet is ${sheet.bytes.length} bytes`);
});

test('run(): an unknown placement key fails the render and uploads nothing', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const transport = fakeTransport();
  transport.spec = {
    renderId: 'r5',
    previewOnly: false,
    creative: { layout: 'stacked', headline: 'x *y*.' },
    brand: BRAND,
    placements: ['not-a-real-placement'],
  };

  const ok = await run({ transport, log: () => {} });

  assert.equal(ok, false);
  assert.equal(transport.calls.putFrame.length, 0);
  assert.equal(transport.calls.complete, null);
  assert.ok(transport.calls.fail, 'expected transport.fail to have been called');
  assert.match(transport.calls.fail.message, /unknown placement/);
});

test('run(): a spec fetch failure fails immediately with no frames rendered', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const transport = fakeTransport();
  transport.getSpec = async () => {
    throw new Error('backend unreachable');
  };

  const ok = await run({ transport, log: () => {} });

  assert.equal(ok, false);
  assert.equal(transport.calls.putFrame.length, 0);
  assert.deepEqual(transport.calls.fail, { message: 'backend unreachable', log: undefined });
});

/* ── framesFor: pure, no browser needed ───────────────────────────────── */

test('framesFor: previewOnly always wins, ignoring any sequence', () => {
  const frames = framesFor({
    previewOnly: true,
    creative: { sequenceKind: 'story', sequence: [{ headline: 'a' }] },
    placements: ['9x16'],
  });
  assert.deepEqual(frames, [{ page: 'sheet.html', placementKey: 'sheet' }]);
});

test('framesFor: a carousel produces one indexed frame per card at the carousel ratio', () => {
  const frames = framesFor({
    previewOnly: false,
    creative: { sequenceKind: 'carousel', sequence: [{}, {}, {}] },
    placements: ['4x5'],
  });
  assert.deepEqual(frames, [
    { page: 'frame.html', placementKey: '4x5', index: 0 },
    { page: 'frame.html', placementKey: '4x5', index: 1 },
    { page: 'frame.html', placementKey: '4x5', index: 2 },
  ]);
});

test('framesFor: a plain creative produces one frame per placement, no index', () => {
  const frames = framesFor({ previewOnly: false, creative: {}, placements: ['1x1', '4x5'] });
  assert.deepEqual(frames, [
    { page: 'frame.html', placementKey: '1x1' },
    { page: 'frame.html', placementKey: '4x5' },
  ]);
});
