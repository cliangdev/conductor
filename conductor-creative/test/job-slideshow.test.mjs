/* job-slideshow.test.mjs — run() on a CAROUSEL spec: ffmpeg only (no Chromium), real ffmpeg/ffprobe.
 * Skips without ffmpeg/ffprobe; CREATIVE_TEST_FFMPEG picks the binary. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { run } from '../job/render.mjs';
import { FFMPEG, skipUnlessFfmpeg, serveBytes, genSlide, genMusic, ffprobe, measureLufs, fakeTransport } from './media-helpers.mjs';

function carouselSpec(origin, { durations, volume = 1, track = true, previewOnly = false } = {}) {
  return {
    renderId: 's1',
    previewOnly,
    creative: {
      kind: 'CAROUSEL',
      slides: ['a', 'b', 'c', 'd'].map((n) => ({ url: `${origin}/${n}.jpg` })),
      slideshow: { slideDurations: durations || [3, 2.5, 2.5, 2.5], zoom: true, crossfadeSec: 0.5 },
      ...(track ? { audio: { source: 'track', trackUrl: `${origin}/music.wav`, volume, fadeInSec: 0.5, fadeOutSec: 1, targetLufs: -14 } } : {}),
    },
    brand: {},
    placements: [{ key: '9x16', width: 1080, height: 1920 }],
  };
}

async function fixtures(musicSeconds) {
  return serveBytes({
    '/a.jpg': { type: 'image/jpeg', bytes: genSlide('1080x1920') },
    '/b.jpg': { type: 'image/jpeg', bytes: genSlide('1080x1350') },
    '/c.jpg': { type: 'image/jpeg', bytes: genSlide('800x800') },
    '/d.jpg': { type: 'image/jpeg', bytes: genSlide('1920x1080') },
    '/music.wav': { type: 'audio/wav', bytes: genMusic(musicSeconds) },
  });
}

test('run(): a CAROUSEL with a track renders one 1080x1920 30fps h264 stream + one AAC stream at -14 LUFS', async (t) => {
  if (!skipUnlessFfmpeg(t)) return;
  const { server, origin } = await fixtures(20);
  try {
    const transport = fakeTransport(carouselSpec(origin));
    // no browserFactory: a slideshow must not launch (or need) a browser
    const ok = await run({ transport, ffmpegPath: FFMPEG, log: () => {}, browserFactory: () => { throw new Error('browser launched'); } });
    assert.equal(transport.calls.fail, null);
    assert.equal(ok, true);

    assert.equal(transport.calls.putFrame.length, 1);
    const frame = transport.calls.putFrame[0];
    assert.equal(frame.placementKey, '9x16');
    assert.equal('index' in frame, false, 'the video frame has no index');
    assert.equal(frame.contentType, 'video/mp4');
    assert.equal(frame.hasAudio, true);
    assert.equal(frame.durationSeconds, 9);

    const probe = await ffprobe(frame.bytes);
    const videos = probe.streams.filter((s) => s.codec_type === 'video');
    const audios = probe.streams.filter((s) => s.codec_type === 'audio');
    assert.equal(videos.length, 1);
    assert.equal(audios.length, 1);
    assert.equal(videos[0].codec_name, 'h264');
    assert.deepEqual([videos[0].width, videos[0].height], [1080, 1920]);
    assert.equal(videos[0].pix_fmt, 'yuv420p');
    assert.equal(videos[0].r_frame_rate, '30/1');
    assert.equal(audios[0].codec_name, 'aac');
    assert.equal(audios[0].sample_rate, '48000');
    assert.equal(audios[0].channels, 2);
    const duration = Number(probe.format.duration);
    assert.ok(Math.abs(duration - 9) < 0.1, `expected 9 s, got ${duration}`);

    const lufs = await measureLufs(frame.bytes);
    assert.ok(Math.abs(lufs - -14) < 1, `expected -14 LUFS +/- 1, got ${lufs}`);

    assert.equal(transport.calls.putPoster.length, 1);
    assert.equal(transport.calls.putPoster[0].placementKey, '9x16');
    assert.deepEqual([...transport.calls.putPoster[0].bytes.subarray(0, 2)], [0xff, 0xd8]);
    assert.ok(Array.isArray(transport.calls.complete));
  } finally {
    server.close();
  }
});

test('run(): volume 0.5 lands 6 LU lower, and a short track loops to cover the video', async (t) => {
  if (!skipUnlessFfmpeg(t)) return;
  const { server, origin } = await fixtures(4); // 4 s of music under a 9 s video
  try {
    const transport = fakeTransport(carouselSpec(origin, { volume: 0.5, durations: [3, 2.5, 2.5, 2.5], previewOnly: true }));
    const ok = await run({ transport, ffmpegPath: FFMPEG, log: () => {} });
    assert.equal(ok, true, JSON.stringify(transport.calls.fail));
    const bytes = transport.calls.putFrame[0].bytes; // previewOnly still produces the mp4
    const lufs = await measureLufs(bytes);
    assert.ok(Math.abs(lufs - -20.02) < 1, `expected ~-20 LUFS, got ${lufs}`);
    const probe = await ffprobe(bytes);
    assert.equal(probe.streams.filter((s) => s.codec_type === 'audio').length, 1);
    assert.ok(Math.abs(Number(probe.format.duration) - 9) < 0.1);
  } finally {
    server.close();
  }
});

test('run(): custom slide durations set the total (sum - 0.5 per join); no track means no audio stream', async (t) => {
  if (!skipUnlessFfmpeg(t)) return;
  const { server, origin } = await fixtures(2);
  try {
    const transport = fakeTransport(carouselSpec(origin, { durations: [4, 1.5, 2, 3], track: false }));
    const ok = await run({ transport, ffmpegPath: FFMPEG, log: () => {} });
    assert.equal(ok, true, JSON.stringify(transport.calls.fail));
    const frame = transport.calls.putFrame[0];
    assert.equal(frame.hasAudio, false);
    const probe = await ffprobe(frame.bytes);
    assert.equal(probe.streams.some((s) => s.codec_type === 'audio'), false);
    assert.ok(Math.abs(Number(probe.format.duration) - 9) < 0.1, `expected 9 s (10.5-1.5), got ${probe.format.duration}`);
  } finally {
    server.close();
  }
});

test('run(): a CAROUSEL render with no ffmpegPath fails with a clear error', async (t) => {
  const transport = fakeTransport(carouselSpec('http://127.0.0.1:1'));
  const ok = await run({ transport, log: () => {} });
  assert.equal(ok, false);
  assert.match(transport.calls.fail.message, /ffmpeg/);
  assert.equal(transport.calls.putFrame.length, 0);
});
