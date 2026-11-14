/* Pure argv-builder tests for the compose_video part of job/media.mjs: no ffmpeg needed, always run. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  buildVideoComposeArgs, buildVideoComposeMeasureArgs, videoComposeHasAudio, trimmedClipChain, AUDIO_TERMINATOR,
} from '../job/media.mjs';

const MEASURED = { input_i: '-20.10', input_tp: '-3.00', input_lra: '4.00', input_thresh: '-30.50', target_offset: '0.20' };
const graph = (args) => args[args.indexOf('-filter_complex') + 1];
const seg = (over) => ({ kind: 'image', startSec: 0, durationSec: 3, zoom: false, useAudio: false, transitionIn: 'cut', ...over });
const plan = (segments, extra = {}) => ({ segments, texts: [], totalSec: 9.4, ...extra });
const inputsFor = (segments, extra = {}) => ({ segmentPaths: segments.map((_, i) => `/tmp/in${i}`), textPaths: [], outPath: '/tmp/out.mp4', ...extra });

test('clip chain trims by start and duration, fills the frame, holds the last frame and ends at exactly d', () => {
  const c = trimmedClipChain(0.5, 3.4);
  assert.match(c, /^trim=start=0\.5:duration=3\.4,setpts=PTS-STARTPTS,fps=30,/);
  assert.match(c, /scale=1080:1920:force_original_aspect_ratio=increase:out_range=tv,crop=1080:1920/);
  assert.match(c, /tpad=stop_mode=clone:stop_duration=1,trim=duration=3\.4,setpts=PTS-STARTPTS,settb=1\/30$/);
});

test('hard cuts join with concat (no fps filter after it); fades use xfade at the running offset', () => {
  const segments = [seg({ kind: 'clip', startSec: 0.4, durationSec: 3.4 }), seg({ durationSec: 3 }), seg({ durationSec: 3, transitionIn: 'fade' }), seg({ durationSec: 3 })];
  const g = graph(buildVideoComposeArgs(plan(segments, { totalSec: 12.4 - 0.5 }), inputsFor(segments), null));
  assert.match(g, /\[s0\]\[s1\]concat=n=2:v=1:a=0,settb=1\/30\[x1\]/);
  assert.match(g, /\[x1\]\[s2\]xfade=transition=fade:duration=0\.5:offset=5\.9\[x2\]/);
  assert.match(g, /\[x2\]\[s3\]concat=n=2:v=1:a=0,settb=1\/30\[x3\]/);
  assert.ok(!/concat=n=2:v=1:a=0,fps/.test(g), 'fps after concat drops a frame');
  assert.match(g, /\[x3\]format=yuv420p\[vout\]$/);
});

test('input forms: a clip is read plain, a zoomed still as one frame, a static still looped for its length', () => {
  const segments = [seg({ kind: 'clip', durationSec: 3.4 }), seg({ zoom: true }), seg({ durationSec: 2.5 })];
  const args = buildVideoComposeArgs(plan(segments, { totalSec: 8.9 }), inputsFor(segments), null);
  const head = args.slice(0, args.indexOf('-filter_complex')).join(' ');
  assert.equal(head, '-i /tmp/in0 -i /tmp/in1 -loop 1 -framerate 30 -t 2.5 -i /tmp/in2');
  assert.match(graph(args), /\[1:v\]scale=4320:7680[^;]*zoompan=z='1\+0\.06\*on\/\(90-1\)'/);
});

test('text beats are PNG inputs overlaid with an enable window, in order', () => {
  const segments = [seg({ durationSec: 4 }), seg({ durationSec: 5.4 })];
  const p = plan(segments, { texts: [{ fromSec: 0, toSec: 3.4 }, { fromSec: 3.4, toSec: 9.4 }] });
  const args = buildVideoComposeArgs(p, inputsFor(segments, { textPaths: ['/tmp/b1.png', '/tmp/b2.png'] }), null);
  assert.ok(args.join(' ').includes('-loop 1 -framerate 30 -t 9.4 -i /tmp/b1.png -loop 1 -framerate 30 -t 9.4 -i /tmp/b2.png'));
  const g = graph(args);
  assert.match(g, /\[2:v\]format=rgba,setpts=PTS-STARTPTS\[t0\]/);
  assert.match(g, /\[x1\]\[t0\]overlay=0:0:format=auto:enable='between\(t,0,3\.4\)'\[o0\]/);
  assert.match(g, /\[o0\]\[t1\]overlay=0:0:format=auto:enable='between\(t,3\.4,9\.4\)'\[o1\]/);
  assert.match(g, /\[o1\]format=yuv420p\[vout\]$/);
});

test('no sound anywhere: no audio map, -an, no measure pass', () => {
  const segments = [seg(), seg()];
  const p = plan(segments, { totalSec: 6 });
  assert.equal(videoComposeHasAudio(p, inputsFor(segments)), false);
  assert.equal(buildVideoComposeMeasureArgs(p, inputsFor(segments)), null);
  const args = buildVideoComposeArgs(p, inputsFor(segments), null);
  assert.ok(args.includes('-an'));
  assert.ok(!args.includes('[a]'));
  assert.deepEqual(args.slice(-6), ['-t', '6', '-movflags', '+faststart', '-y', '/tmp/out.mp4']);
});

test('music only: looped track, loudnorm in two passes, terminator only on pass 2', () => {
  const segments = [seg(), seg()];
  const p = plan(segments, { totalSec: 6 });
  const inputs = inputsFor(segments, { musicPath: '/tmp/m.wav' });
  const measure = buildVideoComposeMeasureArgs(p, inputs);
  assert.ok(measure.join(' ').includes('-stream_loop -1 -i /tmp/m.wav'));
  assert.deepEqual(measure.slice(-5), ['-t', '6', '-f', 'null', '-']);
  assert.ok(!graph(measure).includes(`print_format=json,${AUDIO_TERMINATOR}`));
  assert.match(graph(measure), /\[2:a\]atrim=0:6,asetpts=PTS-STARTPTS,aresample=48000,[^;]*volume=1\[mu\]/);
  assert.throws(() => buildVideoComposeArgs(p, inputs, null), /loudnorm measurements are required/);
  const enc = buildVideoComposeArgs(p, inputs, MEASURED);
  assert.ok(graph(enc).includes('measured_I=-20.10'));
  assert.ok(graph(enc).includes(`print_format=json,${AUDIO_TERMINATOR}`));
  assert.ok(enc.includes('[a]'));
  assert.ok(!graph(enc).includes('afade=t=in'), 'sound starts at full level on frame 0');
});

test('clip sound bed: trimmed per segment, silence for the rest, concat for cuts and acrossfade for fades', () => {
  const segments = [seg({ kind: 'clip', startSec: 0.4, durationSec: 3.4, useAudio: true }), seg({ durationSec: 3, transitionIn: 'fade' }), seg({ durationSec: 3 })];
  const p = plan(segments, { totalSec: 8.9 });
  const g = graph(buildVideoComposeMeasureArgs(p, inputsFor(segments)));
  assert.match(g, /\[0:a\]atrim=start=0\.4:duration=3\.4,asetpts=PTS-STARTPTS,aresample=48000,[^;]*apad=whole_dur=3\.4,atrim=0:3\.4,asetpts=PTS-STARTPTS\[ca0\]/);
  assert.match(g, /anullsrc=r=48000:cl=stereo,atrim=0:3,asetpts=PTS-STARTPTS\[ca1\]/);
  assert.match(g, /\[ca0\]\[ca1\]acrossfade=d=0\.5\[cb1\]/);
  assert.match(g, /\[cb1\]\[ca2\]concat=n=2:v=0:a=1\[cb2\]/);
});

test('music under clip sound and voice ducks by default and mixes flat when duck is false', () => {
  const segments = [seg({ kind: 'clip', durationSec: 3.4, useAudio: true }), seg()];
  const inputs = inputsFor(segments, { musicPath: '/tmp/m.wav', voicePath: '/tmp/v.wav' });
  const ducked = graph(buildVideoComposeMeasureArgs(plan(segments, { totalSec: 6.4 }), inputs));
  assert.match(ducked, /\[3:a\]aresample=48000,[^;]*\[vo\]/);
  assert.match(ducked, /\[bed\]\[vo\]amix=inputs=2:duration=longest:normalize=0\[dlg\]/);
  assert.match(ducked, /volume=0\.3\[mu\]/);
  assert.match(ducked, /\[mu\]\[sc\]sidechaincompress=threshold=0\.05:ratio=4:attack=20:release=400\[duck\]/);
  assert.match(ducked, /\[dl\]\[duck\]amix=inputs=2:duration=first:normalize=0\[mix\]/);
  const flat = graph(buildVideoComposeMeasureArgs(plan(segments, { totalSec: 6.4, audio: { duck: false } }), inputs));
  assert.ok(!flat.includes('sidechaincompress'));
  assert.match(flat, /\[dialog\]\[mu\]amix=inputs=2:duration=first:normalize=0\[mix\]/);
});

test('voice alone is padded to the length and normalised', () => {
  const segments = [seg(), seg()];
  const g = graph(buildVideoComposeMeasureArgs(plan(segments, { totalSec: 6 }), inputsFor(segments, { voicePath: '/tmp/v.wav' })));
  assert.match(g, /\[vo\]apad=whole_dur=6,atrim=0:6,asetpts=PTS-STARTPTS\[dialog\]/);
  assert.match(g, /\[dialog\]atrim=0:6/);
});

test('encode settings: 1080x1920 H.264 at 30 fps, tagged bt709, AAC when there is sound', () => {
  const segments = [seg(), seg()];
  const enc = buildVideoComposeArgs(plan(segments, { totalSec: 6 }), inputsFor(segments, { musicPath: '/tmp/m.wav' }), MEASURED);
  assert.ok(enc.join(' ').includes('-c:v libx264 -preset veryfast -crf 20 -pix_fmt yuv420p -r 30 -color_range tv'));
  assert.ok(enc.join(' ').includes('-c:a aac -b:a 128k -ar 48000 -ac 2'));
  assert.throws(() => buildVideoComposeArgs({ segments: [], totalSec: 3 }, { segmentPaths: [], outPath: 'o.mp4' }, null), /at least one segment/);
});
