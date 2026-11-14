import { describe, it, expect, beforeAll, afterAll } from 'vitest'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdtempSync, rmSync, readFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { composeVideoTool } from '../mcp/tools/video-compose.js'
import { measureOpeningMotion } from '../lib/video/render.js'
import { parseCutTimes } from '../lib/safe-zone/frames.js'

/**
 * End to end: real ffmpeg, real Chromium for the text, real OCR for the safe-zone pass. Inputs are synthesised
 * with ffmpeg lavfi sources (a moving 4 s clip with a tone, three gradient stills, a sine "music" file), so
 * nothing binary is committed. Skips when ffmpeg or a launchable browser is missing.
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
const probe = (file: string) => {
  const j = JSON.parse(execFileSync('ffprobe', ['-v', 'error', '-show_streams', '-show_format', '-of', 'json', file], { encoding: 'utf8' }))
  const v = j.streams.find((s: { codec_type: string }) => s.codec_type === 'video')
  return { width: v.width as number, height: v.height as number, duration: Number(j.format.duration), hasAudio: j.streams.some((s: { codec_type: string }) => s.codec_type === 'audio') }
}
/** An 8-bit grey frame (108x192) at a time, for pixel comparisons. */
const gray = (file: string, at: number): Buffer =>
  execFileSync('ffmpeg', ['-v', 'error', '-ss', String(at), '-i', file, '-frames:v', '1', '-vf', 'scale=108:192,format=gray', '-f', 'rawvideo', '-'], { maxBuffer: 1 << 20 })
const meanDiff = (a: Buffer, b: Buffer, rows?: [number, number]) => {
  const w = 108
  const [r0, r1] = rows ?? [0, 192]
  let s = 0
  let n = 0
  for (let i = r0 * w; i < r1 * w; i++) {
    s += Math.abs(a[i]! - b[i]!)
    n++
  }
  return s / n
}

/** Scene-change times (s) of a video, from ffmpeg's showinfo log. */
const sceneCuts = (file: string): number[] =>
  parseCutTimes(execFileSync('sh', ['-c', `ffmpeg -hide_banner -i "$1" -vf "select='gt(scene,0.1)',showinfo" -an -f null - 2>&1`, 'sh', file], { encoding: 'utf8' }))

let work: string
const paths = { clip: '', imgs: [] as string[], music: '' }

beforeAll(() => {
  if (!ready) return
  work = mkdtempSync(join(tmpdir(), 'compose-video-e2e-'))
  paths.clip = join(work, 'hook.mp4')
  ff('-f', 'lavfi', '-i', 'testsrc2=size=1080x1920:rate=30:duration=4', '-f', 'lavfi', '-i', 'sine=frequency=220:duration=4', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-c:a', 'aac', '-shortest', paths.clip)
  const colours: Array<[string, string]> = [['0x203a5c', '0xe0a030'], ['0x5c2030', '0x30e0a0'], ['0x205c30', '0xa030e0']]
  colours.forEach(([c0, c1], i) => {
    const p = join(work, `still-${i}.png`)
    ff('-f', 'lavfi', '-i', `gradients=s=1080x1920:c0=${c0}:c1=${c1}:x0=0:y0=0:x1=1080:y1=1920:d=1`, '-frames:v', '1', p)
    paths.imgs.push(p)
  })
  paths.music = join(work, 'music.wav')
  ff('-f', 'lavfi', '-i', 'sine=frequency=440:duration=8', paths.music)
})
afterAll(() => {
  if (work) rmSync(work, { recursive: true, force: true })
})

const goodSpec = () => ({
  id: 'hook-e2e',
  segments: [
    { clip: paths.clip, startSec: 0.4, endSec: 3.8 },
    { image: paths.imgs[0], durationSec: 3, zoom: true },
    { image: paths.imgs[1], durationSec: 3, zoom: true },
    { image: paths.imgs[2], durationSec: 3 },
  ],
  texts: [
    { fromSec: 0, toSec: 3.4, t: 'You are doing this wrong', y: 30, style: 'box' },
    { fromSec: 3.4, toSec: 6.4, t: 'Here is the fix', y: 45, style: 'stroke' },
    { fromSec: 6.4, toSec: 12.4, t: 'Save this for later', y: 50, style: 'dark' },
  ],
  audio: { clipAudio: 'keep', track: paths.music, duck: true },
})

d('compose_video end to end', () => {
  it('renders a hook clip + stills + text + music, and the output matches the timeline', async () => {
    const logs: string[] = []
    const res = await composeVideoTool({ spec: goodSpec() }, { projectRoot: work, log: (m) => logs.push(m) })
    expect(res.ok).toBe(true)
    if (!res.ok) return
    console.log('good render checks:', JSON.stringify(res.checks))
    expect(res.rendered).toBe(true)
    const out = probe(res.videoPath!)
    console.log('ffprobe:', JSON.stringify(out), 'timeline:', JSON.stringify(res.timeline))
    expect(out.width).toBe(1080)
    expect(out.height).toBe(1920)
    expect(Math.abs(out.duration - 12.4)).toBeLessThanOrEqual(0.1)
    expect(out.hasAudio).toBe(true)
    expect(res.checks.filter((c) => c.severity === 'error')).toEqual([])
    expect(res.passed).toBe(true)
    expect(existsSync(res.frame0Path!)).toBe(true)
    expect(existsSync(res.contactSheetPath!)).toBe(true)
    expect(res.image?.mimeType).toBe('image/jpeg')
    expect(existsSync(`${res.videoPath}.provenance.json`)).toBe(false) // inputs had no sidecars
    expect(res.provenance.needed.length).toBeGreaterThan(0)

    // frame 0 has the text: the band around y=30% differs from the same video rendered without text
    const bare = await composeVideoTool({ spec: { ...goodSpec(), id: 'bare', texts: [{ fromSec: 0, toSec: 1, t: 'x', y: 62 }] } }, { projectRoot: work })
    if (!bare.ok) throw new Error('bare render failed')
    const withText = gray(res.videoPath!, 0)
    const without = gray(bare.videoPath!, 0)
    const band = meanDiff(withText, without, [Math.round(0.28 * 192), Math.round(0.36 * 192)])
    const elsewhere = meanDiff(withText, without, [Math.round(0.05 * 192), Math.round(0.2 * 192)])
    console.log('frame0 text band diff', band, 'elsewhere', elsewhere)
    expect(band).toBeGreaterThan(10)
    expect(elsewhere).toBeLessThan(2)

    // the trim: output frame 0 is source frame 0.4 s, not 0 s
    const src04 = gray(paths.clip, 0.4)
    const src0 = gray(paths.clip, 0)
    const rowsClear: [number, number] = [Math.round(0.4 * 192), Math.round(0.7 * 192)]
    const dTrim = meanDiff(without, src04, rowsClear)
    const dNoTrim = meanDiff(without, src0, rowsClear)
    console.log('trim diff vs 0.4s', dTrim, 'vs 0s', dNoTrim)
    const others = [0, 0.2, 0.6, 0.8].map((t) => meanDiff(without, gray(paths.clip, t), rowsClear))
    expect(dTrim).toBeLessThan(dNoTrim)
    expect(dTrim).toBeLessThan(Math.min(...others))
    expect(dTrim).toBeLessThan(3)

    // every frame is there: 12.4 s at 30 fps, and the video stream is as long as the file
    const frames = Number(execFileSync('ffprobe', ['-v', 'error', '-count_frames', '-select_streams', 'v', '-show_entries', 'stream=nb_read_frames', '-of', 'csv=p=0', res.videoPath!], { encoding: 'utf8' }).trim())
    console.log('video frames', frames)
    expect(frames).toBe(372)

    // the cuts land at the trim point (3.4 s) and at 6.4 s and 9.4 s, to the frame
    const cuts = sceneCuts(res.videoPath!)
    console.log('scene cuts at', cuts)
    for (const expected of [3.4, 6.4, 9.4]) expect(cuts.some((c) => Math.abs(c - expected) <= 1 / 30 + 0.001)).toBe(true)
  }, 240_000)

  it('cross-fades where asked, keeps clip sound with no music, and holds the length', async () => {
    const spec = {
      id: 'fades',
      segments: [
        { clip: paths.clip, startSec: 0, endSec: 3 },
        { image: paths.imgs[0], durationSec: 3, transition: 'fade' },
        { image: paths.imgs[1], durationSec: 3 },
      ],
      texts: [{ fromSec: 0, toSec: 8.5, t: 'Fade test', y: 30 }],
    }
    const res = await composeVideoTool({ spec }, { projectRoot: work })
    if (!res.ok) throw new Error(res.error)
    console.log('fade checks', JSON.stringify(res.checks), JSON.stringify(res.timeline))
    expect(res.timeline.map((s) => [s.fromSec, s.toSec, s.transition])).toEqual([[0, 3, 'cut'], [2.5, 5.5, 'fade'], [5.5, 8.5, 'cut']])
    const out = probe(res.videoPath!)
    expect(Math.abs(out.duration - 8.5)).toBeLessThanOrEqual(0.1)
    expect(out.hasAudio).toBe(true)
    expect(res.passed).toBe(true)
  }, 240_000)

  it('refuses text-free openings without rendering', async () => {
    const spec = goodSpec()
    spec.texts = [{ fromSec: 1.5, toSec: 6, t: 'Late text', y: 30, style: 'box' }]
    const res = await composeVideoTool({ spec: { ...spec, id: 'late' } }, { projectRoot: work })
    if (!res.ok) throw new Error(res.error)
    expect(res.rendered).toBe(false)
    expect(res.passed).toBe(false)
    expect(res.checks.find((c) => c.rule === 'firstSecondText')?.message).toMatch(/first second has no on-screen text/)
  }, 120_000)

  it('flags a completely static opening as an error, and a zoomed still as a warning', async () => {
    const stillSpec = (zoom: boolean) => ({
      id: zoom ? 'zoomed' : 'static',
      segments: [
        { image: paths.imgs[0], durationSec: 3, zoom },
        { image: paths.imgs[1], durationSec: 3 },
      ],
      texts: [{ fromSec: 0, toSec: 6, t: 'Hold on', y: 30, style: 'box' }],
    })
    const stat = await composeVideoTool({ spec: stillSpec(false) }, { projectRoot: work })
    if (!stat.ok) throw new Error(stat.error)
    const zoomed = await composeVideoTool({ spec: stillSpec(true) }, { projectRoot: work })
    if (!zoomed.ok) throw new Error(zoomed.error)
    const sScore = await measureOpeningMotion(stat.videoPath!)
    const zScore = await measureOpeningMotion(zoomed.videoPath!)
    const cScore = await measureOpeningMotion((await composeVideoTool({ spec: { ...goodSpec(), id: 'again' } }, { projectRoot: work }) as { videoPath: string }).videoPath)
    console.log('motion scores: static still', sScore, 'zoomed still', zScore, 'moving clip', cScore)
    console.log('static checks', JSON.stringify(stat.checks.filter((c) => c.rule === 'firstSecondMotion')))
    console.log('zoom checks', JSON.stringify(zoomed.checks.filter((c) => c.rule === 'firstSecondMotion')))
    expect(stat.checks.find((c) => c.rule === 'firstSecondMotion')?.severity).toBe('error')
    expect(stat.passed).toBe(false)
    expect(zoomed.checks.find((c) => c.rule === 'firstSecondMotion')?.severity).toBe('warning')
    expect(zoomed.passed).toBe(true)
    expect(JSON.parse(readFileSync(zoomed.specPath, 'utf8')).layout).toBe(2)
  }, 300_000)
})
