/* media.mjs — the ffmpeg-only half of the render job: argument builders (pure, unit-tested without
 * ffmpeg) and the runners that shell out to them.
 *
 *   - buildAudioFilter           one audio filter chain; shared by MOTION (normalize:false, its
 *                                historical volume + fade-out, byte-for-byte) and the loudness-
 *                                normalised paths below.
 *   - buildSlideshowArgs         CAROUSEL: N slides + a track -> one 1080x1920 H.264/AAC mp4.
 *   - buildClipAudioArgs         CLIP: replace (`track`), duck-mix (`mix`) or strip (`none`) a clip's sound.
 *   - buildSlideshowMeasureArgs / buildClipAudioMeasureArgs   loudnorm pass 1 for the two above.
 *   - parseLoudnormJson / runLoudnormTwoPass                  the two-pass driver.
 *
 * Nothing here knows about Playwright or a transport; render.mjs wires it in. Recipes were validated
 * on ffmpeg-static 6.0 (see the creative-music contract): loudnorm in two passes with `linear=true`,
 * the target I folding the volume (I = targetLufs + 20*log10(volume)), fades BEFORE loudnorm, and an
 * `aresample + aformat` terminator on every audio chain (ffmpeg 6.0's channel-layout bug).
 */
import { spawn } from 'node:child_process';

export const SLIDE_W = 1080;
export const SLIDE_H = 1920;
export const SLIDE_FPS = 30;
export const CROSSFADE_SEC = 0.5;
export const DEFAULT_TARGET_LUFS = -14;
export const TRUE_PEAK_DB = -2;
export const LOUDNORM_LRA = 11;
const FADE_IN_SEC = 0.5;
export const AUDIO_TERMINATOR = 'aresample=48000,aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo';

/* A number rendered compactly and deterministically inside a filter expression (no 1e-7, no trailing zeros). */
function num(x) {
  return String(Math.round(x * 1000) / 1000);
}

/** Target integrated loudness with the volume folded in: I = targetLufs + 20*log10(volume), clamped to loudnorm's range. */
export function loudnormTarget(targetLufs, volume) {
  const target = targetLufs != null ? targetLufs : DEFAULT_TARGET_LUFS;
  const v = volume != null ? Math.max(volume, 0.01) : 1;
  const i = target + 20 * Math.log10(v);
  return Math.min(-5, Math.max(-70, Math.round(i * 100) / 100));
}

function loudnormFilter(i, measured) {
  const base = `loudnorm=I=${num(i)}:TP=${TRUE_PEAK_DB}:LRA=${LOUDNORM_LRA}`;
  if (!measured) return `${base}:print_format=json`;
  return `${base}:measured_I=${measured.input_i}:measured_LRA=${measured.input_lra}:measured_TP=${measured.input_tp}`
    + `:measured_thresh=${measured.input_thresh}:offset=${measured.target_offset}:linear=true:print_format=json`;
}

/**
 * One audio filter chain (comma-joined, no pad labels).
 *   normalize:false  MOTION's historical chain: `volume=v[,afade=t=out]` — no trim, no loudnorm, no terminator.
 *   normalize:true   atrim -> asetpts -> aresample -> [afade in] -> [afade out] -> loudnorm -> terminator.
 *                    `measured` null/undefined builds the pass-1 (measure only) chain, which has no terminator.
 * `volume` is folded into the loudnorm target for a normalised track (pass `foldVolume:false` to leave the
 * target at `targetLufs`, used by `mix` where volume is the music level under the voice and is applied elsewhere).
 */
export function buildAudioFilter({
  durationSec, volume, fadeInSec = 0, fadeOutSec, normalize = true, targetLufs, measured = null, foldVolume = true,
}) {
  if (!normalize) {
    const filters = [`volume=${volume != null ? volume : 0.8}`];
    const fadeOut = fadeOutSec != null ? fadeOutSec : 1;
    if (fadeOut > 0) filters.push(`afade=t=out:st=${Math.max(0, durationSec - fadeOut)}:d=${fadeOut}`);
    return filters.join(',');
  }
  const filters = [`atrim=0:${num(durationSec)}`, 'asetpts=PTS-STARTPTS', 'aresample=48000'];
  if (fadeInSec > 0) filters.push(`afade=t=in:st=0:d=${num(fadeInSec)}`);
  if (fadeOutSec > 0) filters.push(`afade=t=out:st=${num(Math.max(0, durationSec - fadeOutSec))}:d=${num(fadeOutSec)}`);
  filters.push(loudnormFilter(loudnormTarget(targetLufs, foldVolume ? volume : 1), measured));
  if (measured) filters.push(AUDIO_TERMINATOR);
  return filters.join(',');
}

/* ── MOTION ───────────────────────────────────────────────────────────────── */

/* ffmpeg argv for one MOTION placement (unchanged behaviour; exported for tests). */
export function buildFfmpegArgs({ fps, durationSec, audioPath, audioSource, clipStartSec, volume, fadeOutSec, outPath }) {
  const args = ['-f', 'image2pipe', '-framerate', String(fps), '-i', '-'];
  if (audioPath) {
    if (audioSource === 'clip') {
      args.push('-ss', String(clipStartSec || 0), '-t', String(durationSec), '-i', audioPath);
    } else {
      args.push('-t', String(durationSec), '-i', audioPath);
    }
  }
  // The piped JPEGs are full-range (yuvj420p); platforms expect limited-range yuv420p, so convert the range
  // explicitly — `-pix_fmt yuv420p` alone keeps the full-range flag and ffprobe still reports yuvj420p.
  // Map streams explicitly. A clip used for its sound is an MP4 with its own video stream, and ffmpeg's
  // default picks the highest-resolution video among the inputs — for a 4:5 or 1:1 frame that is the
  // clip's 9:16 picture, so the output was the raw clip, not the rendered creative.
  args.push('-map', '0:v:0');
  if (audioPath) args.push('-map', '1:a:0?');
  args.push('-vf', 'scale=in_range=pc:out_range=tv,format=yuv420p', '-color_range', 'tv');
  args.push('-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-preset', 'veryfast', '-r', String(fps));
  if (audioPath) {
    // A clip's own sound plays as recorded — untouched. A library track is a music bed layered under
    // the creative, so only IT gets volume control and a fade-out.
    if (audioSource === 'track') {
      args.push('-af', buildAudioFilter({ durationSec, volume, fadeOutSec, normalize: false }));
    }
    // Bound the output by the video's own length, not `-shortest`: the audio file reads instantly while
    // frames trickle in through the pipe, and `-shortest` could end the file as soon as audio hit EOF.
    args.push('-c:a', 'aac', '-b:a', '128k', '-t', String(durationSec));
  }
  args.push('-movflags', '+faststart', '-y', outPath);
  return args;
}

/* The URL ffmpeg should read audio from for a MOTION creative: 'clip' reuses the clip already playing as the
 * video background; 'track' is a separate library file. `null` for 'none' or no audio. */
export function audioSourceUrl(creative) {
  const audio = creative.audio;
  if (!audio || audio.source === 'none') return null;
  if (audio.source === 'clip') return creative.backgroundVideoUrl || null;
  if (audio.source === 'track') return audio.trackUrl || null;
  return null;
}

/* ── Slideshow (CAROUSEL) ─────────────────────────────────────────────────── */

const COLOR_TAGS = ['-color_range', 'tv', '-colorspace', 'bt709', '-color_primaries', 'bt709', '-color_trc', 'bt709'];
const VIDEO_ENCODE = ['-c:v', 'libx264', '-preset', 'veryfast', '-crf', '20', '-pix_fmt', 'yuv420p'];
const AUDIO_ENCODE = ['-c:a', 'aac', '-b:a', '128k', '-ar', '48000', '-ac', '2'];

/** Per-slide durations resolved against the slide count: given values win; defaults are 3.0 s first, 2.5 s after. */
export function resolveSlideDurations(slideDurations, count) {
  return Array.from({ length: count }, (_, i) => {
    const d = slideDurations && slideDurations[i];
    return d != null ? d : (i === 0 ? 3.0 : 2.5);
  });
}

/** xfade offsets, one per transition: O_k = sum_{j<k} d_j - crossfade*k for k = 1..n-1. */
export function slideshowOffsets(durations, crossfadeSec = CROSSFADE_SEC) {
  const offsets = [];
  let acc = 0;
  for (let k = 1; k < durations.length; k += 1) {
    acc += durations[k - 1];
    offsets.push(Math.round((acc - crossfadeSec * k) * 1000) / 1000);
  }
  return offsets;
}

/** Total length: sum(d) - crossfade*(n-1). */
export function slideshowDuration(durations, crossfadeSec = CROSSFADE_SEC) {
  const sum = durations.reduce((a, b) => a + b, 0);
  return Math.round((sum - crossfadeSec * (durations.length - 1)) * 1000) / 1000;
}

function slideChain(zoom, d) {
  if (zoom) {
    const frames = Math.round(d * SLIDE_FPS);
    return `scale=4320:7680:force_original_aspect_ratio=increase:out_range=tv,crop=4320:7680,setsar=1,format=yuv420p,`
      + `zoompan=z='1+0.06*on/(${frames}-1)':x='iw/2-iw/zoom/2':y='ih/2-ih/zoom/2':d=${frames}:s=${SLIDE_W}x${SLIDE_H}:fps=${SLIDE_FPS},settb=1/${SLIDE_FPS}`;
  }
  return `scale=${SLIDE_W}:${SLIDE_H}:force_original_aspect_ratio=increase:out_range=tv,crop=${SLIDE_W}:${SLIDE_H},setsar=1,format=yuv420p,settb=1/${SLIDE_FPS}`;
}

function slideshowAudio(spec, durationSec) {
  const a = spec.audio || {};
  return {
    durationSec,
    volume: a.volume != null ? a.volume : 1.0,
    fadeInSec: a.fadeInSec != null ? a.fadeInSec : FADE_IN_SEC,
    fadeOutSec: a.fadeOutSec != null ? a.fadeOutSec : 1,
    targetLufs: a.targetLufs,
  };
}

/**
 * Pass-1 (measure only) argv for a slideshow's track: `-f null -`, no video.
 *   spec   { slideDurations, audio }     inputs { audioPath }
 */
export function buildSlideshowMeasureArgs(spec, inputs) {
  const durations = resolveSlideDurations(spec.slideDurations, inputs.slidePaths ? inputs.slidePaths.length : spec.slideDurations.length);
  const T = slideshowDuration(durations);
  return ['-stream_loop', '-1', '-i', inputs.audioPath,
    '-af', buildAudioFilter({ ...slideshowAudio(spec, T), normalize: true, measured: null }), '-t', num(T), '-f', 'null', '-'];
}

/**
 * Full slideshow encode argv.
 *   spec      { slideDurations?, zoom?, audio?: { volume, fadeInSec, fadeOutSec, targetLufs } }   (audio absent -> silent video)
 *   inputs    { slidePaths: string[], audioPath?: string, outPath: string }
 *   measured  loudnorm pass-1 JSON (required when inputs.audioPath is set)
 */
export function buildSlideshowArgs(spec, inputs, measured) {
  const { slidePaths, audioPath, outPath } = inputs;
  const n = slidePaths.length;
  if (!n) throw new Error('a slideshow needs at least one slide');
  const zoom = spec.zoom !== false;
  const durations = resolveSlideDurations(spec.slideDurations, n);
  const T = slideshowDuration(durations);
  const args = [];
  slidePaths.forEach((p, i) => {
    if (zoom) args.push('-i', p); // one frame in, zoompan makes the d seconds (no -loop: it would multiply frames)
    else args.push('-loop', '1', '-framerate', String(SLIDE_FPS), '-t', num(durations[i]), '-i', p);
  });
  if (audioPath) args.push('-stream_loop', '-1', '-i', audioPath);

  const graph = slidePaths.map((_, i) => `[${i}:v]${slideChain(zoom, durations[i])}[s${i}]`);
  if (n === 1) {
    graph.push('[s0]null[vout]');
  } else {
    const offsets = slideshowOffsets(durations);
    let prev = 's0';
    for (let k = 1; k < n; k += 1) {
      const out = k === n - 1 ? 'vout' : `x${k}`;
      graph.push(`[${prev}][s${k}]xfade=transition=fade:duration=${CROSSFADE_SEC}:offset=${offsets[k - 1]}[${out}]`);
      prev = out;
    }
  }
  if (audioPath) {
    if (!measured) throw new Error('buildSlideshowArgs: loudnorm measurements are required when there is a track');
    graph.push(`[${n}:a]${buildAudioFilter({ ...slideshowAudio(spec, T), normalize: true, measured })}[a]`);
  }
  args.push('-filter_complex', graph.join(';'), '-map', '[vout]');
  if (audioPath) args.push('-map', '[a]');
  args.push(...VIDEO_ENCODE, ...COLOR_TAGS);
  if (audioPath) args.push(...AUDIO_ENCODE);
  args.push('-t', num(T), '-movflags', '+faststart', '-y', outPath);
  return args;
}

/* ── CLIP audio ───────────────────────────────────────────────────────────── */

function requireDuration(placement) {
  const d = placement.durationSec;
  if (!Number.isFinite(d) || d <= 0) throw new Error(`CLIP audio: durationSec must be a positive number, got ${d}`);
}

function clipInputs(placement) {
  requireDuration(placement);
  const args = [];
  if (placement.clipStartSec) args.push('-ss', num(placement.clipStartSec));
  args.push('-t', num(placement.durationSec), '-i', placement.clipPath);
  if (placement.trackPath) args.push('-stream_loop', '-1', '-i', placement.trackPath);
  return args;
}

function clipGraph(source, audio, placement, measured) {
  const d = placement.durationSec;
  const a = audio || {};
  const common = { durationSec: d, fadeInSec: a.fadeInSec != null ? a.fadeInSec : FADE_IN_SEC, fadeOutSec: a.fadeOutSec != null ? a.fadeOutSec : 1, targetLufs: a.targetLufs, measured };
  if (source === 'track') {
    return `[1:a]${buildAudioFilter({ ...common, volume: a.volume != null ? a.volume : 1.0 })}[a]`;
  }
  // mix: the music (at `volume`, default 0.3) ducks under the clip's own sound; the blend is then normalised flat.
  const musicVol = a.volume != null ? a.volume : 0.3;
  const fmt = 'aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo';
  return [
    `[0:a]${fmt},asplit=2[vo][sc]`,
    `[1:a]atrim=0:${num(d)},asetpts=PTS-STARTPTS,aresample=48000,${fmt},volume=${musicVol}[mu]`,
    '[mu][sc]sidechaincompress=threshold=0.05:ratio=4:attack=20:release=400[duck]',
    '[vo][duck]amix=inputs=2:duration=first:normalize=0[mix]',
    `[mix]${buildAudioFilter({ ...common, volume: 1, foldVolume: false, normalize: true })}[a]`,
  ].join(';');
}

/** Pass-1 argv for a CLIP's `track`/`mix` audio. placement: { clipPath, trackPath, clipStartSec?, durationSec }. */
export function buildClipAudioMeasureArgs(spec, placement) {
  const audio = spec.audio || {};
  return [...clipInputs(placement), '-filter_complex', clipGraph(audio.source, audio, placement, null), '-map', '[a]', '-f', 'null', '-'];
}

/**
 * Full argv for one CLIP placement.
 *   spec       { audio: { source: 'track'|'mix'|'none', volume, fadeInSec, fadeOutSec, targetLufs } }
 *   placement  { clipPath, trackPath?, outPath, clipStartSec?, durationSec }
 *   measured   loudnorm pass-1 JSON (track/mix only)
 * The video is stream-copied unless the clip must start mid-file (then it is re-encoded with the slideshow's settings).
 */
export function buildClipAudioArgs(spec, placement, measured) {
  const audio = spec.audio || {};
  const source = audio.source;
  const reencode = Boolean(placement.clipStartSec && placement.clipStartSec > 0.01);
  const videoCodec = reencode ? [...VIDEO_ENCODE, ...COLOR_TAGS] : ['-c:v', 'copy'];
  const args = clipInputs(source === 'none' ? { ...placement, trackPath: null } : placement);
  if (source === 'none') {
    return [...args, '-map', '0:v:0', ...videoCodec, '-an', '-t', num(placement.durationSec), '-movflags', '+faststart', '-y', placement.outPath];
  }
  if (source !== 'track' && source !== 'mix') throw new Error(`buildClipAudioArgs: unsupported audio source "${source}"`);
  if (!measured) throw new Error('buildClipAudioArgs: loudnorm measurements are required for track/mix');
  return [...args, '-filter_complex', clipGraph(source, audio, placement, measured), '-map', '0:v:0', '-map', '[a]',
    ...videoCodec, ...AUDIO_ENCODE, '-t', num(placement.durationSec), '-movflags', '+faststart', '-y', placement.outPath];
}

/* ── Running ffmpeg / loudnorm ────────────────────────────────────────────── */

/** `ffmpeg -i file` (no output exits non-zero by design): resolves { stderr, hasAudio, durationSec, width, height }. */
export function probeMedia(ffmpegPath, path) {
  return new Promise((resolve, reject) => {
    const child = spawn(ffmpegPath, ['-hide_banner', '-nostdin', '-i', path], { stdio: ['ignore', 'ignore', 'pipe'] });
    let err = '';
    child.stderr.on('data', (c) => { err += c.toString(); });
    child.on('error', reject);
    child.on('close', () => resolve(parseProbe(err)));
  });
}

export function parseProbe(stderr) {
  const d = /Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)/.exec(stderr);
  const durationSec = d ? Number(d[1]) * 3600 + Number(d[2]) * 60 + Number(d[3]) : null;
  // Line-by-line with indexOf rather than one regex over the whole of stderr: ffmpeg output is
  // untrusted input, and a `[^\n]*` scan across it backtracks polynomially (CodeQL js/polynomial-redos).
  let hasAudio = false;
  let width = null;
  let height = null;
  for (const line of stderr.split('\n')) {
    if (!line.includes('Stream #')) continue;
    if (line.includes('Audio:')) hasAudio = true;
    const at = line.indexOf('Video:');
    if (at === -1 || width !== null) continue;
    const size = videoSizeIn(line.slice(at + 'Video:'.length));
    if (size) ({ width, height } = size);
  }
  return { stderr, durationSec, hasAudio, width, height };
}

/** The first `WxH` token (2–5 digits each) in a stream description, e.g. "h264 (High), yuv420p, 1080x1920 [SAR 1:1]". */
function videoSizeIn(description) {
  for (const token of description.split(/[\s,]+/)) {
    const x = token.indexOf('x');
    if (x < 2 || x > 5) continue;
    const w = token.slice(0, x);
    const h = token.slice(x + 1);
    if (/^\d{2,5}$/.test(w) && /^\d{2,5}$/.test(h)) return { width: Number(w), height: Number(h) };
  }
  return null;
}

/** Runs ffmpeg to completion; resolves its stderr, rejects with the tail of stderr on a non-zero exit. */
export function runFfmpeg(ffmpegPath, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(ffmpegPath, ['-hide_banner', '-nostdin', ...args], { stdio: ['ignore', 'ignore', 'pipe'] });
    let err = '';
    child.stderr.on('data', (c) => { err += c.toString(); });
    child.on('error', reject);
    child.on('close', (code) => (code === 0 ? resolve(err) : reject(new Error(`ffmpeg exited ${code}: ${err.slice(-2000)}`))));
  });
}

/** Parses loudnorm's `print_format=json` block out of ffmpeg stderr. Throws when absent or the input is silent. */
export function parseLoudnormJson(stderr) {
  // The JSON block is the braces around the last "input_i" key; found with indexOf, not a regex
  // over untrusted ffmpeg output (CodeQL js/polynomial-redos).
  const key = stderr.lastIndexOf('"input_i"');
  const open = key === -1 ? -1 : stderr.lastIndexOf('{', key);
  const close = key === -1 ? -1 : stderr.indexOf('}', key);
  if (open === -1 || close === -1) throw new Error('loudnorm printed no measurement (is the audio file readable?)');
  const json = JSON.parse(stderr.slice(open, close + 1));
  if (!Number.isFinite(Number(json.input_i))) throw new Error('the audio track is silent, so it cannot be loudness-normalised');
  return json;
}

/**
 * Two-pass loudnorm. `measureArgs` is the pass-1 argv; `buildEncodeArgs(measured)` returns the pass-2 argv.
 * Logs the normalisation type loudnorm reports ("linear" is what we want; "dynamic" means it fell back).
 * Resolves the pass-1 measurement.
 */
export async function runLoudnormTwoPass({ ffmpegPath, measureArgs, buildEncodeArgs, log = () => {} }) {
  const measured = parseLoudnormJson(await runFfmpeg(ffmpegPath, measureArgs));
  log(`loudnorm pass 1: input ${measured.input_i} LUFS, TP ${measured.input_tp} dB`);
  const stderr = await runFfmpeg(ffmpegPath, buildEncodeArgs(measured));
  const type = /"normalization_type"\s*:\s*"(\w+)"/.exec(stderr);
  log(`loudnorm pass 2: normalization_type=${type ? type[1] : 'unknown'}`);
  return measured;
}
