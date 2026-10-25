/* Pure argv-builder tests for job/media.mjs: no ffmpeg needed, always run. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildAudioFilter, buildClipAudioArgs, buildClipAudioMeasureArgs, buildFfmpegArgs, buildSlideshowArgs,
  buildSlideshowMeasureArgs, loudnormTarget, parseLoudnormJson, resolveSlideDurations, slideshowDuration,
  slideshowOffsets, AUDIO_TERMINATOR,
} from '../job/media.mjs';

const MEASURED = { input_i: '-20.10', input_tp: '-3.00', input_lra: '4.00', input_thresh: '-30.50', target_offset: '0.20' };
const slides = (n) => Array.from({ length: n }, (_, i) => `/tmp/s${i}.jpg`);
const filterGraph = (args) => args[args.indexOf('-filter_complex') + 1];

test('slideshow offsets: O_k = sum(d_j, j<k) - 0.5k, defaults and custom durations', () => {
  const d4 = resolveSlideDurations(undefined, 4);
  assert.deepEqual(d4, [3, 2.5, 2.5, 2.5]);
  assert.deepEqual(slideshowOffsets(d4), [2.5, 4.5, 6.5]);
  assert.equal(slideshowDuration(d4), 9);
  assert.deepEqual(slideshowOffsets([4, 1, 10]), [3.5, 4.0]);
  assert.equal(slideshowDuration([4, 1, 10]), 14);
  assert.deepEqual(slideshowOffsets([5]), []);
  assert.equal(slideshowDuration([5]), 5);
  // partial custom durations: the rest fall back to 2.5
  assert.deepEqual(resolveSlideDurations([6], 3), [6, 2.5, 2.5]);
});

test('slideshow args: xfade chain carries those offsets and the total is -t T', () => {
  const args = buildSlideshowArgs({ slideDurations: [3, 2.5, 2.5, 2.5] }, { slidePaths: slides(4), outPath: '/tmp/o.mp4' }, null);
  const g = filterGraph(args);
  assert.match(g, /\[s0\]\[s1\]xfade=transition=fade:duration=0\.5:offset=2\.5\[x1\]/);
  assert.match(g, /\[x1\]\[s2\]xfade=transition=fade:duration=0\.5:offset=4\.5\[x2\]/);
  assert.match(g, /\[x2\]\[s3\]xfade=transition=fade:duration=0\.5:offset=6\.5\[vout\]/);
  assert.equal(args[args.indexOf('-t') + 1], '9');
  assert.ok(!/fps=30\[/.test(g.split('xfade').slice(-1)[0]), 'no fps filter after the xfade chain');
});

test('slideshow args: zoom uses single-frame inputs and zoompan on a 4x canvas; static loops each image', () => {
  const zoom = buildSlideshowArgs({ slideDurations: [2, 2] }, { slidePaths: slides(2), outPath: 'o.mp4' }, null);
  assert.ok(!zoom.includes('-loop'));
  const g = filterGraph(zoom);
  assert.match(g, /scale=4320:7680:force_original_aspect_ratio=increase:out_range=tv,crop=4320:7680,setsar=1,format=yuv420p,zoompan=z='1\+0\.06\*on\/\(60-1\)'/);
  assert.match(g, /d=60:s=1080x1920:fps=30,settb=1\/30/);
  assert.ok(!g.includes('yuv444p'));

  const stat = buildSlideshowArgs({ slideDurations: [2, 3], zoom: false }, { slidePaths: slides(2), outPath: 'o.mp4' }, null);
  assert.deepEqual(stat.slice(0, 10), ['-loop', '1', '-framerate', '30', '-t', '2', '-i', '/tmp/s0.jpg', '-loop', '1']);
  const sg = filterGraph(stat);
  assert.ok(!sg.includes('zoompan'));
  assert.match(sg, /scale=1080:1920:force_original_aspect_ratio=increase:out_range=tv,crop=1080:1920,setsar=1,format=yuv420p,settb=1\/30/);
});

test('slideshow args: track input loops, audio chain is trimmed, faded, loudnorm linear and terminated', () => {
  const args = buildSlideshowArgs(
    { slideDurations: [3, 2.5], audio: { volume: 1, fadeOutSec: 1 } },
    { slidePaths: slides(2), audioPath: '/tmp/m.wav', outPath: '/tmp/o.mp4' }, MEASURED);
  const i = args.indexOf('-stream_loop');
  assert.deepEqual(args.slice(i, i + 4), ['-stream_loop', '-1', '-i', '/tmp/m.wav']);
  const g = filterGraph(args);
  assert.match(g, /\[2:a\]atrim=0:5,asetpts=PTS-STARTPTS,aresample=48000,afade=t=in:st=0:d=0\.5,afade=t=out:st=4:d=1,loudnorm=I=-14:TP=-2:LRA=11:measured_I=-20\.10:/);
  assert.ok(g.includes('linear=true'));
  assert.ok(g.endsWith(`${AUDIO_TERMINATOR}[a]`));
  assert.ok(args.includes('+faststart'));
  assert.deepEqual(args.slice(args.indexOf('-c:a'), args.indexOf('-c:a') + 6), ['-c:a', 'aac', '-b:a', '128k', '-ar', '48000']);
});

test('slideshow measure args: pass 1 prints json and has no terminator', () => {
  const args = buildSlideshowMeasureArgs({ slideDurations: [3, 2.5], audio: { volume: 1, fadeOutSec: 1 } }, { slidePaths: slides(2), audioPath: 'm.wav' });
  const af = args[args.indexOf('-af') + 1];
  assert.match(af, /loudnorm=I=-14:TP=-2:LRA=11:print_format=json$/);
  assert.deepEqual(args.slice(-3), ['-f', 'null', '-']);
});

test('loudnorm target folds volume: I = -14 + 20*log10(v)', () => {
  assert.equal(loudnormTarget(-14, 1), -14);
  assert.equal(loudnormTarget(-14, 0.5), -20.02);
  assert.equal(loudnormTarget(-14, 0.8), -15.94);
  assert.equal(loudnormTarget(undefined, undefined), -14);
  assert.equal(loudnormTarget(-14, 100), -5, 'clamped to loudnorm range');
  const f = buildAudioFilter({ durationSec: 5, volume: 0.5, targetLufs: -14, measured: MEASURED });
  assert.ok(f.includes('loudnorm=I=-20.02:'));
  assert.ok(!f.includes('volume='), 'no volume filter after folding');
  assert.ok(f.endsWith(AUDIO_TERMINATOR));
});

test('MOTION args are unchanged: no loudnorm, no terminator, same volume/afade/aac flags', () => {
  const none = buildFfmpegArgs({ fps: 30, durationSec: 3, outPath: 'o.mp4' });
  assert.deepEqual(none, ['-f', 'image2pipe', '-framerate', '30', '-i', '-', '-map', '0:v:0',
    '-vf', 'scale=in_range=pc:out_range=tv,format=yuv420p', '-color_range', 'tv',
    '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-crf', '20', '-preset', 'veryfast', '-r', '30',
    '-movflags', '+faststart', '-y', 'o.mp4']);
  const track = buildFfmpegArgs({ fps: 10, durationSec: 2, audioPath: 'a', audioSource: 'track', volume: 0.5, fadeOutSec: 0.5, outPath: 'o.mp4' });
  assert.deepEqual(track.slice(track.indexOf('-af')), ['-af', 'volume=0.5,afade=t=out:st=1.5:d=0.5', '-c:a', 'aac', '-b:a', '128k', '-t', '2', '-movflags', '+faststart', '-y', 'o.mp4']);
  const defaults = buildFfmpegArgs({ fps: 10, durationSec: 4, audioPath: 'a', audioSource: 'track', outPath: 'o.mp4' });
  assert.equal(defaults[defaults.indexOf('-af') + 1], 'volume=0.8,afade=t=out:st=3:d=1');
  const clip = buildFfmpegArgs({ fps: 10, durationSec: 2, audioPath: 'a', audioSource: 'clip', clipStartSec: 1.5, outPath: 'o.mp4' });
  assert.ok(!clip.includes('-af'));
  assert.deepEqual(clip.slice(clip.indexOf('-ss'), clip.indexOf('-ss') + 5), ['-ss', '1.5', '-t', '2', '-i']);
});

test('clip audio args: track replaces, mix ducks, none strips; video copied unless the clip starts mid-file', () => {
  const p = { clipPath: 'c.mp4', trackPath: 't.wav', outPath: 'o.mp4', durationSec: 4 };
  const track = buildClipAudioArgs({ audio: { source: 'track', volume: 1, fadeOutSec: 1 } }, p, MEASURED);
  assert.deepEqual(track.slice(0, 4), ['-t', '4', '-i', 'c.mp4']);
  assert.ok(track.join(' ').includes('-map 0:v:0 -map [a] -c:v copy'));
  assert.match(filterGraph(track), /^\[1:a\]atrim=0:4,.*loudnorm=I=-14:/);

  const mix = buildClipAudioArgs({ audio: { source: 'mix', volume: 0.3, fadeOutSec: 1 } }, p, MEASURED);
  const g = filterGraph(mix);
  assert.match(g, /\[0:a\]aformat=.*asplit=2\[vo\]\[sc\]/);
  assert.match(g, /volume=0\.3\[mu\]/);
  assert.match(g, /\[mu\]\[sc\]sidechaincompress=threshold=0\.05:ratio=4:attack=20:release=400\[duck\]/);
  assert.match(g, /\[vo\]\[duck\]amix=inputs=2:duration=first:normalize=0\[mix\]/);
  assert.match(g, /loudnorm=I=-14:/, 'mix volume is the music level, the blend lands on the target');
  assert.ok(g.endsWith(`${AUDIO_TERMINATOR}[a]`));

  const none = buildClipAudioArgs({ audio: { source: 'none' } }, p, null);
  assert.ok(none.includes('-an') && none.includes('copy') && !none.includes('-filter_complex') && !none.includes('t.wav'));

  const cut = buildClipAudioArgs({ audio: { source: 'track' } }, { ...p, clipStartSec: 1.5 }, MEASURED);
  assert.deepEqual(cut.slice(0, 2), ['-ss', '1.5']);
  assert.ok(cut.includes('libx264') && !cut.includes('copy'));

  assert.throws(() => buildClipAudioArgs({ audio: { source: 'track' } }, p, null), /measurements/);
  const meas = buildClipAudioMeasureArgs({ audio: { source: 'mix', volume: 0.3 } }, p);
  assert.match(filterGraph(meas), /print_format=json\[a\]$/);
});

test('parseLoudnormJson pulls the block out of ffmpeg stderr and rejects silence', () => {
  const stderr = 'noise\n[Parsed_loudnorm_0 @ 0x1] \n{\n\t"input_i" : "-22.31",\n\t"input_tp" : "-4.00",\n\t"input_lra" : "3.10",\n\t"input_thresh" : "-32.8",\n\t"target_offset" : "0.5"\n}\n';
  assert.equal(parseLoudnormJson(stderr).input_i, '-22.31');
  assert.throws(() => parseLoudnormJson('no json here'), /no measurement/);
  assert.throws(() => parseLoudnormJson('{ "input_i" : "-inf" }'), /silent/);
});
