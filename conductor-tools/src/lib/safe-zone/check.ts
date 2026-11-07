/**
 * The safe-zone text checks: look for readable text inside the areas the platforms cover with their
 * own interface (see zones.ts), in finished slide images and in frames sampled from a video.
 * An empty result always means "looked and found nothing"; if OCR cannot run the result is one
 * `safeZoneUnchecked` warning instead.
 */
import { readFile } from 'node:fs/promises'
import type { DraftCheck } from '../creative-render.js'
import { readImageDimensions } from '../image-dimensions.js'
import { getDefaultEngine, OcrUnavailable, type OcrEngine, type OcrRect, type OcrWord } from './ocr.js'
import { sampleVideoFrames, type SampledFrame, type SampledFrames } from './frames.js'
import { hitZones, projectBox, REF_WIDTH, zonesFor, type PixelBox, type SafeZone } from './zones.js'

/** A top hit this early in a video (seconds) is an error; later it is a warning. */
const EARLY_SECONDS = 3

/** Something hit in one zone: the merged words of that zone on one image. */
interface ZoneHit {
  zone: SafeZone
  text: string
  box: PixelBox
}

const clampInt = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, Math.round(v)))

/** The pixels to read for a zone. The zoom starts at 1x, so the zone itself is always read; with zoom,
 * text outside the zone can also be pushed into it later, so the union of the zone and its inward
 * projection (by 1/maxScale) is read, clamped to the image. */
function ocrRectFor(zone: SafeZone, width: number, height: number, maxScale: number | undefined): OcrRect {
  let b: PixelBox = zone.rect
  if (maxScale && maxScale > 1) {
    const p = projectBox(zone.rect, width, height, 1 / maxScale)
    const x0 = Math.min(b.x, p.x)
    const y0 = Math.min(b.y, p.y)
    b = { x: x0, y: y0, w: Math.max(b.x + b.w, p.x + p.w) - x0, h: Math.max(b.y + b.h, p.y + p.h) - y0 }
  }
  const left = clampInt(b.x, 0, width - 1)
  const top = clampInt(b.y, 0, height - 1)
  return {
    left,
    top,
    width: clampInt(b.x + b.w, left + 1, width) - left,
    height: clampInt(b.y + b.h, top + 1, height) - top,
  }
}

/** Joins words into reading order (line by line) and returns their union box. */
function mergeWords(words: OcrWord[]): { text: string; box: PixelBox } {
  const sorted = [...words].sort((a, b) => a.box.y - b.box.y || a.box.x - b.box.x)
  const lines: OcrWord[][] = []
  for (const w of sorted) {
    const line = lines.find((l) => Math.abs(l[0]!.box.y + l[0]!.box.h / 2 - (w.box.y + w.box.h / 2)) < l[0]!.box.h * 0.6)
    if (line) line.push(w)
    else lines.push([w])
  }
  const text = lines.map((l) => l.sort((a, b) => a.box.x - b.box.x).map((w) => w.text).join(' ')).join(' ')
  const x0 = Math.min(...words.map((w) => w.box.x))
  const y0 = Math.min(...words.map((w) => w.box.y))
  const x1 = Math.max(...words.map((w) => w.box.x + w.box.w))
  const y1 = Math.max(...words.map((w) => w.box.y + w.box.h))
  return { text, box: { x: x0, y: y0, w: x1 - x0, h: y1 - y0 } }
}

/** OCRs the zone areas of one image and returns one merged hit per zone that has text in it. */
async function zoneHits(png: Buffer, engine: OcrEngine, zoom: { maxScale: number } | undefined): Promise<ZoneHit[]> {
  const dim = readImageDimensions(png)
  if (!dim) return []
  const zones = zonesFor(dim.width, dim.height)
  const hits: ZoneHit[] = []
  for (const zone of zones) {
    const words = await engine.recognizeWords(png, ocrRectFor(zone, dim.width, dim.height, zoom?.maxScale))
    const inZone = words.filter((w) => {
      // Zoom grows from 1x to maxScale, so test the word where it starts and where it ends up.
      if (hitZones(w.box, [zone]).length > 0) return true
      if (!(zoom?.maxScale && zoom.maxScale > 1)) return false
      return hitZones(projectBox(w.box, dim.width, dim.height, zoom.maxScale), [zone]).length > 0
    })
    if (inZone.length) hits.push({ zone, ...mergeWords(inZone) })
  }
  return hits
}

/** "y=160" or "x=950", at the 1080x1920 scale the platforms are measured in. */
function positionText(hit: ZoneHit, width: number): string {
  const s = REF_WIDTH / width
  return hit.zone.name === 'right' ? `x=${Math.round(hit.box.x * s)}` : `y=${Math.round(hit.box.y * s)}`
}

/** The fix, in plain words. */
function fixText(zone: SafeZone): string {
  if (zone.name === 'top') return `move it below y=${zone.edge1080} at 1080x1920`
  if (zone.name === 'bottom') return `move it above y=${zone.edge1080} at 1080x1920`
  return `move it left of x=${zone.edge1080} at 1080x1920`
}

const ZONE_LABEL = { top: 'top', bottom: 'bottom', right: 'right-hand' } as const

function describe(where: string, hit: ZoneHit, width: number, suffix = ''): string {
  return (
    `${where}: the text "${hit.text}" sits at ${positionText(hit, width)}, in the ${ZONE_LABEL[hit.zone.name]} area covered by ` +
    `${hit.zone.platforms.join(' / ')}${suffix}; ${fixText(hit.zone)}.`
  )
}

/** The single warning returned when OCR could not run. */
export function uncheckedCheck(placementKey: string, reason: string): DraftCheck {
  return {
    placementKey,
    rule: 'safeZoneUnchecked',
    severity: 'warning',
    message:
      `The safe-zone text check could not run (${reason}), so text in the platform UI zones was not looked for. ` +
      'Check slide 1 and the first 3 seconds by eye: nothing above y=290, below y=1490, or in the right 15% column at 1080x1920.',
  }
}

function reasonOf(e: unknown): string {
  return e instanceof Error ? e.message : String(e)
}

/** The unchecked warning for a run that did look, but could not read some slides or frames. */
function partialUnchecked(placementKey: string, what: string): DraftCheck {
  return {
    placementKey,
    rule: 'safeZoneUnchecked',
    severity: 'warning',
    message:
      `The safe-zone text check could not read ${what}, so text in the platform UI zones was not looked for there. ` +
      'Check those by eye: nothing above y=290, below y=1490, or in the right 15% column at 1080x1920.',
  }
}

export interface SlideCheckOptions {
  placementKey?: string
  /** When the slideshow zooms (Ken Burns), boxes are pushed outward from the centre by this factor. */
  zoom?: { maxScale: number }
  engine?: OcrEngine
}

/** Checks finished slide images (9:16 only) for text in the platform UI zones. Top on slide 1 is an error. */
export async function checkSlideImages(slides: Array<{ path: string; index: number }>, options: SlideCheckOptions = {}): Promise<DraftCheck[]> {
  const { placementKey = '9x16', zoom, engine = getDefaultEngine() } = options
  const checks: DraftCheck[] = []
  const failed: Array<{ index: number; reason: string }> = []
  let read = 0
  for (const slide of slides) {
    let hits: ZoneHit[]
    let width: number
    try {
      const png = await readFile(slide.path)
      const dim = readImageDimensions(png)
      if (!dim || zonesFor(dim.width, dim.height).length === 0) continue
      width = dim.width
      hits = await zoneHits(png, engine, zoom)
    } catch (e) {
      failed.push({ index: slide.index, reason: reasonOf(e) })
      // An engine that cannot start will not start for the next slide either.
      if (e instanceof OcrUnavailable && e.engineDown && read === 0) break
      continue
    }
    read++
    for (const hit of hits) {
      const suffix = zoom ? ` once the slideshow zoom (up to ${zoom.maxScale}x) is applied` : ''
      checks.push({
        placementKey,
        index: slide.index,
        rule: 'safeZoneText',
        severity: hit.zone.name === 'top' && slide.index === 0 ? 'error' : 'warning',
        message: describe(`Slide ${slide.index + 1}`, hit, width, suffix),
        box: hit.box,
        text: hit.text,
        platforms: hit.zone.platforms,
      })
    }
  }
  if (failed.length && read === 0) return [uncheckedCheck(placementKey, failed[0]!.reason)]
  if (failed.length) {
    checks.push(partialUnchecked(placementKey, `slide${failed.length === 1 ? '' : 's'} ${failed.map((f) => f.index + 1).join(', ')} (${failed[0]!.reason})`))
  }
  return checks
}

export interface VideoCheckOptions {
  placementKey?: string
  engine?: OcrEngine
  sampler?: (videoPath: string) => Promise<SampledFrame[]>
}

const norm = (s: string) => s.toLowerCase().replace(/[^\p{L}\p{N}]/gu, '')

/** True when two OCR readings are the same on-screen text (one may have lost or gained a word). */
function sameText(a: string, b: string): boolean {
  const x = norm(a)
  const y = norm(b)
  return x === y || (x.length > 2 && y.length > 2 && (x.includes(y) || y.includes(x)))
}

interface Run {
  hit: ZoneHit
  atSeconds: number
  isCut: boolean
  width: number
  lastText: string
}

/** Samples a video and reports text in the UI zones: one check per zone per run of frames showing the same text. */
export async function checkVideoFrames(videoPath: string, options: VideoCheckOptions = {}): Promise<DraftCheck[]> {
  const { placementKey = '9x16', engine = getDefaultEngine(), sampler = sampleVideoFrames } = options
  const checks: DraftCheck[] = []
  const open = new Map<string, Run>()
  const close = (name: string) => {
    const run = open.get(name)
    if (!run) return
    open.delete(name)
    const { hit, atSeconds, isCut, width } = run
    const when = `${atSeconds.toFixed(1)}s${isCut ? ' (right after a cut)' : ''}`
    checks.push({
      placementKey,
      rule: 'safeZoneText',
      severity: hit.zone.name === 'top' && atSeconds < EARLY_SECONDS ? 'error' : 'warning',
      message: describe(`At ${when}`, hit, width),
      atSeconds,
      box: hit.box,
      text: hit.text,
      platforms: hit.zone.platforms,
    })
  }
  let frames: SampledFrame[]
  try {
    frames = await sampler(videoPath)
  } catch (e) {
    return [uncheckedCheck(placementKey, reasonOf(e))]
  }
  // "Looked and found nothing" needs frames to have been looked at.
  if (frames.length === 0) return [uncheckedCheck(placementKey, 'no frames could be read from the video')]
  const failedAt: number[] = []
  let firstReason = ''
  let read = 0
  for (const frame of frames) {
    let hits: Map<SafeZone['name'], ZoneHit>
    let width: number
    try {
      const dim = readImageDimensions(frame.png)
      if (!dim) throw new Error('the frame image could not be decoded')
      if (zonesFor(dim.width, dim.height).length === 0) break // not 9:16: nothing to check in any frame
      width = dim.width
      hits = new Map((await zoneHits(frame.png, engine, undefined)).map((h) => [h.zone.name, h]))
    } catch (e) {
      failedAt.push(frame.atSeconds)
      firstReason ||= reasonOf(e)
      if (e instanceof OcrUnavailable && e.engineDown && read === 0) break
      continue
    }
    read++
    for (const name of ['top', 'bottom', 'right'] as const) {
      const hit = hits.get(name)
      const run = open.get(name)
      if (run && (!hit || !sameText(run.lastText, hit.text))) close(name)
      if (hit && !open.has(name)) open.set(name, { hit, atSeconds: frame.atSeconds, isCut: frame.isCut, width, lastText: hit.text })
      else if (hit && run) run.lastText = hit.text
    }
  }
  // Open runs are always emitted, however the loop ended.
  for (const name of ['top', 'bottom', 'right']) close(name)
  if (failedAt.length && read === 0) return [uncheckedCheck(placementKey, firstReason)]
  const skipped = (frames as SampledFrames).skipped ?? 0
  if (failedAt.length) {
    checks.push(partialUnchecked(placementKey, `${failedAt.length} frame${failedAt.length === 1 ? '' : 's'} (at ${failedAt.map((t) => `${t.toFixed(1)}s`).join(', ')}; ${firstReason})`))
  }
  if (skipped > 0) checks.push(partialUnchecked(placementKey, `${skipped} frame${skipped === 1 ? '' : 's'} of the video (ffmpeg could not extract ${skipped === 1 ? 'it' : 'them'})`))
  return checks.sort((a, b) => (a.atSeconds ?? 0) - (b.atSeconds ?? 0))
}

/** Whole-image OCR: how many words and lines of text a slide carries (used by pacing). */
export async function ocrSlideWordCount(path: string, engine: OcrEngine = getDefaultEngine()): Promise<{ words: number; lines: number }> {
  const words = await engine.recognizeWords(await readFile(path))
  if (!words.length) return { words: 0, lines: 0 }
  const lines = new Set<number>()
  const sorted = [...words].sort((a, b) => a.box.y - b.box.y)
  let lineTop = -Infinity
  let lineH = 0
  for (const w of sorted) {
    if (w.box.y > lineTop + lineH * 0.6) {
      lineTop = w.box.y
      lineH = w.box.h
      lines.add(lineTop)
    }
  }
  return { words: words.length, lines: lines.size }
}
