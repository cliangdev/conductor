/* file-transport.test.mjs — job/file-transport.mjs, the transport that renders a DRAFT spec into a
 * local directory instead of talking to the backend. The unit tests need no browser; the last test
 * drives run() end to end against a real Chromium (skipped when none is installed) with a local photo
 * that only exists as `local:photo` in the spec. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, rmSync, existsSync, readdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createFileTransport, localKeysIn } from '../job/file-transport.mjs';
import { run, isChromiumAvailable } from '../job/render.mjs';

// A valid 1x1 red PNG.
const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==',
  'base64'
);

function tmp(prefix) {
  return mkdtempSync(join(tmpdir(), prefix));
}

const BRAND = { tokens: { accent: '#FF5A5F', darkBg: '#1C1C1E', darkInk: '#FFFFFF' } };

function draftSpec(extra = {}) {
  return {
    renderId: 'draft',
    previewOnly: false,
    creative: { layout: 'stacked', theme: 'dark', headline: 'Plan the week in *one sentence*.', photoUrl: 'local:photo' },
    brand: BRAND,
    placements: ['4x5'],
    ...extra,
  };
}

test('localKeysIn finds every local:<key> in nested spec fields', () => {
  const spec = draftSpec({
    creative: {
      photoUrl: 'local:photo',
      backgroundVideoUrl: 'local:clip',
      audio: { source: 'track', trackUrl: 'local:audio' },
      sequence: [{ photoUrl: 'local:beat-1' }, { photoUrl: 'https://x.test/a.jpg' }],
    },
  });
  assert.deepEqual(localKeysIn(spec).sort(), ['audio', 'beat-1', 'clip', 'photo']);
});

test('getSpec replaces local:<key> with http URLs that serve the local file (with Range support)', async () => {
  const dir = tmp('cc-ft-');
  const photo = join(dir, 'My Photo.PNG');
  writeFileSync(photo, PNG_1X1);
  const transport = createFileTransport({ spec: draftSpec(), localFiles: { photo }, outDir: join(dir, 'out') });
  try {
    const spec = await transport.getSpec();
    assert.match(spec.creative.photoUrl, /^http:\/\/127\.0\.0\.1:\d+\/photo\.png$/);
    assert.equal(transport.getRenderId(), 'draft');

    const res = await fetch(spec.creative.photoUrl);
    assert.equal(res.status, 200);
    assert.equal(res.headers.get('access-control-allow-origin'), '*');
    assert.deepEqual(Buffer.from(await res.arrayBuffer()), PNG_1X1);

    const ranged = await fetch(spec.creative.photoUrl, { headers: { range: 'bytes=0-3' } });
    assert.equal(ranged.status, 206);
    assert.equal(ranged.headers.get('content-range'), `bytes 0-3/${PNG_1X1.length}`);
    assert.deepEqual(Buffer.from(await ranged.arrayBuffer()), PNG_1X1.subarray(0, 4));

    // Only the mapped files are reachable, not the rest of the directory the photo came from.
    assert.equal((await fetch(spec.creative.photoUrl.replace('photo.png', 'other.png'))).status, 404);
    assert.equal((await fetch(spec.creative.photoUrl.replace('/photo.png', '/../x'))).status === 200, false);
  } finally {
    await transport.close();
    rmSync(dir, { recursive: true, force: true });
  }
});

test('getSpec leaves a spec with no local refs alone (no server started)', async () => {
  const dir = tmp('cc-ft-');
  const spec = draftSpec({ creative: { layout: 'stacked', headline: 'x *y*.', photoUrl: 'https://x.test/p.jpg' } });
  const transport = createFileTransport({ spec, outDir: dir });
  assert.deepEqual(await transport.getSpec(), spec);
  await transport.close();
  rmSync(dir, { recursive: true, force: true });
});

test('getSpec rejects when a local:<key> has no file, and records the reason', async () => {
  const dir = tmp('cc-ft-');
  const transport = createFileTransport({ spec: draftSpec(), localFiles: {}, outDir: dir });
  await assert.rejects(() => transport.getSpec(), /local:photo/);
  assert.match(transport.getError(), /local:photo/);
  assert.equal(transport.getRenderId(), undefined);
  rmSync(dir, { recursive: true, force: true });
});

test('putFrame / putPoster / complete write the named files and manifest.json', async () => {
  const dir = tmp('cc-ft-');
  const outDir = join(dir, 'nested', 'out');
  const transport = createFileTransport({ spec: draftSpec({ creative: { layout: 'stacked', headline: 'x *y*.' } }), outDir });
  await transport.getSpec();
  await transport.putFrame('sheet', { width: 100, height: 50, bytes: Buffer.from('S'), contentType: 'image/jpeg' });
  await transport.putFrame('story', { index: 1, width: 10, height: 20, bytes: Buffer.from('J'), contentType: 'image/jpeg' });
  await transport.putFrame('9x16', {
    width: 1080, height: 1920, bytes: Buffer.from('V'), contentType: 'video/mp4', durationSeconds: 8, hasAudio: true,
  });
  await transport.putPoster('9x16', Buffer.from('P'));
  await transport.complete([{ placementKey: '9x16', message: 'tight margin' }]);

  assert.deepEqual(readdirSync(outDir).sort(), ['9x16.mp4', 'manifest.json', 'poster-9x16.jpg', 'sheet.jpg', 'story-1.jpg']);
  const manifest = JSON.parse(readFileSync(join(outDir, 'manifest.json'), 'utf8'));
  assert.equal(manifest.ok, true);
  assert.deepEqual(manifest.warnings, [{ placementKey: '9x16', message: 'tight margin' }]);
  const video = manifest.frames.find((f) => f.placementKey === '9x16');
  assert.equal(video.file, '9x16.mp4');
  assert.equal(video.poster, 'poster-9x16.jpg');
  assert.equal(video.durationSeconds, 8);
  assert.equal(video.hasAudio, true);
  assert.equal(manifest.frames.find((f) => f.placementKey === 'story').index, 1);
  rmSync(dir, { recursive: true, force: true });
});

test('fail records the error in manifest.json and closes the asset server', async () => {
  const dir = tmp('cc-ft-');
  const photo = join(dir, 'p.png');
  writeFileSync(photo, PNG_1X1);
  const outDir = join(dir, 'out');
  const transport = createFileTransport({ spec: draftSpec(), localFiles: { photo }, outDir });
  const spec = await transport.getSpec();
  await transport.fail('boom');
  assert.equal(transport.getError(), 'boom');
  const manifest = JSON.parse(readFileSync(join(outDir, 'manifest.json'), 'utf8'));
  assert.equal(manifest.ok, false);
  assert.equal(manifest.error, 'boom');
  await assert.rejects(() => fetch(spec.creative.photoUrl)); // server is closed
  rmSync(dir, { recursive: true, force: true });
});

let chromiumAvailable = null;
async function browserAvailable() {
  if (chromiumAvailable === null) chromiumAvailable = await isChromiumAvailable();
  return chromiumAvailable;
}

test('run() with a file transport renders a draft whose photo only exists locally', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');
  const dir = tmp('cc-ft-');
  const photo = join(dir, 'photo.png');
  writeFileSync(photo, PNG_1X1);
  const outDir = join(dir, 'out');
  const transport = createFileTransport({ spec: draftSpec({ previewOnly: true }), localFiles: { photo }, outDir });

  const ok = await run({ transport, log: () => {} });

  assert.equal(ok, true, transport.getError());
  assert.ok(existsSync(join(outDir, 'sheet.jpg')));
  const manifest = JSON.parse(readFileSync(join(outDir, 'manifest.json'), 'utf8'));
  assert.equal(manifest.ok, true);
  assert.equal(manifest.frames[0].placementKey, 'sheet');
  assert.equal(readFileSync(join(outDir, 'sheet.jpg')).subarray(0, 2).toString('hex'), 'ffd8');
  rmSync(dir, { recursive: true, force: true });
});

test('run() with a file transport reports a missing local file as a failure', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');
  const dir = tmp('cc-ft-');
  const transport = createFileTransport({ spec: draftSpec(), localFiles: {}, outDir: join(dir, 'out') });
  const ok = await run({ transport, log: () => {} });
  assert.equal(ok, false);
  assert.match(transport.getError(), /local:photo/);
  rmSync(dir, { recursive: true, force: true });
});
