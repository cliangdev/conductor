import { mkdir, readFile, stat, writeFile } from 'node:fs/promises'
import { isAbsolute, join, resolve } from 'node:path'
import type { DraftCheck } from '../../lib/creative-render.js'
import type { BrowserFactory } from '../../lib/creative-browser.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import { placementFit } from '../../lib/image-common.js'
import { probeMedia } from '../../lib/media-probe.js'
import { readProvenanceSidecar, writeProvenanceSidecar, type SidecarProvenance } from '../../lib/provenance-sidecar.js'
import { SLIDESHOW_LAYOUT } from '../../lib/slideshow/spec.js'
import {
  firstSecondTextCheck,
  isClipSegment,
  openingMotionChecks,
  overlappingBeatChecks,
  resolveTimeline,
  staticStretchChecks,
  validateVideoSpec,
  type ClipSource,
  type Timeline,
  type VideoSpec,
} from '../../lib/video/timeline.js'
import { renderTextBeats, type RenderedBeats } from '../../lib/video/text-beats.js'
import { extractFrame0, makeHookContactSheet, measureOpeningMotion, renderVideo, type RenderPlanInput } from '../../lib/video/render.js'
import { findProjectRoot, stamp } from './creative-drafts.js'
import { checkClipPixels, hasErrors, type PixelToolOptions } from './safe-zone-pixels.js'

/**
 * compose_video: builds one 1080x1920 hook-first MP4 on this machine from a timeline of trimmed clips and
 * stills with hard cuts, timed text on screen from 0 s and optional music, voice and clip sound. Nothing is
 * sent to Conductor; the MP4 goes to preview_creative_draft({kind: "CLIP", clipPath}) next.
 */

export const VIDEOS_SUBDIR = join('.conductor', 'videos')

/** A source clip or still smaller than this (as a scale factor to fill 1080x1920) is refused; between 1 and this it only warns. */
export const MAX_UPSCALE = 2

export interface ComposeVideoParams {
  spec?: unknown
  draftDir?: string
}

export interface ComposeVideoDeps {
  /** Tests: replace the heavy steps. */
  renderBeats?: typeof renderTextBeats
  render?: (input: RenderPlanInput, log?: (m: string) => void) => Promise<{ hasAudio: boolean }>
  motion?: (videoPath: string) => Promise<number>
  frame0?: typeof extractFrame0
  contactSheet?: typeof makeHookContactSheet
  probeOutput?: (videoPath: string) => Promise<{ durationSeconds?: number; width?: number; height?: number; hasAudio?: boolean }>
}

export interface ComposeVideoOptions {
  projectRoot?: string
  now?: Date
  log?: (message: string) => void
  browserFactory?: BrowserFactory
  /** OCR / sampler overrides for the safe-zone pass (tests). */
  pixel?: PixelToolOptions
  deps?: ComposeVideoDeps
}

export type ComposeVideoToolResult =
  | {
      ok: true
      /** false when a spec-level error stopped the tool before encoding (nothing was rendered). */
      rendered: boolean
      outDir: string
      specPath: string
      videoPath?: string
      frame0Path?: string
      contactSheetPath?: string
      durationSec?: number
      hasAudio?: boolean
      timeline: Array<{ segment: number; kind: 'clip' | 'image'; fromSec: number; toSec: number; transition: 'cut' | 'fade' }>
      checks: DraftCheck[]
      passed: boolean
      provenance: { recorded: boolean; needed: string[]; aiGenerated: boolean }
      nextStep: string
      image?: { data: Buffer; mimeType: string }
    }
  | { ok: false; error: string; errors?: string[] }

const unique = (items: string[]): string[] => [...new Set(items)]
const abs = (root: string, p: string): string => (isAbsolute(p) ? p : resolve(root, p))

/** The check list for a source that has to be upscaled to fill the frame. */
function resolutionCheck(where: string, width: number, height: number): DraftCheck | undefined {
  const scale = placementFit(width, height)['9x16'].scale
  if (scale <= 1) return undefined
  const severity = scale > MAX_UPSCALE ? 'error' : 'warning'
  return {
    placementKey: '9x16',
    rule: 'sourceResolution',
    severity,
    message: `${where} is ${width}x${height}, upscaled ${scale}x to fill 1080x1920${severity === 'error' ? `; more than ${MAX_UPSCALE}x looks soft, use a larger source` : ' (fine for a draft, use 1080p for a final)'}`,
  }
}

async function visualProvenance(paths: string[]): Promise<{ sidecar: SidecarProvenance | null; missing: string[]; anyAi: boolean }> {
  const known: SidecarProvenance[] = []
  const missing: string[] = []
  for (const p of unique(paths)) {
    let found: SidecarProvenance | null = null
    try {
      found = await readProvenanceSidecar(p)
    } catch {
      found = null
    }
    if (found) known.push(found)
    else missing.push(p)
  }
  const anyAi = known.some((k) => k.aiGenerated)
  if (missing.length > 0) return { sidecar: null, missing, anyAi }
  return {
    sidecar: {
      source: `Composed with Conductor's video composer from: ${unique(known.map((k) => k.source)).join('; ')}`,
      licence: unique(known.map((k) => k.licence)).join('; '),
      aiGenerated: anyAi,
    },
    missing,
    anyAi,
  }
}

const fail = (error: string, errors?: string[]): ComposeVideoToolResult => ({ ok: false, error, ...(errors && errors.length ? { errors } : {}) })

export async function composeVideoTool(params: ComposeVideoParams, options: ComposeVideoOptions = {}): Promise<ComposeVideoToolResult> {
  if (params.spec === undefined || params.spec === null) return fail('Pass spec: { segments, texts?, audio? }.')
  const shape = validateVideoSpec(params.spec)
  if (shape.length > 0) return fail('The video spec is invalid.', shape)
  const root = options.projectRoot ?? findProjectRoot()
  const input = params.spec as VideoSpec
  const spec: VideoSpec = {
    ...input,
    segments: input.segments.map((s) => (isClipSegment(s) ? { ...s, clip: abs(root, s.clip) } : { ...s, image: abs(root, s.image) })),
    ...(input.audio?.track ? { audio: { ...input.audio, track: abs(root, input.audio.track) } } : {}),
  }
  if (spec.audio?.voicePath) spec.audio = { ...spec.audio, voicePath: abs(root, spec.audio.voicePath) }
  const texts = spec.texts ?? []
  const deps = options.deps ?? {}

  // Inspect every file the spec names, reporting each problem with its path.
  const fileErrors: string[] = []
  const checks: DraftCheck[] = []
  const sources: Array<ClipSource | undefined> = []
  for (const [i, seg] of spec.segments.entries()) {
    const at = `segments[${i}]`
    const file = isClipSegment(seg) ? seg.clip : seg.image
    try {
      await stat(file)
    } catch {
      fileErrors.push(`${at}.${isClipSegment(seg) ? 'clip' : 'image'}: file not found: ${file}`)
      sources.push(undefined)
      continue
    }
    try {
      if (isClipSegment(seg)) {
        const probe = await probeMedia(file)
        if (probe.kind !== 'VIDEO' || !probe.durationSeconds) throw new Error('not a video file')
        sources.push({ durationSec: probe.durationSeconds, hasAudio: probe.hasAudio === true, ...(probe.width ? { width: probe.width } : {}), ...(probe.height ? { height: probe.height } : {}) })
        const c = probe.width && probe.height ? resolutionCheck(`${at}.clip`, probe.width, probe.height) : undefined
        if (c) checks.push({ ...c, index: i })
      } else {
        const dims = readImageDimensions(await readFile(file))
        if (!dims) throw new Error('not a PNG, JPEG or WebP image')
        sources.push(undefined)
        const c = resolutionCheck(`${at}.image`, dims.width, dims.height)
        if (c) checks.push({ ...c, index: i })
      }
    } catch (err) {
      fileErrors.push(`${at}.${isClipSegment(seg) ? 'clip' : 'image'}: ${err instanceof Error ? err.message : String(err)}: ${file}`)
      sources.push(undefined)
    }
  }
  for (const key of ['track', 'voicePath'] as const) {
    const file = spec.audio?.[key]
    if (!file) continue
    try {
      await stat(file)
      const probe = await probeMedia(file)
      if (probe.kind !== 'AUDIO' && !(probe.kind === 'VIDEO' && probe.hasAudio)) throw new Error('not an audio file')
    } catch (err) {
      fileErrors.push(`audio.${key}: ${err instanceof Error && err.message === 'not an audio file' ? 'not an audio file' : 'file not found or unreadable'}: ${file}`)
    }
  }
  if (fileErrors.length > 0) return fail('The video spec is invalid.', fileErrors)

  const resolved = resolveTimeline(spec, sources)
  if (!resolved.timeline) return fail('The video spec is invalid.', resolved.errors)
  const timeline: Timeline = resolved.timeline

  const id = String(input.id ?? 'video').replace(/[^A-Za-z0-9._-]+/g, '-').slice(0, 40) || 'video'
  const outDir = params.draftDir ? resolve(root, params.draftDir) : join(root, VIDEOS_SUBDIR, `${stamp(options.now ?? new Date())}-${id}`)
  await mkdir(outDir, { recursive: true })
  const specPath = join(outDir, 'spec.json')
  await writeFile(specPath, JSON.stringify({ ...spec, id, layout: SLIDESHOW_LAYOUT }, null, 2))
  const summary = timeline.segments.map((s) => ({ segment: s.index, kind: s.kind, fromSec: s.outStart, toSec: s.outEnd, transition: s.transitionIn }))
  const prov = await visualProvenance(timeline.segments.map((s) => s.path))
  const provenance = { recorded: false, needed: prov.missing, aiGenerated: prov.anyAi }

  // What the spec alone decides: text on screen at 0 s, text that fits the frame, beats that do not collide.
  let beats: RenderedBeats = { beats: [], checks: [] }
  try {
    beats = await (deps.renderBeats ?? renderTextBeats)(texts, outDir, { log: options.log, ...(options.browserFactory ? { browserFactory: options.browserFactory } : {}) })
  } catch (err) {
    return fail(`The text could not be rendered: ${err instanceof Error ? err.message : String(err)}`)
  }
  checks.push(...beats.checks)
  checks.push(...firstSecondTextCheck(texts))
  checks.push(...overlappingBeatChecks(texts.map((b, i) => ({ fromSec: b.fromSec, toSec: b.toSec, top: beats.beats[i]?.top ?? null, bottom: beats.beats[i]?.bottom ?? null }))))
  checks.push(...staticStretchChecks(timeline.totalSec, timeline.cutTimes, texts))

  const stop = (): ComposeVideoToolResult => ({
    ok: true,
    rendered: false,
    outDir,
    specPath,
    timeline: summary,
    checks,
    passed: false,
    provenance,
    nextStep: `Not rendered: fix every check with severity "error" in the spec (${checks.filter((c) => c.severity === 'error').map((c) => c.rule).join(', ')}) and call compose_video again BEFORE showing anything to the person.`,
  })
  if (hasErrors(checks)) return stop()

  const videoPath = join(outDir, `${id}.mp4`)
  let hasAudio: boolean
  try {
    ;({ hasAudio } = await (deps.render ?? renderVideo)(
      {
        timeline,
        texts: texts.map((b) => ({ fromSec: b.fromSec, toSec: b.toSec })),
        textPngs: beats.beats.map((b) => b.png),
        ...(spec.audio?.track ? { musicPath: spec.audio.track } : {}),
        ...(spec.audio?.voicePath ? { voicePath: spec.audio.voicePath } : {}),
        duck: spec.audio?.duck !== false,
        outPath: videoPath,
      },
      options.log,
    ))
  } catch (err) {
    return fail(`The video could not be encoded: ${err instanceof Error ? err.message : String(err)}`)
  }
  options.log?.('encoded; checking the opening and the safe zones')

  const first = timeline.segments[0]!
  try {
    checks.push(...openingMotionChecks(first.kind, await (deps.motion ?? measureOpeningMotion)(videoPath)))
  } catch (err) {
    checks.push({ placementKey: '9x16', rule: 'firstSecondMotion', severity: 'warning', message: `could not measure motion in the opening: ${err instanceof Error ? err.message : String(err)}` })
  }
  const probe = await (deps.probeOutput ?? probeMedia)(videoPath).catch(() => ({} as { durationSeconds?: number }))
  const safe = await checkClipPixels(videoPath, { ...(options.pixel ?? {}), placementKey: '9x16', uiOverlay: 'none', ...(probe.durationSeconds ? { durationSeconds: probe.durationSeconds } : {}) })
  checks.push(...safe.checks)

  const frame0Path = join(outDir, 'frame0.png')
  const contactSheetPath = join(outDir, 'contact-0-3s.jpg')
  let image: { data: Buffer; mimeType: string } | undefined
  const notes: string[] = []
  let frame0Written = false
  try {
    await (deps.frame0 ?? extractFrame0)(videoPath, frame0Path)
    frame0Written = true
    image = await (deps.contactSheet ?? makeHookContactSheet)(videoPath, contactSheetPath)
  } catch (err) {
    notes.push(`Could not write the preview frames: ${err instanceof Error ? err.message : String(err)}. Open ${videoPath} directly.`)
  }
  if (prov.sidecar) {
    await writeProvenanceSidecar(videoPath, prov.sidecar)
    provenance.recorded = true
  }
  const passed = !hasErrors(checks)
  const provNote =
    (prov.missing.length > 0 ? ` ${prov.missing.length} input file${prov.missing.length === 1 ? ' has' : 's have'} no .provenance.json, so the video has none: upload_creative_media will need source and licence.` : '') +
    (prov.anyAi ? ' An input is AI-generated: the video is labelled AI-generated.' : '')
  return {
    ok: true,
    rendered: true,
    outDir,
    specPath,
    videoPath,
    ...(frame0Written ? { frame0Path } : {}),
    ...(image ? { contactSheetPath } : {}),
    ...(probe.durationSeconds ? { durationSec: Math.round(probe.durationSeconds * 1000) / 1000 } : { durationSec: timeline.totalSec }),
    hasAudio,
    timeline: summary,
    checks,
    passed,
    provenance,
    nextStep: passed
      ? `Call preview_creative_draft({kind: "CLIP", clipPath: "${videoPath}"}) for the safe-zone overlay, show the contact sheet and the overlay to the person, and only after they approve call upload_creative_media for the file, then create_creative with kind CLIP and clipMedia ({"9x16": mediaId}). A CLIP has no commit step. Warnings are advisory.${provNote}${notes.length ? ` ${notes.join(' ')}` : ''}`
      : `Fix every check with severity "error" (change the spec, not the file) and call compose_video again BEFORE showing anything to the person.${notes.length ? ` ${notes.join(' ')}` : ''}`,
    ...(image ? { image } : {}),
  }
}
