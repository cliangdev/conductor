import { describe, it, expect, afterAll } from 'vitest'
import { mkdtempSync, writeFileSync, existsSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { createRequire } from 'node:module'
import { execFileSync } from 'node:child_process'
import { boxInZone, hitZones, projectBox, zonesFor } from '../lib/safe-zone/zones.js'
import { checkSlideImages, checkVideoFrames, ocrSlideWordCount } from '../lib/safe-zone/check.js'
import { closeOcr, dedupeWords, filterWords, OcrUnavailable, type OcrEngine, type OcrWord } from '../lib/safe-zone/ocr.js'
import { parseCutTimes, parseDuration, planSampleTimes } from '../lib/safe-zone/frames.js'

const tmp = mkdtempSync(join(tmpdir(), 'sz-test-'))
afterAll(async () => {
  await closeOcr()
  rmSync(tmp, { recursive: true, force: true })
})

/** A PNG whose header says w x h. The fake engines never decode it. */
function fakePng(w: number, h: number): Buffer {
  const b = Buffer.alloc(33)
  Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]).copy(b)
  b.writeUInt32BE(13, 8)
  b.write('IHDR', 12)
  b.writeUInt32BE(w, 16)
  b.writeUInt32BE(h, 20)
  b[24] = 8
  b[25] = 2
  return b
}
function slideFile(name: string, w = 1080, h = 1920): string {
  const p = join(tmp, name)
  writeFileSync(p, fakePng(w, h))
  return p
}
const word = (text: string, x: number, y: number, w = 200, h = 50): OcrWord => ({ text, confidence: 95, box: { x, y, w, h } })

/** An engine that returns the given words whose box centre lies inside the requested rect. */
function fakeEngine(words: OcrWord[]): OcrEngine {
  return {
    async recognizeWords(_png, rect) {
      if (!rect) return words
      return words.filter((w) => {
        const cx = w.box.x + w.box.w / 2
        const cy = w.box.y + w.box.h / 2
        return cx >= rect.left && cx <= rect.left + rect.width && cy >= rect.top && cy <= rect.top + rect.height
      })
    },
  }
}

describe('zones', () => {
  it('scales the table and rejects non 9:16 frames', () => {
    expect(zonesFor(1080, 1350)).toEqual([])
    expect(zonesFor(1920, 1080)).toEqual([])
    const z = zonesFor(540, 960)
    expect(z.map((x) => x.name)).toEqual(['top', 'bottom', 'right'])
    expect(z[0]!.rect).toEqual({ x: 0, y: 0, w: 540, h: 145 })
    expect(z[1]!.rect.y).toBe(745)
    expect(z[2]!.rect).toMatchObject({ x: 459, y: 288, w: 81, h: 528 })
  })
  it('top and right count any overlap, bottom needs 30% of the box height', () => {
    const z = zonesFor(1080, 1920)
    expect(hitZones({ x: 100, y: 280, w: 100, h: 50 }, z).map((x) => x.name)).toEqual(['top'])
    expect(hitZones({ x: 100, y: 295, w: 100, h: 50 }, z)).toEqual([])
    expect(boxInZone({ x: 100, y: 1480, w: 100, h: 50 }, z[1]!)).toBe(true) // 40 of 50
    expect(boxInZone({ x: 100, y: 1480, w: 100, h: 100 }, z[1]!)).toBe(true)
    expect(boxInZone({ x: 100, y: 1440, w: 100, h: 60 }, z[1]!)).toBe(false) // 10 of 60
    expect(hitZones({ x: 950, y: 700, w: 80, h: 40 }, z).map((x) => x.name)).toEqual(['right'])
    expect(hitZones({ x: 950, y: 300, w: 80, h: 40 }, z)).toEqual([])
  })
  it('projects a box outward from the centre', () => {
    const b = projectBox({ x: 540, y: 400, w: 100, h: 50 }, 1080, 1920, 1.1)
    expect(b.y).toBeCloseTo(960 + (400 - 960) * 1.1)
    expect(b.w).toBeCloseTo(110)
  })
})

describe('ocr word filtering', () => {
  it('keeps confident, wordlike, tall-enough words and scales the height floor', () => {
    const ws: OcrWord[] = [
      { text: 'Hello', confidence: 90, box: { x: 0, y: 0, w: 100, h: 30 } },
      { text: 'low', confidence: 50, box: { x: 0, y: 0, w: 100, h: 30 } },
      { text: 'a', confidence: 95, box: { x: 0, y: 0, w: 100, h: 30 } },
      { text: '--', confidence: 95, box: { x: 0, y: 0, w: 100, h: 30 } },
      { text: 'tiny', confidence: 95, box: { x: 0, y: 0, w: 100, h: 10 } },
    ]
    expect(filterWords(ws, 1080).map((w) => w.text)).toEqual(['Hello'])
    expect(filterWords(ws, 540).map((w) => w.text)).toEqual(['Hello', 'tiny'])
  })
  it('dedupes overlapping words from the two passes', () => {
    const out = dedupeWords([
      { text: 'Hi', confidence: 80, box: { x: 0, y: 0, w: 100, h: 40 } },
      { text: 'Hi', confidence: 90, box: { x: 4, y: 2, w: 100, h: 40 } },
      { text: 'There', confidence: 90, box: { x: 300, y: 0, w: 100, h: 40 } },
    ])
    expect(out.map((w) => w.confidence)).toEqual([90, 90])
  })
})

describe('checkSlideImages', () => {
  it('flags top text on slide 1 as an error, with a useful message', async () => {
    const eng = fakeEngine([word('SALE', 100, 160), word('TODAY', 320, 165)])
    const checks = await checkSlideImages([{ path: slideFile('a.png'), index: 0 }], { engine: eng })
    expect(checks).toHaveLength(1)
    const c = checks[0]!
    expect(c).toMatchObject({ rule: 'safeZoneText', severity: 'error', index: 0, placementKey: '9x16', text: 'SALE TODAY' })
    expect(c.message).toContain('Slide 1')
    expect(c.message).toContain('"SALE TODAY"')
    expect(c.message).toContain('y=160')
    expect(c.message).toContain('Instagram Reels header')
    expect(c.message).toContain('move it below y=290 at 1080x1920')
    expect(c.box).toEqual({ x: 100, y: 160, w: 420, h: 55 })
  })
  it('makes top text on later slides, and bottom/right text, warnings', async () => {
    const eng = fakeEngine([word('TOP', 100, 160), word('BOTTOM', 100, 1600), word('SIDE', 930, 800, 100)])
    const checks = await checkSlideImages([{ path: slideFile('b.png'), index: 2 }], { engine: eng })
    expect(checks.map((c) => `${c.severity}:${c.platforms?.[0]}`)).toEqual([
      'warning:Instagram Reels header',
      'warning:Instagram caption, username and music row',
      'warning:Instagram, TikTok and Shorts like/comment/share column',
    ])
    expect(checks[0]!.message).toContain('Slide 3')
    expect(checks[2]!.message).toContain('x=930')
  })
  it('skips non 9:16 slides and returns [] when nothing is in a zone', async () => {
    const eng = fakeEngine([word('MIDDLE', 100, 900)])
    expect(await checkSlideImages([{ path: slideFile('sq.png', 1080, 1080), index: 0 }], { engine: eng })).toEqual([])
    expect(await checkSlideImages([{ path: slideFile('c.png'), index: 0 }], { engine: eng })).toEqual([])
  })
  it('projects boxes through the zoom before testing', async () => {
    // y=320..370 is clear of the 290 line, but a 1.12x zoom about the centre moves it to ~y=297 -> still clear;
    // text at y=345 projects to 960+(345-960)*1.12 = 271 -> inside the top zone.
    const eng = fakeEngine([word('NEARLY', 100, 345, 200, 20)])
    const slide = [{ path: slideFile('z.png'), index: 1 }]
    expect(await checkSlideImages(slide, { engine: eng })).toEqual([])
    const zoomed = await checkSlideImages(slide, { engine: eng, zoom: { maxScale: 1.12 } })
    expect(zoomed).toHaveLength(1)
    expect(zoomed[0]!.message).toContain('zoom')
  })
  it('reads and catches text in the outer edge of the top zone when zoom is on', async () => {
    // y=20..50 is visible on the first frames (zoom starts at 1x) and is pushed off-screen only later.
    const eng = fakeEngine([word('EDGE', 100, 20, 200, 30)])
    const checks = await checkSlideImages([{ path: slideFile('edge.png'), index: 1 }], { engine: eng, zoom: { maxScale: 1.06 } })
    expect(checks).toHaveLength(1)
    expect(checks[0]!.message).toContain('y=20')
  })
  it('returns one safeZoneUnchecked warning when OCR is unavailable', async () => {
    const eng: OcrEngine = { recognizeWords: async () => { throw new OcrUnavailable('no wasm') } }
    const checks = await checkSlideImages([{ path: slideFile('d.png'), index: 0 }], { engine: eng })
    expect(checks).toHaveLength(1)
    expect(checks[0]).toMatchObject({ rule: 'safeZoneUnchecked', severity: 'warning' })
    expect(checks[0]!.message).toContain('(no wasm)')
    expect(checks[0]!.message).toContain('below y=1490')
  })
})

describe('checkVideoFrames', () => {
  const frame = (atSeconds: number, isCut = false) => ({ atSeconds, png: fakePng(1080, 1920), isCut })

  it('reports one check per run of the same text, with the first time and cut flag', async () => {
    // The engine is called once per zone per frame (top, bottom, right), so index frames by call count.
    const perFrame: OcrWord[][] = [[word('HOOK', 100, 170)], [word('HOOK', 100, 170)], [], [word('HOOK', 100, 170)], [word('Later', 100, 170)]]
    let call = 0
    const engine: OcrEngine = {
      async recognizeWords(png, rect) {
        const frameIdx = Math.floor(call++ / 3)
        return fakeEngine(perFrame[frameIdx] ?? []).recognizeWords(png, rect)
      },
    }
    const frames = [frame(0.5), frame(1), frame(1.5), frame(3.5, true), frame(4)]
    const checks = await checkVideoFrames('x.mp4', { engine, sampler: async () => frames })
    expect(checks.map((c) => [c.atSeconds, c.severity, c.text])).toEqual([
      [0.5, 'error', 'HOOK'],
      [3.5, 'warning', 'HOOK'],
      [4, 'warning', 'Later'],
    ])
    expect(checks[1]!.message).toContain('right after a cut')
  })
  it('returns [] for clean frames and a warning when sampling fails', async () => {
    expect(await checkVideoFrames('x.mp4', { engine: fakeEngine([]), sampler: async () => [frame(0)] })).toEqual([])
    const failed = await checkVideoFrames('x.mp4', { engine: fakeEngine([]), sampler: async () => { throw new Error('ffmpeg missing') } })
    expect(failed[0]).toMatchObject({ rule: 'safeZoneUnchecked' })
    expect(failed[0]!.message).toContain('ffmpeg missing')
  })
})

describe('ocrSlideWordCount', () => {
  it('counts words and lines', async () => {
    const eng = fakeEngine([word('one', 10, 100), word('two', 300, 102), word('three', 10, 400)])
    expect(await ocrSlideWordCount(slideFile('w.png'), eng)).toEqual({ words: 3, lines: 2 })
    expect(await ocrSlideWordCount(slideFile('w.png'), fakeEngine([]))).toEqual({ words: 0, lines: 0 })
  })
})

describe('frame sampling plan', () => {
  it('parses ffmpeg logs', () => {
    expect(parseDuration('  Duration: 00:01:02.50, start')).toBeCloseTo(62.5)
    expect(parseCutTimes('[Parsed_showinfo] n:0 pts:1 pts_time:1.5 x\n pts_time:3')).toEqual([1.5, 3])
  })
  it('samples every 0.5 s, adds cuts, dedupes within 0.2 s', () => {
    const plan = planSampleTimes(4, [1.6, 2.2])
    const times = plan.map((p) => p.at)
    expect(times).toContain(2.2)
    expect(times).not.toContain(1.5) // replaced by the cut at 1.6
    expect(plan.find((p) => p.at === 1.6)?.isCut).toBe(true)
    expect(plan.find((p) => p.at === 2.2)?.isCut).toBe(true)
    expect(times.filter((t) => t < 3)).toEqual([0, 0.5, 1, 1.6, 2, 2.2, 2.5])
  })
  it('caps long clips but keeps 0.5 s steps before 3 s', () => {
    const plan = planSampleTimes(600, [])
    expect(plan.length).toBeLessThanOrEqual(60)
    expect(plan.filter((p) => p.at < 3).map((p) => p.at)).toEqual([0, 0.5, 1, 1.5, 2, 2.5])
    expect(plan[plan.length - 1]!.at).toBeGreaterThan(500)
  })
})

// ---------------------------------------------------------------------------------------------
// Real OCR (skipped when drawtext, a font, or the OCR engine is unavailable)
// ---------------------------------------------------------------------------------------------
const require = createRequire(import.meta.url)
const FONTS = ['/System/Library/Fonts/Helvetica.ttc', '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', '/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf']
const font = FONTS.find((f) => existsSync(f))
let ffmpegWithText: string | undefined
try {
  const candidates = ['ffmpeg', require('ffmpeg-static') as string]
  ffmpegWithText = candidates.find((c) => {
    try {
      return execFileSync(c, ['-hide_banner', '-filters'], { encoding: 'utf8' }).includes('drawtext')
    } catch {
      return false
    }
  })
} catch {
  /* none */
}
const canDraw = Boolean(font && ffmpegWithText)

function drawText(args: string[]): void {
  execFileSync(ffmpegWithText!, ['-y', '-v', 'error', ...args], { stdio: 'pipe' })
}
const textFilter = (y: number, extra = '') =>
  `drawtext=fontfile=${font}:text='SAVE MORE TODAY':fontsize=90:fontcolor=white:x=(w-text_w)/2:y=${y}${extra}`

describe.skipIf(!canDraw)('real OCR', () => {
  it('flags text at y=160 as a top error and ignores the same text at y=400', async () => {
    const top = join(tmp, 'real-top.png')
    const low = join(tmp, 'real-low.png')
    drawText(['-f', 'lavfi', '-i', 'color=c=0x203040:s=1080x1920', '-vf', textFilter(160), '-frames:v', '1', top])
    drawText(['-f', 'lavfi', '-i', 'color=c=0x203040:s=1080x1920', '-vf', textFilter(400), '-frames:v', '1', low])
    const t0 = Date.now()
    const hit = await checkSlideImages([{ path: top, index: 0 }])
    const ms = Date.now() - t0
    if (hit[0]?.rule === 'safeZoneUnchecked') return // OCR cannot load here
    console.log(`real OCR slide (incl. worker start): ${ms} ms`)
    expect(hit).toHaveLength(1)
    expect(hit[0]).toMatchObject({ severity: 'error', rule: 'safeZoneText' })
    expect(hit[0]!.text?.toUpperCase()).toContain('SAVE')
    const t1 = Date.now()
    expect(await checkSlideImages([{ path: low, index: 0 }])).toEqual([])
    console.log(`real OCR slide (warm): ${Date.now() - t1} ms`)
  }, 60_000)

  it('finds caption text in the first second of a clip', async () => {
    const clip = join(tmp, 'clip.mp4')
    drawText([
      '-f', 'lavfi', '-i', 'color=c=0x203040:s=1080x1920:r=15:d=4',
      '-vf', textFilter(170, ":enable='lt(t,1)'"),
      '-pix_fmt', 'yuv420p', clip,
    ])
    const t0 = Date.now()
    const checks = await checkVideoFrames(clip)
    console.log(`real OCR 4 s clip: ${Date.now() - t0} ms`)
    if (checks[0]?.rule === 'safeZoneUnchecked') return
    expect(checks.length).toBeGreaterThan(0)
    expect(checks[0]).toMatchObject({ severity: 'error', rule: 'safeZoneText' })
    expect(checks[0]!.atSeconds).toBeLessThan(1)
  }, 90_000)
})

describe('slideshow composer safe margins agree with the OCR zones', () => {
  it('uses the same top, bottom and right edges as zones.ts', async () => {
    const { SAFE } = await import('../lib/slideshow/html.js')
    const { TOP_EDGE, BOTTOM_EDGE, RIGHT_EDGE, REF_WIDTH, REF_HEIGHT } = await import('../lib/safe-zone/zones.js')
    expect(SAFE.top).toBe(TOP_EDGE)
    expect(REF_HEIGHT - SAFE.bottom).toBe(BOTTOM_EDGE)
    expect(REF_WIDTH - SAFE.right).toBe(RIGHT_EDGE)
  })
})

describe('planSampleTimes with many cuts', () => {
  it('keeps early frames and the end of a 45 s clip with 80 cuts, within the cap', () => {
    const cuts = Array.from({ length: 80 }, (_, i) => 0.3 + i * 0.55)
    const plan = planSampleTimes(45, cuts)
    const times = plan.map((p) => p.at)
    expect(plan.length).toBeLessThanOrEqual(60)
    expect(times[times.length - 1]!).toBeGreaterThan(44.5)
    for (const t of [0, 0.5, 1, 1.5, 2, 2.5]) expect(times.some((x) => Math.abs(x - t) < 0.2)).toBe(true)
    expect(times).toEqual([...times].sort((a, b) => a - b))
  })
})
