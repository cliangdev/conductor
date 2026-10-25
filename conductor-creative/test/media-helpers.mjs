/* Shared fixtures for the ffmpeg-backed job tests (slideshow, clip audio, capability check).
 * CREATIVE_TEST_FFMPEG picks the ffmpeg binary (e.g. conductor-tools' ffmpeg-static 6.0); default is `ffmpeg` on PATH.
 * ffprobe always comes from PATH. */
import { execFile, execFileSync } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { promisify } from 'node:util';

const execFileAsync = promisify(execFile);

export const FFMPEG = process.env.CREATIVE_TEST_FFMPEG || 'ffmpeg';

export function ffmpegAvailable() {
  try {
    execFileSync(FFMPEG, ['-version'], { stdio: 'ignore' });
    execFileSync('ffprobe', ['-version'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
}

export function skipUnlessFfmpeg(t) {
  if (ffmpegAvailable()) return true;
  t.skip('no local ffmpeg/ffprobe');
  return false;
}

export function serveBytes(files) {
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      const f = files[req.url];
      if (!f) { res.writeHead(404); res.end(); return; }
      res.writeHead(200, { 'content-type': f.type, 'content-length': f.bytes.length });
      res.end(f.bytes);
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, origin: `http://127.0.0.1:${server.address().port}` }));
  });
}

function withTmp(fn) {
  const dir = mkdtempSync(join(tmpdir(), 'cc-media-test-'));
  try {
    return fn(dir);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

export function genSlide(size) {
  return withTmp((dir) => {
    const path = join(dir, 'slide.jpg');
    execFileSync(FFMPEG, ['-y', '-f', 'lavfi', '-i', `testsrc2=size=${size}:rate=1`, '-frames:v', '1', '-update', '1', path], { stdio: 'ignore' });
    return readFileSync(path);
  });
}

/** Pink noise + a 220 Hz sine: a music-like signal with a moderate crest factor (not a click track). */
export function genMusic(seconds) {
  return withTmp((dir) => {
    const path = join(dir, 'music.wav');
    execFileSync(FFMPEG, [
      '-y', '-f', 'lavfi', '-i', `anoisesrc=color=pink:amplitude=0.3:duration=${seconds}:sample_rate=44100`,
      '-f', 'lavfi', '-i', `sine=frequency=220:duration=${seconds}:sample_rate=44100`,
      '-filter_complex', 'amix=inputs=2:normalize=0,aformat=channel_layouts=stereo', path,
    ], { stdio: 'ignore' });
    return readFileSync(path);
  });
}

export function genClip({ seconds = 4, withAudio = false, tone = 1000, size = '320x568' } = {}) {
  return withTmp((dir) => {
    const path = join(dir, 'clip.mp4');
    execFileSync(FFMPEG, [
      '-y', '-f', 'lavfi', '-i', `testsrc=size=${size}:rate=30`,
      ...(withAudio ? ['-f', 'lavfi', '-i', `sine=frequency=${tone}:sample_rate=44100`] : []),
      '-t', String(seconds), '-pix_fmt', 'yuv420p', '-c:v', 'libx264', ...(withAudio ? ['-c:a', 'aac'] : []), path,
    ], { stdio: 'ignore' });
    return readFileSync(path);
  });
}

export async function ffprobe(bytes) {
  const dir = mkdtempSync(join(tmpdir(), 'cc-media-probe-'));
  const path = join(dir, 'probe.mp4');
  writeFileSync(path, bytes);
  try {
    const { stdout } = await execFileAsync('ffprobe', ['-v', 'error', '-print_format', 'json', '-show_format', '-show_streams', path]);
    return JSON.parse(stdout);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

async function filterStderr(bytes, filter) {
  const dir = mkdtempSync(join(tmpdir(), 'cc-media-meas-'));
  const path = join(dir, 'in.mp4');
  writeFileSync(path, bytes);
  try {
    const { stderr } = await execFileAsync(FFMPEG, ['-hide_banner', '-nostats', '-i', path, '-vn', '-af', filter, '-f', 'null', '-'], { maxBuffer: 64 * 1024 * 1024 });
    return stderr;
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
}

/** Integrated loudness (LUFS) from ebur128's summary block. */
export async function measureLufs(bytes) {
  const err = await filterStderr(bytes, 'ebur128');
  const summary = err.slice(err.lastIndexOf('Summary:'));
  return Number(/I:\s+(-?[\d.]+) LUFS/.exec(summary)[1]);
}

/** Overall RMS level (dBFS) of the audio inside a narrow band around `hz`. */
export async function bandRmsDb(bytes, hz) {
  const err = await filterStderr(bytes, `bandpass=f=${hz}:width_type=h:w=60,astats=metadata=0`);
  const all = [...err.matchAll(/RMS level dB:\s+(-?[\d.]+|-inf)/g)];
  return Number(all[all.length - 1][1]);
}

export function fakeTransport(spec) {
  const calls = { putFrame: [], putPoster: [], complete: null, fail: null };
  return {
    calls,
    spec,
    async getSpec() { return this.spec; },
    async putFrame(placementKey, opts) { calls.putFrame.push({ placementKey, ...opts }); },
    async putPoster(placementKey, bytes, opts) { calls.putPoster.push({ placementKey, bytes, ...(opts || {}) }); },
    async complete(warnings) { calls.complete = warnings; },
    async fail(message, log) { calls.fail = { message, log }; },
  };
}
