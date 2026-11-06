import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { DraftCheck } from '../../lib/creative-render.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import {
  checkSlideImages,
  checkVideoFrames,
  ocrSlideWordCount,
  uncheckedCheck,
} from '../../lib/safe-zone/check.js'
import { sampleVideoFrames, type SampledFrame } from '../../lib/safe-zone/frames.js'
import type { OcrEngine } from '../../lib/safe-zone/ocr.js'
import { overlaySheetsFor, type OverlayFrame, type OverlayPlatform } from '../../lib/safe-zone/overlay.js'
import {
  CROSSFADE_SEC,
  isDetailSlide,
  pacingWarnings,
  paceSlides,
  slideTextFromAltText,
  totalSeconds,
  type PacingSlide,
} from '../../lib/slideshow-pacing.js'

/**
 * Shared by preview_creative_draft, preview_creative, attach_creative_to_post, render_creative and
 * create_creative/update_creative: the finished-pixel safe-zone check, the platform-UI overlay sheets
 * and the reading-based slide pacing. Tests inject fakes through PixelToolOptions so neither tesseract
 * nor a browser is needed.
 */

/** The largest push-in of the slideshow's Ken Burns zoom (conductor-creative/job/media.mjs: z = 1 + 0.06 x progress). */
export const SLIDESHOW_ZOOM_MAX = 1.06

export const MAX_INLINE_BYTES = 1_000_000
const OVERLAY_SLIDES_PER_SHEET = 10

export type UiOverlay = 'instagram' | 'tiktok' | 'both' | 'none'
export interface OverlaySheet {
  platform: OverlayPlatform
  file: string
  placementKey?: string
}

export type OverlayWriter = (
  frames: OverlayFrame[],
  platforms: OverlayPlatform[],
  outDir: string,
  baseName: string,
  opts?: { maxPerSheet?: number },
) => Promise<Array<{ platform: OverlayPlatform; file: string; bytes?: number }>>

export interface PixelToolOptions {
  /** OCR engine override (tests). Default: the shared tesseract worker. */
  ocrEngine?: OcrEngine
  /** Video frame sampler override (tests). Default: ffmpeg. */
  sampler?: (videoPath: string) => Promise<SampledFrame[]>
  /** Overlay sheet writer override (tests). Default: Playwright. */
  overlay?: OverlayWriter
  /** fetch override for downloading a saved Creative's media (tests). */
  fetchImpl?: typeof fetch
}

export const hasErrors = (checks: DraftCheck[]): boolean => checks.some((c) => c.severity === 'error')

export function platformsFor(ui: UiOverlay | undefined): OverlayPlatform[] {
  if (ui === 'none') return []
  if (ui === 'instagram') return ['instagram']
  if (ui === 'tiktok') return ['tiktok']
  return ['instagram', 'tiktok']
}

function isNineBySixteen(png: Buffer): boolean {
  const d = readImageDimensions(png)
  return !!d && Math.abs(d.width / d.height - 9 / 16) < 0.01
}

/** Writes the overlay sheets; never throws (no browser is a note, not a failure). */
export async function writeOverlaySheets(
  frames: OverlayFrame[],
  platforms: OverlayPlatform[],
  outDir: string,
  baseName: string,
  placementKey: string,
  opts: PixelToolOptions,
): Promise<{ sheets: OverlaySheet[]; note?: string }> {
  if (!platforms.length || !frames.length) return { sheets: [] }
  try {
    const writer: OverlayWriter = opts.overlay ?? overlaySheetsFor
    const written = await writer(frames, platforms, outDir, baseName, { maxPerSheet: OVERLAY_SLIDES_PER_SHEET })
    return { sheets: written.map((w) => ({ platform: w.platform, file: w.file, placementKey })) }
  } catch (e) {
    return { sheets: [], note: `${placementKey}: could not draw the platform UI overlay (${e instanceof Error ? e.message : String(e)}).` }
  }
}

/** The one image to inline: the Instagram sheet (else the first), unless it is too large. */
export async function inlineOverlay(sheets: OverlaySheet[]): Promise<{ image?: { data: Buffer; mimeType: string }; note?: string }> {
  const pick = sheets.find((s) => s.platform === 'instagram') ?? sheets[0]
  if (!pick) return {}
  try {
    const data = await readFile(pick.file)
    if (data.byteLength > MAX_INLINE_BYTES) return { note: `The UI overlay sheet is larger than this tool can inline (~1 MB) — open ${pick.file} directly.` }
    return { image: { data, mimeType: 'image/jpeg' } }
  } catch {
    return {}
  }
}

// --- Slides ------------------------------------------------------------------------

export interface SlideInput {
  /** Local file, when the slide is on this machine. */
  path?: string
  /** Position in the slide list (0-based). */
  index: number
}

export interface SlidePixelResult {
  checks: DraftCheck[]
  overlaySheets: OverlaySheet[]
  notes: string[]
}

/** Checks the 9:16 slides that are files on this machine, and draws the overlay for them. */
export async function checkSlidesPixels(
  slides: SlideInput[],
  opts: PixelToolOptions & { placementKey?: string; zoom?: boolean; outDir?: string; baseName?: string; uiOverlay?: UiOverlay },
): Promise<SlidePixelResult> {
  const placementKey = opts.placementKey ?? '9x16'
  const notes: string[] = []
  const local = slides.filter((s): s is SlideInput & { path: string } => !!s.path)
  const missing = slides.filter((s) => !s.path)
  const checks = local.length
    ? await checkSlideImages(local.map((s) => ({ path: s.path, index: s.index })), {
        placementKey,
        ...(opts.zoom ? { zoom: { maxScale: SLIDESHOW_ZOOM_MAX } } : {}),
        ...(opts.ocrEngine ? { engine: opts.ocrEngine } : {}),
      })
    : []
  if (missing.length) {
    checks.push(
      uncheckedCheck(
        placementKey,
        `slide${missing.length === 1 ? '' : 's'} ${missing.map((m) => m.index + 1).join(', ')} ${missing.length === 1 ? 'is' : 'are'} existing media, not a file on this machine`,
      ),
    )
  }
  let overlaySheets: OverlaySheet[] = []
  const platforms = platformsFor(opts.uiOverlay)
  if (opts.outDir && platforms.length && local.length) {
    const frames: OverlayFrame[] = []
    for (const s of local) {
      try {
        const bytes = await readFile(s.path)
        if (isNineBySixteen(bytes)) frames.push({ png: bytes, label: `Slide ${s.index + 1}` })
      } catch {
        /* the check already reported the unreadable file */
      }
    }
    const written = await writeOverlaySheets(frames, platforms, opts.outDir, opts.baseName ?? `overlay-${placementKey}`, placementKey, opts)
    overlaySheets = written.sheets
    if (written.note) notes.push(written.note)
    if (frames.length > OVERLAY_SLIDES_PER_SHEET && written.sheets.length) {
      notes.push(`The UI overlay shows the first ${OVERLAY_SLIDES_PER_SHEET} of ${frames.length} slides.`)
    }
  }
  return { checks, overlaySheets, notes }
}

// --- Clips -------------------------------------------------------------------------

const EARLY_TARGETS = [0.5, 1.5, 2.5]
const MAX_OVERLAY_FRAMES = 10

/** Frames for the overlay sheet: the ones nearest 0.5, 1.5 and 2.5 s, plus the first frame after each cut. */
export function clipOverlayFrames(sampled: SampledFrame[]): OverlayFrame[] {
  const picked = new Map<number, { frame: SampledFrame; label: string; cut: boolean }>()
  for (const target of EARLY_TARGETS) {
    let best: SampledFrame | undefined
    for (const f of sampled) if (!best || Math.abs(f.atSeconds - target) < Math.abs(best.atSeconds - target)) best = f
    if (best && Math.abs(best.atSeconds - target) <= 0.5 && !picked.has(best.atSeconds)) {
      picked.set(best.atSeconds, { frame: best, label: `${target} s`, cut: false })
    }
  }
  const cuts = sampled.filter((f) => f.isCut)
  for (const f of cuts) picked.set(f.atSeconds, { frame: f, label: `Cut at ${f.atSeconds.toFixed(1)} s`, cut: true })
  const all = [...picked.values()].sort((a, b) => a.frame.atSeconds - b.frame.atSeconds)
  // Over the cap, keep the early frames and the earliest cuts.
  const keep = all.length > MAX_OVERLAY_FRAMES ? [...all.filter((p) => !p.cut), ...all.filter((p) => p.cut)].slice(0, MAX_OVERLAY_FRAMES).sort((a, b) => a.frame.atSeconds - b.frame.atSeconds) : all
  return keep.map((p) => ({ png: p.frame.png, label: p.label }))
}

export interface ClipPixelResult {
  checks: DraftCheck[]
  overlaySheets: OverlaySheet[]
  notes: string[]
}

/** Checks one 9:16 video for text under the platform UI and draws the overlay from its early frames and cuts. */
export async function checkClipPixels(
  videoPath: string,
  opts: PixelToolOptions & { placementKey?: string; outDir?: string; baseName?: string; uiOverlay?: UiOverlay },
): Promise<ClipPixelResult> {
  const placementKey = opts.placementKey ?? '9x16'
  const notes: string[] = []
  let sampled: SampledFrame[] = []
  const base = opts.sampler ?? ((p: string) => sampleVideoFrames(p))
  const checks = await checkVideoFrames(videoPath, {
    placementKey,
    ...(opts.ocrEngine ? { engine: opts.ocrEngine } : {}),
    sampler: async (p) => {
      sampled = await base(p)
      return sampled
    },
  })
  let overlaySheets: OverlaySheet[] = []
  const platforms = platformsFor(opts.uiOverlay)
  if (opts.outDir && platforms.length && sampled.length) {
    const frames = clipOverlayFrames(sampled.filter((f) => isNineBySixteen(f.png)))
    const written = await writeOverlaySheets(frames, platforms, opts.outDir, opts.baseName ?? `overlay-${placementKey}`, placementKey, opts)
    overlaySheets = written.sheets
    if (written.note) notes.push(written.note)
  }
  return { checks, overlaySheets, notes }
}

// --- Saved Creatives ---------------------------------------------------------------

export interface SavedFrame {
  placementKey: string
  sequenceIndex?: number | null
  url?: string
  durationSeconds?: number
  contentType?: string
}
export interface SavedRender {
  id: string
  state: string
  creativeVersion?: number
  previewOnly?: boolean
  frames?: SavedFrame[]
}

const isVideoFrame = (f: SavedFrame): boolean => f.durationSeconds != null || (f.contentType ?? '').startsWith('video/')

/** The render whose frames to check: the named one, else the latest SUCCEEDED full render (of `version` when known). */
export function pickRender(renders: SavedRender[], renderId?: string, version?: number): SavedRender | undefined {
  if (renderId) return renders.find((r) => r.id === renderId)
  return renders.find((r) => r.state === 'SUCCEEDED' && !r.previewOnly && (version === undefined || r.creativeVersion === undefined || r.creativeVersion === version))
}

export interface SavedPixelResult {
  checks: DraftCheck[]
  passed: boolean
  overlaySheets: OverlaySheet[]
  notes: string[]
}

/**
 * Downloads a saved CAROUSEL's 9x16 slides or CLIP's 9x16 video from a render's signed URLs and runs the
 * same pixel check as the draft. Returns undefined when there is nothing to check (another kind, no 9x16
 * frames, or no render yet), with the reason in `skipped`.
 */
export async function checkSavedCreativePixels(
  creative: { kind?: string; audio?: { source?: string } | null; slideshow?: { zoom?: boolean } | null },
  render: SavedRender | undefined,
  opts: PixelToolOptions & { writeOverlay?: boolean; uiOverlay?: UiOverlay; outDir?: string },
): Promise<{ result?: SavedPixelResult; skipped?: string }> {
  const kind = creative.kind
  if (kind !== 'CAROUSEL' && kind !== 'CLIP') return { skipped: 'not a CAROUSEL or CLIP' }
  if (!render) return { skipped: 'no full render yet — call render_creative first' }
  const frames = (render.frames ?? []).filter((f) => f.placementKey === '9x16' && f.url)
  const wanted =
    kind === 'CAROUSEL'
      ? frames.filter((f) => !isVideoFrame(f)).sort((a, b) => (a.sequenceIndex ?? 0) - (b.sequenceIndex ?? 0))
      : frames.filter(isVideoFrame).slice(0, 1)
  if (!wanted.length) return { skipped: 'no 9x16 placement to check' }

  const fetchImpl = opts.fetchImpl ?? fetch
  const outDir = opts.outDir ?? (opts.writeOverlay ? await mkdtemp(join(tmpdir(), 'conductor-safezone-')) : undefined)
  const work = await mkdtemp(join(tmpdir(), 'conductor-safezone-media-'))
  try {
    const files: Array<{ path: string; index: number }> = []
    for (const [i, f] of wanted.entries()) {
      const res = await fetchImpl(f.url!)
      if (!res.ok) throw new Error(`could not download ${f.placementKey} ${kind === 'CLIP' ? 'video' : `slide ${i + 1}`} (HTTP ${res.status})`)
      const path = join(work, kind === 'CLIP' ? 'clip.mp4' : `slide-${i + 1}.${(f.contentType ?? '').includes('png') ? 'png' : 'jpg'}`)
      await writeFile(path, Buffer.from(await res.arrayBuffer()))
      files.push({ path, index: kind === 'CAROUSEL' ? (f.sequenceIndex ?? i) : 0 })
    }
    const common = { ...opts, ...(opts.writeOverlay && outDir ? { outDir } : {}), uiOverlay: opts.uiOverlay }
    const zoom = kind === 'CAROUSEL' && creative.audio?.source === 'track' && creative.slideshow?.zoom !== false
    const r =
      kind === 'CAROUSEL'
        ? await checkSlidesPixels(files, { ...common, zoom })
        : await checkClipPixels(files[0]!.path, common)
    return { result: { checks: r.checks, passed: !hasErrors(r.checks), overlaySheets: r.overlaySheets, notes: r.notes } }
  } catch (e) {
    const checks = [uncheckedCheck('9x16', e instanceof Error ? e.message : String(e))]
    return { result: { checks, passed: true, overlaySheets: [], notes: [] } }
  } finally {
    await rm(work, { recursive: true, force: true }).catch(() => {})
  }
}

/** A short message listing the failing checks, for a refusal. */
export function describeChecks(checks: DraftCheck[]): string {
  return checks.map((c) => `${c.placementKey}${c.index !== undefined ? `[${c.index}]` : ''}: ${c.message}`).join('; ')
}

/** Makes sure a directory exists. */
export async function ensureDir(dir: string): Promise<void> {
  await mkdir(dir, { recursive: true })
}

// --- Pacing ------------------------------------------------------------------------

export interface PacingResult {
  slideDurations: number[]
  totalSeconds: number
  basis: 'altText' | 'ocr' | 'mixed' | 'none'
  /** True when the tool computed the durations; false when the caller passed them. */
  computed: boolean
  warnings: string[]
}

const TEXT_NONE = /\btext\s*:\s*none\b/i

/**
 * Per-slide durations from the on-slide words. A slide's words come from the "Text:" part of its alt text
 * (`Text: none` means no words); without one, from OCR of the local slide when `ocr` is allowed.
 * When `given` is passed the durations are kept and only the warnings are computed.
 */
export async function pacingFor(input: {
  count: number
  altText?: string[]
  /** Local file per slide index, when it is on this machine. */
  paths?: Array<string | undefined>
  ocr?: boolean
  engine?: OcrEngine
  given?: number[]
}): Promise<PacingResult> {
  const slides: PacingSlide[] = []
  const bases = new Set<'altText' | 'ocr' | 'none'>()
  for (let i = 0; i < input.count; i++) {
    const alt = input.altText?.[i]
    const slide: PacingSlide = { ...(alt ? { altText: alt } : {}) }
    if (alt && TEXT_NONE.test(alt)) {
      slide.words = 0
      slide.lines = 0
      bases.add('altText')
    } else if (slideTextFromAltText(alt)) bases.add('altText')
    else if (input.ocr && input.paths?.[i]) {
      try {
        const counted = await ocrSlideWordCount(input.paths[i]!, input.engine)
        slide.words = counted.words
        slide.lines = counted.lines
        bases.add('ocr')
      } catch {
        bases.add('none')
      }
    } else bases.add('none')
    slides.push(slide)
  }
  const basis = bases.size === 1 ? [...bases][0]! : 'mixed'
  if (input.given) {
    return {
      slideDurations: input.given,
      totalSeconds: totalSeconds(input.given),
      basis,
      computed: false,
      warnings: pacingWarnings(input.given, slides),
    }
  }
  const paced = paceSlides(slides)
  return { slideDurations: paced.slideDurations, totalSeconds: paced.totalSeconds, basis, computed: true, warnings: [] }
}

/** True when a slideshow object already carries explicit durations. */
export function givenDurations(slideshow: unknown): number[] | undefined {
  const d = (slideshow as { slideDurations?: unknown } | undefined)?.slideDurations
  return Array.isArray(d) && d.length && d.every((n) => typeof n === 'number') ? (d as number[]) : undefined
}

/** True when the audio of a CAROUSEL is a library or local track. */
export function hasTrack(audio: unknown): boolean {
  const a = audio as { source?: string } | undefined
  return a?.source === 'track'
}

export { CROSSFADE_SEC, isDetailSlide, totalSeconds }
