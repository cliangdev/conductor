import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { execFileSync } from 'node:child_process'
import { mkdtempSync, rmSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { composeVideoTool } from '../mcp/tools/video-compose.js'
import { generateVoiceLine } from '../mcp/tools/creative-voice.js'
import type { GenerateVoiceResult } from '../lib/voice-generation.js'
import { normalizeVoiceParams } from '../lib/voice-common.js'

/**
 * End to end for PR4's wiring: generate_voice_line's output (audio file + caption beats) goes straight into a
 * real compose_video render. The provider call is replaced by an ffmpeg-made stand-in tone (the live API is
 * mocked here; see voice-line.test.ts), so this proves the file format, `voicePath`, the beats and the
 * first-caption timing, not the voice quality. Skips when ffmpeg or a browser is missing.
 */

function have(cmd: string): boolean {
  try {
    execFileSync(cmd, ['-version'], { stdio: 'ignore' })
    return true
  } catch {
    return false
  }
}

async function browserAvailable(): Promise<boolean> {
  try {
    const { chromium } = await import('playwright-core')
    for (const options of [{ channel: 'chrome' }, { channel: 'msedge' }, {}]) {
      try {
        const browser = await chromium.launch(options)
        await browser.close()
        return true
      } catch {
        continue
      }
    }
  } catch {
    /* no playwright */
  }
  return false
}

const ready = have('ffmpeg') && have('ffprobe') && (await browserAvailable())
const d = ready ? describe : describe.skip
const ff = (...args: string[]) => execFileSync('ffmpeg', ['-v', 'error', '-y', ...args], { stdio: 'pipe' })
const streams = (file: string) =>
  (JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_streams', '-of', 'json', file], { encoding: 'utf8' })).streams as Array<{ codec_type: string }>).map((s) => s.codec_type)

let work: string
let clip = ''
let stillPath = ''

beforeAll(() => {
  if (!ready) return
  work = mkdtempSync(join(tmpdir(), 'voice-compose-e2e-'))
  clip = join(work, 'hook.mp4')
  ff('-f', 'lavfi', '-i', 'testsrc2=size=1080x1920:rate=30:duration=4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-an', clip)
  stillPath = join(work, 'still.png')
  ff('-f', 'lavfi', '-i', 'gradients=s=1080x1920:c0=0x203a5c:c1=0xe0a030:x0=0:y0=0:x1=1080:y1=1920:d=1', '-frames:v', '1', stillPath)
})
afterAll(() => {
  if (work) rmSync(work, { recursive: true, force: true })
})

d('generate_voice_line into compose_video', () => {
  it('a voiced render has audio and its first caption starts inside 0.5 s', async () => {
    // Stand-in for the provider: a 2.6 s 24 kHz 16-bit mono WAV, the same container Cloud TTS returns for LINEAR16.
    const wavPath = join(work, 'stand-in.wav')
    ff('-f', 'lavfi', '-i', 'sine=frequency=180:duration=2.6:sample_rate=24000', '-ac', '1', '-c:a', 'pcm_s16le', wavPath)
    const audio = readFileSync(wavPath)
    const fakeGenerate = async (p: Parameters<typeof normalizeVoiceParams>[0]): Promise<GenerateVoiceResult> => ({
      audio,
      request: normalizeVoiceParams(p),
      marked: false,
      estimateUsd: 0.001,
      characters: 40,
      breakdown: 'stand-in',
      spentTodayUsd: 0.001,
      dailyCapUsd: 2,
      confirmed: false,
    })
    const voice = await generateVoiceLine({ text: 'Stop scrolling, this one saves dinner tonight.' }, { outDir: join(work, 'voice'), generate: fakeGenerate })
    console.log('voice:', JSON.stringify({ durationSec: voice.durationSec, timing: voice.timing, beats: voice.captionBeats }))
    expect(voice.durationSec).toBeCloseTo(2.6, 1)
    expect(voice.captionBeats[0]!.fromSec).toBeLessThanOrEqual(0.5)

    const res = await composeVideoTool(
      {
        spec: {
          id: 'voiced',
          segments: [{ clip, startSec: 0, endSec: 3 }, { image: stillPath, durationSec: 3 }],
          texts: voice.captionBeats,
          audio: { voicePath: voice.audioPath, duck: true },
        },
      },
      { projectRoot: work }
    )
    expect(res.ok).toBe(true)
    if (!res.ok) return
    console.log('voiced render checks:', JSON.stringify(res.checks))
    expect(res.checks.filter((c) => c.severity === 'error')).toEqual([])
    expect(res.passed).toBe(true)
    expect(streams(res.videoPath!)).toContain('audio')
    const audioDur = Number(execFileSync('ffprobe', ['-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=duration', '-of', 'csv=p=0', res.videoPath!], { encoding: 'utf8' }))
    console.log('voiced render audio stream seconds:', audioDur)
    expect(audioDur).toBeGreaterThan(2.5)
  }, 180_000)
})
