/* job-motion.test.mjs — job/render.mjs's MOTION path (`run()` against a
 * `creative.kind === 'MOTION'` spec), end to end against a real Playwright
 * Chromium AND a real local ffmpeg — the two external binaries this path
 * actually shells out to. No network beyond a couple of throwaway
 * `node:http` fixture servers this file starts itself (for a "clip"/"track"
 * URL ffmpeg downloads), and an in-memory FAKE TRANSPORT (see job.test.mjs's
 * own header comment for why: this is exactly the render core's four-method
 * transport contract, decoupled from today's HTTP shape).
 *
 * Needs a local Chromium (`npx playwright install chromium`) AND a local
 * `ffmpeg`/`ffprobe` on PATH; every test skips itself when either is
 * missing, rather than failing a machine that never set either up.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';
import { run, isChromiumAvailable } from '../job/render.mjs';

const execFileAsync = promisify(execFile);

let chromiumAvailable = null;
async function browserAvailable() {
  if (chromiumAvailable === null) chromiumAvailable = await isChromiumAvailable();
  return chromiumAvailable;
}

function ffmpegAvailable() {
  try {
    execFileSync('ffmpeg', ['-version'], { stdio: 'ignore' });
    execFileSync('ffprobe', ['-version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

async function skipUnlessReady(t) {
  if (!(await browserAvailable())) {
    t.skip('no local Chromium (run: npx playwright install chromium)');
    return false;
  }
  if (!ffmpegAvailable()) {
    t.skip('no local ffmpeg/ffprobe on PATH');
    return false;
  }
  return true;
}

async function ffprobe(bytes) {
  const dir = mkdtempSync(join(tmpdir(), 'cc-motion-test-'));
  const path = join(dir, 'probe.mp4');
  writeFileSync(path, bytes);
  try {
    const { stdout } = await execFileAsync('ffprobe', [
      '-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', path,
    ]);
    return JSON.parse(stdout);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/* A tiny node:http server handing back one fixed buffer, for a `backgroundVideoUrl`/`audio.trackUrl`
 * the job downloads via `fetch()` — which does not support `file://`, so a real (loopback) HTTP URL
 * is the simplest fixture. */
function serveBytes(bytes, contentType) {
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      res.writeHead(200, { 'content-type': contentType, 'content-length': bytes.length });
      res.end(bytes);
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, origin: `http://127.0.0.1:${server.address().port}` }));
  });
}

function generateClipMp4({ withAudio = false, size = '320x240' } = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'cc-motion-fixture-'));
  const path = join(dir, 'clip.mp4');
  execFileSync('ffmpeg', [
    '-y', '-f', 'lavfi', '-i', `testsrc=size=${size}:rate=10`,
    ...(withAudio ? ['-f', 'lavfi', '-i', 'sine=frequency=440'] : []),
    '-t', '3', '-pix_fmt', 'yuv420p', '-c:v', 'libx264', ...(withAudio ? ['-c:a', 'aac'] : []), path,
  ]);
  const bytes = readFileSync(path);
  rmSync(dir, { recursive: true, force: true });
  return bytes;
}

function generateSineWav() {
  const dir = mkdtempSync(join(tmpdir(), 'cc-motion-fixture-'));
  const path = join(dir, 'track.wav');
  execFileSync('ffmpeg', ['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=3', path]);
  const bytes = readFileSync(path);
  rmSync(dir, { recursive: true, force: true });
  return bytes;
}

// A minimal 1x1 transparent PNG data URI — decodes fine in Chromium, no network fetch needed.
const TINY_PHOTO = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=';

function fakeTransport(spec) {
  const calls = { putFrame: [], putPoster: [], complete: null, fail: null };
  return {
    calls,
    spec,
    async getSpec() {
      return this.spec;
    },
    async putFrame(placementKey, opts) {
      calls.putFrame.push({ placementKey, ...opts });
    },
    async putPoster(placementKey, bytes, opts) {
      calls.putPoster.push({ placementKey, bytes, ...(opts || {}) });
    },
    async complete(warnings) {
      calls.complete = warnings;
    },
    async fail(message, log) {
      calls.fail = { message, log };
    },
  };
}

test('run(): a 3s photo creative at 10fps renders an h264 MP4 + poster for one placement', async (t) => {
  if (!(await skipUnlessReady(t))) return;

  const transport = fakeTransport({
    renderId: 'm1',
    previewOnly: false,
    creative: {
      kind: 'MOTION',
      layout: 'stacked',
      theme: 'dark',
      headline: 'Plan the week in *one sentence*.',
      body: 'Body copy.',
      photoUrl: TINY_PHOTO,
      motion: { durationSec: 3, preset: 'fade-up', background: { source: 'photo', motion: 'zoom-in' } },
      audio: { source: 'none' },
    },
    brand: {},
    placements: ['1x1'],
  });

  const started = Date.now();
  const ok = await run({ transport, ffmpegPath: 'ffmpeg', fps: 10, log: () => {} });
  const elapsedMs = Date.now() - started;

  assert.equal(ok, true);
  assert.equal(transport.calls.fail, null);
  assert.equal(transport.calls.putFrame.length, 1);
  const frame = transport.calls.putFrame[0];
  assert.equal(frame.placementKey, '1x1');
  assert.equal(frame.contentType, 'video/mp4');
  assert.equal(frame.width, 1080);
  assert.equal(frame.height, 1080);
  assert.equal(frame.durationSeconds, 3);
  assert.equal(frame.hasAudio, false);

  const probe = await ffprobe(frame.bytes);
  const videoStream = probe.streams.find((s) => s.codec_type === 'video');
  assert.equal(videoStream.codec_name, 'h264');
  // Limited-range yuv420p, not the full-range yuvj420p the piped JPEGs carry — platforms expect it.
  assert.equal(videoStream.pix_fmt, 'yuv420p');
  assert.equal(videoStream.width, 1080);
  assert.equal(videoStream.height, 1080);
  const duration = Number(probe.format.duration);
  assert.ok(Math.abs(duration - 3) < 0.5, `expected ~3s, got ${duration}s`);
  assert.equal(probe.streams.some((s) => s.codec_type === 'audio'), false);

  assert.equal(transport.calls.putPoster.length, 1);
  assert.equal(transport.calls.putPoster[0].placementKey, '1x1');
  assert.ok(transport.calls.putPoster[0].bytes.length > 0);
  // JPEG SOI marker
  assert.equal(transport.calls.putPoster[0].bytes[0], 0xff);
  assert.equal(transport.calls.putPoster[0].bytes[1], 0xd8);

  assert.ok(Array.isArray(transport.calls.complete));
  console.log(`[timing] 3s@10fps MOTION render: ${elapsedMs}ms`);
});

test('run(): a track audio source produces an MP4 with an AAC stream', async (t) => {
  if (!(await skipUnlessReady(t))) return;

  const track = generateSineWav();
  const { server, origin } = await serveBytes(track, 'audio/wav');
  try {
    const transport = fakeTransport({
      renderId: 'm2',
      previewOnly: false,
      creative: {
        kind: 'MOTION',
        layout: 'bleed',
        headline: 'Sound *on*.',
        photoUrl: TINY_PHOTO,
        motion: { durationSec: 2, preset: 'none', background: { source: 'photo', motion: 'none' }, endCard: false },
        audio: { source: 'track', trackUrl: `${origin}/track.wav`, volume: 0.5, fadeOutSec: 0.5 },
      },
      brand: {},
      placements: ['1x1'],
    });

    const ok = await run({ transport, ffmpegPath: 'ffmpeg', fps: 10, log: () => {} });
    assert.equal(ok, true);
    const frame = transport.calls.putFrame[0];
    assert.equal(frame.hasAudio, true);

    const probe = await ffprobe(frame.bytes);
    const audioStream = probe.streams.find((s) => s.codec_type === 'audio');
    assert.ok(audioStream, 'expected an audio stream');
    assert.equal(audioStream.codec_name, 'aac');
  } finally {
    server.close();
  }
});

test('run(): a clip background source renders successfully', async (t) => {
  if (!(await skipUnlessReady(t))) return;

  const clip = generateClipMp4();
  const { server, origin } = await serveBytes(clip, 'video/mp4');
  try {
    const transport = fakeTransport({
      renderId: 'm3',
      previewOnly: false,
      creative: {
        kind: 'MOTION',
        layout: 'bleed',
        headline: 'Clip *background*.',
        backgroundVideoUrl: `${origin}/clip.mp4`,
        clipStartSec: 0,
        motion: { durationSec: 2, preset: 'fade-up', background: { source: 'clip' } },
        audio: { source: 'none' },
      },
      brand: {},
      placements: ['1x1'],
    });

    const ok = await run({ transport, ffmpegPath: 'ffmpeg', fps: 10, log: () => {} });
    assert.equal(ok, true);
    assert.equal(transport.calls.putFrame.length, 1);
    const probe = await ffprobe(transport.calls.putFrame[0].bytes);
    assert.equal(probe.streams.find((s) => s.codec_type === 'video').codec_name, 'h264');
  } finally {
    server.close();
  }
});

// The spec as the API sends it: the clip lives under motion.background (clipUrl, clipStartSec), not in
// the engine's own backgroundVideoUrl. Both the picture and the clip's sound must come through.
test('run(): an API-shaped clip background renders the clip and carries its sound', async (t) => {
  if (!(await skipUnlessReady(t))) return;

  // A 9:16 clip behind a 1:1 frame: ffmpeg's default stream choice would take the clip's larger picture
  // instead of the rendered frames, so this also proves the frames are what gets encoded.
  const clip = generateClipMp4({ withAudio: true, size: '1080x1920' });
  const { server, origin } = await serveBytes(clip, 'video/mp4');
  try {
    const transport = fakeTransport({
      renderId: 'm5',
      previewOnly: false,
      creative: {
        kind: 'MOTION',
        layout: 'bleed',
        headline: 'Clip *background*.',
        motion: { durationSec: 2, preset: 'fade-up', background: { source: 'clip', clipUrl: `${origin}/clip.mp4`, clipStartSec: 0.5 } },
        audio: { source: 'clip' },
      },
      brand: {},
      placements: ['1x1'],
    });

    const ok = await run({ transport, ffmpegPath: 'ffmpeg', fps: 10, log: () => {} });
    assert.equal(ok, true);
    const probe = await ffprobe(transport.calls.putFrame[0].bytes);
    assert.ok(probe.streams.find((s) => s.codec_type === 'audio'), 'the clip sound is in the MP4');
    const video = probe.streams.find((s) => s.codec_type === 'video');
    assert.deepEqual([video.width, video.height], [1080, 1080], 'the rendered 1:1 frames, not the 9:16 clip');
    assert.equal(transport.calls.putFrame[0].hasAudio, true);
  } finally {
    server.close();
  }
});

test('run(): an end-card assertion failure fails the render and uploads nothing', async (t) => {
  if (!(await skipUnlessReady(t))) return;

  const transport = fakeTransport({
    renderId: 'm4',
    previewOnly: false,
    creative: {
      kind: 'MOTION',
      layout: 'stacked',
      headline: 'Broken *photo*.',
      // Nothing listens on loopback port 1: the <img> fails fast and deterministically (connection
      // refused), so assertions.js's "image(s) failed to load" check fires on the end-card frame —
      // the board still BUILDS fine (renderBoard/fitBoard never wait on the photo), so this exercises
      // the __assertBoard() path specifically, not an early boot failure.
      photoUrl: 'http://127.0.0.1:1/missing.jpg',
      motion: { durationSec: 1, preset: 'none' },
      audio: { source: 'none' },
    },
    brand: {},
    placements: ['1x1'],
  });

  const ok = await run({ transport, ffmpegPath: 'ffmpeg', fps: 10, log: () => {} });

  assert.equal(ok, false);
  assert.equal(transport.calls.putFrame.length, 0);
  assert.equal(transport.calls.putPoster.length, 0);
  assert.equal(transport.calls.complete, null);
  assert.ok(transport.calls.fail, 'expected transport.fail to have been called');
  assert.match(transport.calls.fail.message, /failed to load/);
});

test('run(): a MOTION render with no ffmpegPath fails with a clear error', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const transport = fakeTransport({
    renderId: 'm5',
    previewOnly: false,
    creative: { kind: 'MOTION', layout: 'stacked', headline: 'x *y*.', photoUrl: TINY_PHOTO, motion: {} },
    brand: {},
    placements: ['1x1'],
  });

  const ok = await run({ transport, log: () => {} }); // no ffmpegPath

  assert.equal(ok, false);
  assert.equal(transport.calls.putFrame.length, 0);
  assert.match(transport.calls.fail.message, /ffmpeg/);
});

test('run(): a MOTION preview with checkPlacements checks the end-card frame and encodes no video', async (t) => {
  if (!(await browserAvailable())) return t.skip('no local Chromium (run: npx playwright install chromium)');

  const badge = 'data:image/svg+xml;utf8,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="480" height="160"><rect width="480" height="160" fill="black"/></svg>'
  );
  const transport = fakeTransport({
    renderId: 'm-check',
    previewOnly: true,
    creative: {
      kind: 'MOTION',
      layout: 'stacked',
      theme: 'dark',
      headline: 'Plan the week in *one sentence*.',
      photoUrl: TINY_PHOTO,
      motion: { durationSec: 3, preset: 'fade-up', background: { source: 'photo', motion: 'zoom-in' } },
      audio: { source: 'none' },
    },
    brand: { logos: { badge }, ctaClaim: 'Free on the App Store' },
    placements: ['9x16'],
  });
  let checks;
  transport.complete = async (warnings, c) => {
    checks = c;
  };

  // No ffmpegPath: a preview must not need (or start) an encoder.
  const ok = await run({ transport, checkPlacements: true, fps: 10, log: () => {} });

  assert.equal(ok, true);
  assert.deepEqual(transport.calls.putFrame.map((f) => f.placementKey), ['sheet']);
  assert.equal(transport.calls.putPoster.length, 0);
  const failures = checks.filter((c) => c.severity === 'error');
  assert.equal(failures.length, 1, JSON.stringify(checks));
  assert.equal(failures[0].rule, 'safeZone');
  assert.equal(failures[0].placementKey, '9x16');
});
