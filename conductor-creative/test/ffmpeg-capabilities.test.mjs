/* The ffmpeg binary in use must carry every filter/encoder the slideshow and clip-audio recipes need.
 * Skips without ffmpeg; CREATIVE_TEST_FFMPEG picks the binary. */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { FFMPEG, skipUnlessFfmpeg } from './media-helpers.mjs';

test('ffmpeg has the filters and encoders the music renders use', (t) => {
  if (!skipUnlessFfmpeg(t)) return;
  const filters = execFileSync(FFMPEG, ['-hide_banner', '-filters'], { encoding: 'utf8' });
  const encoders = execFileSync(FFMPEG, ['-hide_banner', '-encoders'], { encoding: 'utf8' });
  for (const f of ['xfade', 'zoompan', 'loudnorm', 'ebur128', 'sidechaincompress', 'amix', 'afade', 'atrim', 'aformat']) {
    assert.match(filters, new RegExp(`\\s${f}\\s`), `missing filter ${f}`);
  }
  for (const e of ['libx264', 'aac']) assert.match(encoders, new RegExp(`\\s${e}\\s`), `missing encoder ${e}`);
});
