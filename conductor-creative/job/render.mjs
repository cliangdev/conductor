#!/usr/bin/env node
/* render.mjs — the T3 render job's rendering core, plus a thin CLI entry
 * point at the bottom.
 *
 * `run()` below knows nothing about HTTP, Cloud Run, or a Conductor
 * Workflow — it takes a `transport` object (see transport.mjs) with four
 * methods (getSpec, putFrame, complete, fail) and drives Playwright against
 * this package's own frame.html/sheet.html. That split exists because how
 * this job is *launched and reports back* is expected to change (a direct
 * Cloud Run Job launch talking straight REST vs. sitting behind a Conductor
 * Workflow) while the rendering behavior — what gets rendered, how it is
 * checked, and what "done" or "failed" mean — should not have to change
 * alongside it. Tests exercise `run()` directly with an in-memory fake
 * transport (test/job.test.mjs); transport.mjs's HTTP behavior is tested on
 * its own (test/transport.test.mjs).
 *
 * Exit code (CLI only): 0 on success, 1 on any failure (spec fetch, page
 * load, an assertion, an upload, or a timeout on any of those).
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { spawn } from 'node:child_process';
import { mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { startServer } from './server.mjs';
import { createApiTransport } from './transport.mjs';
import { motionKeyTimes, normalizeMotionCreative } from '../motion.js';
import {
  audioSourceUrl, buildClipAudioArgs, buildClipAudioMeasureArgs, buildFfmpegArgs, buildSlideshowArgs,
  buildSlideshowMeasureArgs, resolveSlideDurations, probeMedia, runFfmpeg, runLoudnormTwoPass, slideshowDuration,
  SLIDE_FPS, SLIDE_H, SLIDE_W,
} from './media.mjs';

export { audioSourceUrl, buildFfmpegArgs };
export * from './media.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PACKAGE_ROOT = join(HERE, '..'); // conductor-creative/, the static root

const PAGE_TIMEOUT_MS = 20_000;

/* Placement frames are JPEG — Instagram feed images and TikTok photo posts both refuse PNG (see
 * MediaTargetValidator on the backend) — at quality 92, a level export tooling generally treats as
 * visually lossless while still compressing meaningfully smaller than PNG. The `sheet` contact sheet
 * (a preview-only render, never attached to a Post) is a 1x JPEG: it exists to be looked at inside a
 * chat (preview_creative returns it as an image), which caps it near 1 MB, and a 2x PNG grid of every
 * placement runs well past that. Every `.cc-board` paints an explicit
 * `background-color` (frame.css) — dark or light theme — so there is no transparency for JPEG's opaque
 * export to clip. */
const PLACEMENT_FRAME_SCREENSHOT = { type: 'jpeg', quality: 92 };
const PLACEMENT_FRAME_CONTENT_TYPE = 'image/jpeg';
const SHEET_SCREENSHOT = { type: 'jpeg', quality: 85 };
const SHEET_CONTENT_TYPE = 'image/jpeg';
const FRAME_SCALE = 2;
const SHEET_SCALE = 1;

/* MOTION frames are captured at deviceScaleFactor 1 (the placement's true pixel size) — video, unlike
 * a placement JPEG, is never worth doubling: it would roughly quadruple every per-frame screenshot
 * and the ffmpeg encode, for no visible gain on any surface that plays this file back. Quality 90 (a
 * hair below the placement JPEG's 92) keeps each frame small; H.264's own compression dominates the
 * final file size regardless. */
const MOTION_FRAME_SCREENSHOT = { type: 'jpeg', quality: 90 };
const MOTION_DEVICE_SCALE_FACTOR = 1;
const DEFAULT_FPS = 30;

async function defaultBrowserFactory() {
  const { chromium } = await import('playwright');
  return chromium.launch();
}

/** True when a local Chromium can actually launch. `playwright` lives only
 * in job/node_modules (see job/package.json's header comment on why it is
 * not a conductor-creative/ dependency), so anything outside job/ — e.g.
 * test/job.test.mjs, one directory up — cannot `import('playwright')`
 * itself and resolve it; it calls this instead, which resolves relative to
 * THIS file. Tests use it to skip cleanly on a machine that never ran
 * `npx playwright install chromium`. */
export async function isChromiumAvailable() {
  try {
    const browser = await defaultBrowserFactory();
    await browser.close();
    return true;
  } catch {
    return false;
  }
}

/* Which page + placementKey(s) this render needs, from the spec's own
 * shape — see the internal API contract in transport.mjs's header comment.
 * A story/carousel's beats are separate indexed frames at the sequence's one
 * placement key; a plain creative renders each of its placements once;
 * `previewOnly` always renders exactly one contact-sheet frame, regardless
 * of a sequence, uploaded as placementKey "sheet". */
export function framesFor(spec) {
  const { creative, placements, previewOnly } = spec;
  if (previewOnly) return [{ page: 'sheet.html', placementKey: 'sheet' }];
  const isSequence = creative.sequenceKind && Array.isArray(creative.sequence) && creative.sequence.length;
  if (!placements || !placements.length) {
    throw new Error('the render spec names no placements to render (check the Brand Kit\'s enabled placements, or the carousel ratio)');
  }
  if (isSequence) {
    const key = placements[0];
    return creative.sequence.map((_, index) => ({ page: 'frame.html', placementKey: key, index }));
  }
  return (placements || []).map((placementKey) => ({ page: 'frame.html', placementKey }));
}

/* ── MOTION: frame-stepped video capture ──────────────────────────────────── */

/* Writes `chunk` to `stream` and resolves once Node has flushed it, which
 * naturally serializes the capture-then-write pipeline (screenshot, write,
 * await, screenshot, write, ...) without any separate backpressure/'drain'
 * handling — the write for frame i+1 never starts until frame i's bytes have
 * actually left the process. */
function writeAsync(stream, chunk) {
  return new Promise((resolve, reject) => {
    stream.write(chunk, (err) => (err ? reject(err) : resolve()));
  });
}

async function downloadToTemp(url, dir, name, what = 'audio') {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`failed to download ${what} (${url}): ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  const path = join(dir, name);
  await writeFile(path, buf);
  return path;
}

/* Renders ONE placement's MOTION video: one frame.html page load, then N
 * in-page seeks (frame.js's window.__seekMotion — see its header comment for
 * why this is not N page loads), streaming each screenshot into an ffmpeg
 * child process. Runs the layout assertions exactly once, on the final
 * (end-card) frame; a failure throws before any upload happens. Resolves
 * `{ bytes, posterBytes, width, height, durationSeconds, hasAudio, warnings }`. */
async function renderMotionPlacement({ browser, origin, spec, placementKey, ffmpegPath, fps, log }) {
  const motion = spec.creative.motion || {};
  const durationSec = motion.durationSec || 8;
  const frameCount = Math.max(1, Math.round(durationSec * fps));
  const warnings = [];

  const tmpDir = await mkdtemp(join(tmpdir(), 'cc-motion-'));
  const page = await browser.newPage({ deviceScaleFactor: MOTION_DEVICE_SCALE_FACTOR });
  let ffmpeg;
  try {
    await page.addInitScript((s) => {
      window.__RENDER_SPEC__ = s;
    }, {
      creative: spec.creative,
      brand: spec.brand,
      placementKey,
      motion: spec.creative.motion,
      time: 0,
      clipStartSec: spec.creative.clipStartSec,
    });
    await page.goto(`${origin}/frame.html`, { waitUntil: 'load', timeout: PAGE_TIMEOUT_MS });
    await page.waitForFunction(() => window.__ready === true, null, { timeout: PAGE_TIMEOUT_MS });
    const boot = await page.evaluate(() => window.__RENDER_RESULT);
    if (!boot || !boot.ok) {
      throw new Error(`${placementKey}: ${(boot && boot.error) || 'failed to build the MOTION board'}`);
    }
    const box = await page.locator('.cc-board').first().boundingBox();
    const width = Math.round((box && box.width) || 0);
    const height = Math.round((box && box.height) || 0);

    const srcUrl = audioSourceUrl(spec.creative);
    const audioPath = srcUrl ? await downloadToTemp(srcUrl, tmpDir, 'audio-src') : null;
    const audio = spec.creative.audio;

    const outPath = join(tmpDir, `${placementKey}.mp4`);
    const args = buildFfmpegArgs({
      fps,
      durationSec,
      audioPath,
      audioSource: audio && audio.source,
      clipStartSec: spec.creative.clipStartSec,
      volume: audio && audio.volume,
      fadeOutSec: audio && audio.fadeOutSec,
      outPath,
    });
    ffmpeg = spawn(ffmpegPath, args, { stdio: ['pipe', 'ignore', 'pipe'] });
    // If ffmpeg exits early, the next frame write fails with EPIPE; without a listener that 'error' event
    // is unhandled and kills the whole process (no failure is reported and the render stays RUNNING).
    // The write's own callback rejects too, and the loop below turns that into ffmpeg's real message.
    ffmpeg.stdin.on('error', () => {});
    let ffmpegErr = '';
    ffmpeg.stderr.on('data', (chunk) => {
      ffmpegErr += chunk.toString();
    });
    const ffmpegExit = new Promise((resolve, reject) => {
      ffmpeg.on('error', reject);
      ffmpeg.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited ${code}: ${ffmpegErr.slice(-2000)}`))));
    });
    // An end-card assertion failure (below) throws before `await ffmpegExit` is ever reached, and the
    // `finally` block then kills ffmpeg — which rejects this same promise with nothing left awaiting
    // it. Without this, that becomes an unhandled rejection; this does not change what the success
    // path below still awaits and reports.
    ffmpegExit.catch(() => {});

    let posterBytes = null;
    let lastLoggedPct = -25;
    for (let i = 0; i < frameCount; i += 1) {
      const t = i / fps;
      const seeked = await page.evaluate((tt) => window.__seekMotion(tt), t);
      if (!seeked) throw new Error(`${placementKey}: motion seek failed at t=${t.toFixed(2)}s`);

      if (i === frameCount - 1) {
        const assertion = await page.evaluate(() => window.__assertBoard());
        if (!assertion || (assertion.errors && assertion.errors.length)) {
          const detail = (assertion && assertion.errors && assertion.errors.join('; ')) || 'end-card assertions failed';
          throw new Error(`${placementKey}: ${detail}`);
        }
        (assertion.warnings || []).forEach((message) => warnings.push(message));
      }

      const bytes = await page.locator('.cc-board').first().screenshot(MOTION_FRAME_SCREENSHOT);
      if (i === frameCount - 1) posterBytes = bytes;
      try {
        await writeAsync(ffmpeg.stdin, bytes);
      } catch (err) {
        // ffmpeg stopped reading: report why it stopped (its exit message), not the pipe error. Give it a
        // moment to finish exiting so its stderr is complete.
        const exitErr = await Promise.race([
          ffmpegExit.then(() => null, (e) => e),
          new Promise((resolve) => setTimeout(() => resolve(null), 5000)),
        ]);
        throw new Error(`${placementKey}: ${exitErr ? exitErr.message : `ffmpeg stopped reading frames (${err.code || err.message}): ${ffmpegErr.slice(-1500)}`}`);
      }

      const pct = Math.floor(((i + 1) / frameCount) * 100);
      if (pct >= lastLoggedPct + 25 || i === frameCount - 1) {
        log(`${placementKey}: ${pct}% (${i + 1}/${frameCount} frames)`);
        lastLoggedPct = pct;
      }
    }
    ffmpeg.stdin.end();
    await ffmpegExit;
    ffmpeg = null;

    const bytes = await readFile(outPath);
    return { bytes, posterBytes, width, height, durationSeconds: durationSec, hasAudio: Boolean(audioPath), warnings };
  } finally {
    if (ffmpeg && ffmpeg.exitCode === null) {
      try { ffmpeg.kill('SIGKILL'); } catch { /* already gone */ }
    }
    await page.close().catch(() => {});
    await rm(tmpDir, { recursive: true, force: true }).catch(() => {});
  }
}

/* ── CAROUSEL slideshow / CLIP audio: ffmpeg only, no browser ───────────── */

function sniffImageExt(buf) {
  if (buf[0] === 0xff && buf[1] === 0xd8) return 'jpg';
  if (buf[0] === 0x89 && buf[1] === 0x50) return 'png';
  if (buf.slice(0, 4).toString() === 'RIFF' && buf.slice(8, 12).toString() === 'WEBP') return 'webp';
  return 'img';
}

async function downloadImage(url, dir, name) {
  const path = await downloadToTemp(url, dir, name, 'slide image');
  const ext = sniffImageExt((await readFile(path)).subarray(0, 12));
  const renamed = `${path}.${ext}`;
  await rename(path, renamed);
  return renamed;
}

function requireFfmpeg(ffmpegPath, what) {
  if (!ffmpegPath) {
    throw new Error(
      `${what} needs a local ffmpeg binary — pass \`ffmpegPath\` to run() (e.g. a system \`ffmpeg\` found via `
      + '`which ffmpeg`, or a bundled ffmpeg-static build).'
    );
  }
}

/* The CAROUSEL's 9:16 slideshow video: slides + optional track -> one H.264/AAC mp4 and a JPEG poster of the first slide. */
export async function renderSlideshow({ spec, ffmpegPath, log = () => {} }) {
  requireFfmpeg(ffmpegPath, 'a CAROUSEL slideshow render');
  const creative = spec.creative;
  const slides = (creative.slides || []).map((s) => (typeof s === 'string' ? s : s.url)).filter(Boolean);
  if (!slides.length) throw new Error('the slideshow spec lists no slides');
  const slideshow = creative.slideshow || {};
  const durations = resolveSlideDurations(slideshow.slideDurations, slides.length);
  const audio = creative.audio && creative.audio.source === 'track' && creative.audio.trackUrl ? creative.audio : null;

  const dir = await mkdtemp(join(tmpdir(), 'cc-slideshow-'));
  try {
    const slidePaths = [];
    for (let i = 0; i < slides.length; i += 1) slidePaths.push(await downloadImage(slides[i], dir, `slide-${i}`));
    const audioPath = audio ? await downloadToTemp(audio.trackUrl, dir, 'track') : null;
    const outPath = join(dir, 'slideshow.mp4');
    const encSpec = { slideDurations: durations, zoom: slideshow.zoom !== false, audio };
    const inputs = { slidePaths, audioPath, outPath };
    log(`rendering a ${slides.length}-slide slideshow (${slideshowDuration(durations)}s, ${audioPath ? 'with track' : 'silent'})`);
    if (audioPath) {
      await runLoudnormTwoPass({
        ffmpegPath, log,
        measureArgs: buildSlideshowMeasureArgs(encSpec, inputs),
        buildEncodeArgs: (measured) => buildSlideshowArgs(encSpec, inputs, measured),
      });
    } else {
      await runFfmpeg(ffmpegPath, buildSlideshowArgs(encSpec, inputs, null));
    }
    const posterPath = join(dir, 'poster.jpg');
    await runFfmpeg(ffmpegPath, [
      '-i', slidePaths[0], '-vf', `scale=${SLIDE_W}:${SLIDE_H}:force_original_aspect_ratio=increase,crop=${SLIDE_W}:${SLIDE_H}`,
      '-frames:v', '1', '-q:v', '2', '-update', '1', '-y', posterPath,
    ]);
    return {
      bytes: await readFile(outPath), posterBytes: await readFile(posterPath),
      width: SLIDE_W, height: SLIDE_H, fps: SLIDE_FPS,
      durationSeconds: slideshowDuration(durations), hasAudio: Boolean(audioPath),
    };
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => {});
  }
}

/* One CLIP placement: the clip's sound replaced (`track`), ducked under a track (`mix`) or removed (`none`). */
export async function renderClipAudio({ spec, placement, ffmpegPath, log = () => {} }) {
  requireFfmpeg(ffmpegPath, 'a CLIP audio render');
  const audio = spec.creative.audio || {};
  let source = audio.source;
  if (!placement.clipUrl) throw new Error(`placement ${placement.key}: the spec has no clipUrl`);
  if (source !== 'track' && source !== 'mix' && source !== 'none') throw new Error(`a CLIP render has nothing to do for audio source "${source}"`);
  if (source !== 'none' && !audio.trackUrl) throw new Error('the spec has no trackUrl for the chosen audio source');

  const dir = await mkdtemp(join(tmpdir(), 'cc-clip-'));
  try {
    const clipPath = await downloadToTemp(placement.clipUrl, dir, 'clip.mp4', 'clip');
    const trackPath = source === 'none' ? null : await downloadToTemp(audio.trackUrl, dir, 'track');
    const outPath = join(dir, 'out.mp4');
    const info = await probeMedia(ffmpegPath, clipPath);
    let encAudio = { ...audio, source };
    if (source === 'mix' && (placement.clipHasAudio === false || !info.hasAudio)) {
      log(`${placement.key}: the clip has no sound to mix under; using the track alone`);
      source = 'track';
      encAudio = { ...audio, source, volume: 1.0 };
    }
    let durationSec = placement.durationSec;
    if (!(Number.isFinite(durationSec) && durationSec > 0)) {
      if (!(info.durationSec > 0)) throw new Error(`placement ${placement.key}: the clip's duration is unknown`);
      durationSec = Math.round((info.durationSec - (placement.clipStartSec || 0)) * 1000) / 1000;
      log(`${placement.key}: duration probed from the clip: ${durationSec}s`);
    }
    if (!(durationSec > 0)) throw new Error(`placement ${placement.key}: clipStartSec is past the end of the clip`);
    const encSpec = { audio: encAudio };
    const p = { clipPath, trackPath, outPath, clipStartSec: placement.clipStartSec, durationSec };
    log(`${placement.key}: clip audio ${source}, ${durationSec}s`);
    if (source === 'none') {
      await runFfmpeg(ffmpegPath, buildClipAudioArgs(encSpec, p, null));
    } else {
      await runLoudnormTwoPass({
        ffmpegPath, log,
        measureArgs: buildClipAudioMeasureArgs(encSpec, p),
        buildEncodeArgs: (measured) => buildClipAudioArgs(encSpec, p, measured),
      });
    }
    const posterPath = join(dir, 'poster.jpg');
    await runFfmpeg(ffmpegPath, ['-i', outPath, '-frames:v', '1', '-q:v', '2', '-update', '1', '-y', posterPath]);
    const outInfo = await probeMedia(ffmpegPath, outPath);
    return {
      bytes: await readFile(outPath), posterBytes: await readFile(posterPath),
      width: outInfo.width || info.width || placement.width, height: outInfo.height || info.height || placement.height, durationSeconds: durationSec, hasAudio: source !== 'none',
    };
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => {});
  }
}

async function runLocalVideo({ spec, transport, ffmpegPath, log }) {
  const warnings = [];
  if (spec.creative.kind === 'CAROUSEL') {
    const placement = (spec.placements || [])[0] || { key: '9x16' };
    const key = placement.key || '9x16';
    const r = await renderSlideshow({ spec, ffmpegPath, log });
    // No `index`: this is the one extra video frame beside the slide images (null sequence_index).
    await transport.putFrame(key, { bytes: r.bytes, contentType: 'video/mp4', width: r.width, height: r.height, durationSeconds: r.durationSeconds, hasAudio: r.hasAudio });
    await transport.putPoster(key, r.posterBytes);
    log(`ok   ${key}  video/mp4  ${r.width}x${r.height}  ${r.durationSeconds}s  audio=${r.hasAudio}`);
  } else {
    const placements = spec.placements || [];
    if (!placements.length) throw new Error('the render spec names no placements to render');
    for (const placement of placements) {
      const r = await renderClipAudio({ spec, placement, ffmpegPath, log });
      await transport.putFrame(placement.key, { bytes: r.bytes, contentType: 'video/mp4', width: r.width, height: r.height, durationSeconds: r.durationSeconds, hasAudio: r.hasAudio });
      await transport.putPoster(placement.key, r.posterBytes);
      log(`ok   ${placement.key}  video/mp4  ${r.width}x${r.height}  ${r.durationSeconds}s  audio=${r.hasAudio}`);
    }
  }
  await transport.complete(warnings);
  log(`complete (${warnings.length} warning(s))`);
}

/* ── Draft preview: the full render's per-placement checks, without the frames ───────────────── */

/* A draft preview renders only the contact sheet (sheet.html), which never runs the assertions — a
 * bad frame still shows up in a sheet so a human can see what broke. The full render DOES run them
 * (spill, the bottom safe zone, contrast, fonts, images, the artboard size) on every placement's
 * frame.html and fails the render, so a design the person approved from the sheet could still fail
 * afterwards. This runs those very same checks (assertions.js's runAssertions, via frame.html) for
 * each placement the full render would produce, in the one already-open browser against the
 * already-served pages, and throws the screenshot away. A MOTION creative is checked the way its
 * full render checks it: once, on the end-card (final) frame — no video is encoded.
 *
 * Resolves `[{ placementKey, index?, rule, message, severity: 'error' | 'warning' }]`; a board that
 * would not even build (or a page error) is reported as rule "render". */
async function checkFrame({ browser, origin, spec, frame, fps, log }) {
  const label = frame.index !== undefined ? `${frame.placementKey}[${frame.index}]` : frame.placementKey;
  const tag = (check) => ({
    placementKey: frame.placementKey,
    ...(frame.index !== undefined ? { index: frame.index } : {}),
    rule: check.rule,
    message: check.message,
    severity: check.severity,
  });
  const isMotion = spec.creative && spec.creative.kind === 'MOTION' && spec.creative.motion;
  const page = await browser.newPage({ deviceScaleFactor: 1 });
  try {
    const motion = isMotion ? spec.creative.motion : undefined;
    const durationSec = (motion && motion.durationSec) || 8;
    await page.addInitScript((s) => {
      window.__RENDER_SPEC__ = s;
    }, {
      creative: spec.creative,
      brand: spec.brand,
      placementKey: frame.placementKey,
      sequenceIndex: frame.index,
      ...(isMotion ? { motion, time: 0, clipStartSec: spec.creative.clipStartSec } : {}),
    });
    await page.goto(`${origin}/frame.html`, { waitUntil: 'load', timeout: PAGE_TIMEOUT_MS });
    await page.waitForFunction(() => window.__ready === true, null, { timeout: PAGE_TIMEOUT_MS });

    if (isMotion) {
      const boot = await page.evaluate(() => window.__RENDER_RESULT);
      if (!boot || !boot.ok) {
        return [tag({ rule: 'render', severity: 'error', message: (boot && boot.error) || 'failed to build the MOTION board' })];
      }
      const frameCount = Math.max(1, Math.round(durationSec * fps));
      const endTime = (frameCount - 1) / fps;
      const seeked = await page.evaluate((t) => window.__seekMotion(t), endTime);
      if (!seeked) return [tag({ rule: 'render', severity: 'error', message: `motion seek failed at t=${endTime.toFixed(2)}s` })];
      const assertion = await page.evaluate(() => window.__assertBoard());
      return ((assertion && assertion.checks) || []).map(tag);
    }

    const result = await page.evaluate(() => window.__RENDER_RESULT);
    if (!result) return [tag({ rule: 'render', severity: 'error', message: 'render failed for an unknown reason' })];
    if (result.checks) return result.checks.map(tag);
    if (!result.ok) {
      return [tag({ rule: 'render', severity: 'error', message: result.error || (result.errors || []).join('; ') || 'render failed for an unknown reason' })];
    }
    return [];
  } catch (err) {
    log(`check ${label}: ${err.message}`);
    return [tag({ rule: 'render', severity: 'error', message: err.message })];
  } finally {
    await page.close().catch(() => {});
  }
}

/** Renders every frame a spec calls for, uploads each PNG (or, for a MOTION
 * creative that is not `previewOnly`, one MP4 + poster per placement — see
 * renderMotionPlacement above), and reports complete/fail. Options:
 *   transport        required. See transport.mjs.
 *   packageRoot       static root to serve (default: this package's own root).
 *   browserFactory    () => Promise<Browser> (default: launches real Playwright chromium).
 *   ffmpegPath        a local ffmpeg binary's path. Required for a MOTION
 *                     render (not previewOnly) and for every CAROUSEL/CLIP render (previews too) — ignored otherwise.
 *   fps               frames per second for a MOTION render (default 30).
 *   checkPlacements   true: when the spec is `previewOnly`, also run the full render's per-placement
 *                     assertions (see checkFrame above) and pass them to `transport.complete(warnings,
 *                     checks)`. They never fail the run; the caller decides what a failing check means.
 *   log               (...args) => void (default: console.log).
 * Resolves `true` on success, `false` on a reported failure (never throws —
 * a thrown error from a truly unexpected place still gets caught and turned
 * into a `transport.fail()` call before resolving `false`, so a caller never
 * has to guess whether `transport.fail` was already called). */
export async function run({ transport, packageRoot = PACKAGE_ROOT, browserFactory = defaultBrowserFactory, ffmpegPath, fps = DEFAULT_FPS, checkPlacements = false, log = console.log }) {
  let spec;
  try {
    log('fetching spec');
    spec = await transport.getSpec();
    if (spec && spec.creative) spec = { ...spec, creative: normalizeMotionCreative(spec.creative) };
  } catch (err) {
    log(`FAIL ${err.message}`);
    await reportFailure(transport, err.message, log);
    return false;
  }

  // CAROUSEL (slideshow) and CLIP (audio swap) are ffmpeg-only: no static server, no browser. A previewOnly
  // spec still produces the mp4 — the draft preview has to be able to watch it.
  if (spec && spec.creative && (spec.creative.kind === 'CAROUSEL' || spec.creative.kind === 'CLIP')) {
    try {
      await runLocalVideo({ spec, transport, ffmpegPath, log });
      return true;
    } catch (err) {
      log(`FAIL ${err.message}`);
      await reportFailure(transport, err.message, log);
      return false;
    }
  }

  const { server, origin } = await startServer(packageRoot);
  let browser;
  const warnings = [];
  try {
    browser = await browserFactory();
    const isMotionRender = spec.creative && spec.creative.kind === 'MOTION' && !spec.previewOnly;

    if (isMotionRender) {
      if (!ffmpegPath) {
        throw new Error(
          'a MOTION render needs a local ffmpeg binary — pass `ffmpegPath` to run() (e.g. a system '
          + '`ffmpeg` found via `which ffmpeg`, or a bundled ffmpeg-static build; conductor-tools '
          + 'resolves this the same way it resolves Chromium).'
        );
      }
      const placements = spec.placements || [];
      if (!placements.length) {
        throw new Error('the render spec names no placements to render (check the Brand Kit\'s enabled placements)');
      }
      log(`rendering ${placements.length} MOTION placement(s) at ${fps}fps`);
      for (const placementKey of placements) {
        const result = await renderMotionPlacement({ browser, origin, spec, placementKey, ffmpegPath, fps, log });
        for (const message of result.warnings) warnings.push({ placementKey, message });
        await transport.putFrame(placementKey, {
          bytes: result.bytes,
          contentType: 'video/mp4',
          width: result.width,
          height: result.height,
          durationSeconds: result.durationSeconds,
          hasAudio: result.hasAudio,
        });
        await transport.putPoster(placementKey, result.posterBytes);
        log(`ok   ${placementKey}  video/mp4  ${result.width}x${result.height}  ${result.durationSeconds}s  audio=${result.hasAudio}`);
      }
      await transport.complete(warnings);
      log(`complete (${warnings.length} warning(s))`);
      return true;
    }

    const frames = framesFor(spec);
    log(`rendering ${frames.length} frame(s)`);

    for (const frame of frames) {
      const label = frame.index !== undefined ? `${frame.placementKey}[${frame.index}]` : frame.placementKey;
      const isSheet = frame.page === 'sheet.html';
      const scale = isSheet ? SHEET_SCALE : FRAME_SCALE;
      const page = await browser.newPage({ deviceScaleFactor: scale });
      try {
        const isMotionSheet = isSheet && spec.creative && spec.creative.kind === 'MOTION' && spec.creative.motion;
        await page.addInitScript((s) => {
          window.__RENDER_SPEC__ = s;
        }, {
          creative: spec.creative,
          brand: spec.brand,
          placementKey: frame.placementKey,
          sequenceIndex: frame.index,
          placementKeys: spec.placements,
          ...(isMotionSheet ? { times: motionKeyTimes(spec.creative.motion) } : {}),
        });
        await page.goto(`${origin}/${frame.page}`, { waitUntil: 'load', timeout: PAGE_TIMEOUT_MS });
        await page.waitForFunction(() => window.__ready === true, null, { timeout: PAGE_TIMEOUT_MS });
        const result = await page.evaluate(() => window.__RENDER_RESULT);

        if (!result || !result.ok) {
          const message = (result && (result.error || (result.errors || []).join('; '))) || 'render failed for an unknown reason';
          throw new Error(`${label}: ${message}`);
        }
        for (const message of result.warnings || []) {
          warnings.push({ placementKey: frame.placementKey, index: frame.index, message });
        }

        const locator = isSheet ? page.locator('#sheet') : page.locator('.cc-board').first();
        const bytes = await locator.screenshot(isSheet ? SHEET_SCREENSHOT : PLACEMENT_FRAME_SCREENSHOT);
        const contentType = isSheet ? SHEET_CONTENT_TYPE : PLACEMENT_FRAME_CONTENT_TYPE;
        const box = await locator.boundingBox();
        const width = Math.round((box && box.width) || 0) * scale;
        const height = Math.round((box && box.height) || 0) * scale;

        await transport.putFrame(frame.placementKey, { index: frame.index, width, height, bytes, contentType });
        log(`ok   ${label}  ${width}x${height}  ${contentType}`);
      } finally {
        await page.close();
      }
    }

    if (checkPlacements && spec.previewOnly) {
      const checks = [];
      if ((spec.placements || []).length) {
        for (const frame of framesFor({ ...spec, previewOnly: false })) {
          checks.push(...(await checkFrame({ browser, origin, spec, frame, fps, log })));
        }
      }
      const failed = checks.filter((c) => c.severity === 'error').length;
      log(`checked placements: ${failed} failing, ${checks.length - failed} warning(s)`);
      await transport.complete(warnings, checks);
    } else {
      await transport.complete(warnings);
    }
    log(`complete (${warnings.length} warning(s))`);
    return true;
  } catch (err) {
    log(`FAIL ${err.message}`);
    await reportFailure(transport, err.message, log);
    return false;
  } finally {
    if (browser) await browser.close().catch(() => {});
    server.close();
  }
}

async function reportFailure(transport, message, log) {
  // A spec fetch the API refused never created a render, so there is nothing to mark failed.
  if (typeof transport.getRenderId === 'function' && !transport.getRenderId()) return;
  try {
    await transport.fail(message);
  } catch (err) {
    log(`also failed to report failure to the backend: ${err.message}`);
  }
}

/* ── CLI entry point ───────────────────────────────────────────────────── */

function isMain() {
  return process.argv[1] && import.meta.url === `file://${process.argv[1]}`;
}

if (isMain()) {
  const apiUrl = process.env.CONDUCTOR_API_URL;
  const apiKey = process.env.CONDUCTOR_API_KEY;
  const projectId = process.env.CONDUCTOR_PROJECT_ID;
  const creativeId = process.env.CREATIVE_ID;
  const previewOnly = /^(1|true)$/i.test(process.env.PREVIEW_ONLY || '');
  const renderer = process.env.RENDERER || 'cli';
  const workflowRunId = process.env.WORKFLOW_RUN_ID || undefined;
  // MOTION only; a STILL/CLIP render, or a previewOnly one, never touches ffmpeg.
  const ffmpegPath = process.env.FFMPEG_PATH || undefined;
  const fps = process.env.FPS ? Number(process.env.FPS) : undefined;
  const missing = ['CONDUCTOR_API_URL', 'CONDUCTOR_API_KEY', 'CONDUCTOR_PROJECT_ID', 'CREATIVE_ID'].filter((k) => !process.env[k]);
  if (missing.length) {
    console.error(`missing required env var(s): ${missing.join(', ')}`);
    process.exit(1);
  }

  const transport = createApiTransport({ apiUrl, apiKey, projectId, creativeId, previewOnly, renderer, workflowRunId });
  const log = (...args) => console.log(`[render ${creativeId}]`, ...args);
  run({ transport, log, ffmpegPath, ...(fps ? { fps } : {}) }).then((ok) => process.exit(ok ? 0 : 1));
}
