/* job-clip-audio.test.mjs — run() on a CLIP spec: the clip's sound replaced (track), ducked under a track
 * (mix) or removed (none). ffmpeg only; skips without ffmpeg/ffprobe. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run } from '../job/render.mjs';
import { FFMPEG, skipUnlessFfmpeg, serveBytes, genClip, genMusic, ffprobe, measureLufs, bandRmsDb, fakeTransport } from './media-helpers.mjs';

const CLIP_TONE_HZ = 1000; // the clip's own sound; the music is pink noise + 220 Hz

function clipSpec(origin, audio, extra = {}) {
  return {
    renderId: 'c1',
    previewOnly: false,
    creative: { kind: 'CLIP', audio: { fadeInSec: 0.5, fadeOutSec: 1, targetLufs: -14, ...audio, ...(audio.source === 'none' ? {} : { trackUrl: `${origin}/music.wav` }) } },
    brand: {},
    placements: [{ key: '9x16', width: 320, height: 568, clipUrl: `${origin}/clip.mp4`, durationSec: 4, clipHasAudio: true, ...extra }],
  };
}

async function setup() {
  return serveBytes({
    '/clip.mp4': { type: 'video/mp4', bytes: genClip({ seconds: 5, withAudio: true, tone: CLIP_TONE_HZ }) },
    '/music.wav': { type: 'audio/wav', bytes: genMusic(20) },
  });
}

test('run(): CLIP track replaces the sound (loudness ~-14, clip tone gone, video copied)', async (t) => {
  if (!skipUnlessFfmpeg(t)) return;
  const { server, origin } = await setup();
  try {
    const transport = fakeTransport(clipSpec(origin, { source: 'track', volume: 1 }));
    const ok = await run({ transport, ffmpegPath: FFMPEG, log: () => {}, browserFactory: () => { throw new Error('browser launched'); } });
    assert.equal(ok, true, JSON.stringify(transport.calls.fail));
    const frame = transport.calls.putFrame[0];
    assert.equal(frame.placementKey, '9x16');
    assert.equal('index' in frame, false);
    assert.equal(frame.hasAudio, true);
    const probe = await ffprobe(frame.bytes);
    const video = probe.streams.find((s) => s.codec_type === 'video');
    assert.equal(video.codec_name, 'h264');
    assert.deepEqual([video.width, video.height], [320, 568]);
    assert.equal(probe.streams.filter((s) => s.codec_type === 'audio').length, 1);
    assert.ok(Math.abs(Number(probe.format.duration) - 4) < 0.2, `expected ~4 s, got ${probe.format.duration}`);
    const lufs = await measureLufs(frame.bytes);
    assert.ok(Math.abs(lufs - -14) < 1, `expected -14 LUFS +/- 1, got ${lufs}`);
    assert.ok((await bandRmsDb(frame.bytes, CLIP_TONE_HZ)) < -35, 'the clip\'s own tone must be gone');
    assert.equal(transport.calls.putPoster.length, 1);
  } finally {
    server.close();
  }
});

test('run(): CLIP mix keeps the clip tone under a track and lands at ~-14 LUFS', async (t) => {
  if (!skipUnlessFfmpeg(t)) return;
  const { server, origin } = await setup();
  try {
    const transport = fakeTransport(clipSpec(origin, { source: 'mix', volume: 0.3 }));
    const ok = await run({ transport, ffmpegPath: FFMPEG, log: () => {} });
    assert.equal(ok, true, JSON.stringify(transport.calls.fail));
    const frame = transport.calls.putFrame[0];
    const probe = await ffprobe(frame.bytes);
    assert.equal(probe.streams.filter((s) => s.codec_type === 'audio').length, 1);
    const lufs = await measureLufs(frame.bytes);
    assert.ok(Math.abs(lufs - -14) < 1, `expected -14 LUFS +/- 1, got ${lufs}`);
    const tone = await bandRmsDb(frame.bytes, CLIP_TONE_HZ);
    assert.ok(tone > -30, `the clip's tone should still be present, band level ${tone} dBFS`);
  } finally {
    server.close();
  }
});

test('run(): CLIP none strips the audio and copies the video; clipStartSec re-encodes to cut accurately', async (t) => {
  if (!skipUnlessFfmpeg(t)) return;
  const { server, origin } = await setup();
  try {
    const transport = fakeTransport(clipSpec(origin, { source: 'none' }));
    assert.equal(await run({ transport, ffmpegPath: FFMPEG, log: () => {} }), true, JSON.stringify(transport.calls.fail));
    const frame = transport.calls.putFrame[0];
    assert.equal(frame.hasAudio, false);
    const probe = await ffprobe(frame.bytes);
    assert.equal(probe.streams.some((s) => s.codec_type === 'audio'), false);
    assert.equal(probe.streams.find((s) => s.codec_type === 'video').codec_name, 'h264');

    const cut = fakeTransport(clipSpec(origin, { source: 'track', volume: 1 }, { clipStartSec: 1.2, durationSec: 3 }));
    assert.equal(await run({ transport: cut, ffmpegPath: FFMPEG, log: () => {} }), true, JSON.stringify(cut.calls.fail));
    const cutProbe = await ffprobe(cut.calls.putFrame[0].bytes);
    assert.ok(Math.abs(Number(cutProbe.format.duration) - 3) < 0.15, `expected ~3 s, got ${cutProbe.format.duration}`);
  } finally {
    server.close();
  }
});
