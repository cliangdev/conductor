import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import { mintPutConfirmImage } from './creatives.js'
import {
  composeSlideshow,
  isLegacyLayout,
  isMediaRef,
  MEDIA_REF,
  mapSpecImages,
  type ComposeOptions,
  type ComposeResult,
  type SlideRatio,
  type SlideshowCheck,
  type SlideshowSpec,
} from '../../lib/slideshow/index.js'

/**
 * `conductor creative render-slides`: the job half of editing a slideshow's text. The backend holds a PENDING
 * revision (the edited spec); this re-composes only the slides whose text changed, at each ratio the Creative has
 * slides for, and hands the new images back. It runs in the render Workflow (image conductor-render, run-scoped key)
 * and on a person's own machine with their own login. Nothing here decides whether the edit applies: the backend
 * does, in /complete.
 */

const creativesBase = (config: Config): string => `/api/v2/projects/${config.projectId}/marketing/creatives`

/** The ratios a text edit re-renders. The backend decides which stored lists get which (`renderLists`). */
const RENDERED: SlideRatio[] = ['9x16', '4x5']

export interface RevisionDetail {
  id: string
  revisionNo?: number
  status?: string
  /** The edited spec, images as `media:<id>`. */
  spec: SlideshowSpec
  baseVersion?: number
  /** 1-based numbers of the slides whose text differs from the Creative's current spec. */
  changedSlides: number[]
  /** The Creative's current stored slides: placement key to media ids, one per slide. */
  slides: Record<string, string[] | null>
  /** The stored lists to re-render and the ratio each is made at: the backend's decision, never inferred here. */
  renderLists?: Array<{ list: string; ratio: SlideRatio }>
  slideStyle?: Record<string, unknown> | null
  /** The Creative's display id (12a), when the backend sends it; used only to label the new media. */
  displayId?: string
  /**
   * Everything the job reads about Media: signed download urls and provenance for the changed slides' images and for
   * the slides being replaced. A run-scoped key cannot read `GET .../photos/{id}`, so nothing else is fetched.
   */
  media?: MediaInfo[]
}

export interface MediaInfo {
  id: string
  url?: string
  width?: number | null
  height?: number | null
  source?: string | null
  licence?: string | null
  aiGenerated?: boolean | null
}

export interface RenderSlidesParams {
  revisionId: string
  creativeId: string
  /** Informational: the run-scoped key already identifies the run to the backend. */
  workflowRunId?: string
}

/** What the job sends for one re-rendered slide. */
export interface SlideUploadParams {
  filePath: string
  label: string
  source?: string
  licence?: string
  aiGenerated?: boolean
}

export interface SlideUploadResult {
  media: Record<string, unknown>
  /** Provenance fields shortened to the API's limit on the way up. */
  warnings?: string[]
}

/** Slides are stored as JPEG at this quality: Instagram feed images and TikTok photo posts refuse PNG. */
export const SLIDE_JPEG_QUALITY = 92

export interface RenderSlidesOptions {
  /** Tests: stand-ins for the browser compose, the image download and the upload. */
  compose?: (spec: SlideshowSpec, outDir: string, options: ComposeOptions) => Promise<Pick<ComposeResult, 'slidePaths' | 'checks'>>
  download?: (media: MediaInfo, into: string) => Promise<string>
  upload?: (params: SlideUploadParams, config: Config) => Promise<SlideUploadResult>
  log?: (message: string) => void
}

export type RenderSlidesResult =
  | { ok: true; outcome: 'completed'; revisionId: string; slides: number[]; ratios: SlideRatio[]; uploaded: number; warnings: number }
  | { ok: false; outcome: 'checks-failed'; revisionId: string; error: string; checks: SlideshowCheck[] }
  | { ok: false; outcome: 'not-pending'; revisionId: string; error: string }
  | { ok: false; outcome: 'rejected'; revisionId: string; error: string }

async function downloadMedia(media: MediaInfo, into: string): Promise<string> {
  if (!media.url) throw new Error(`Media ${media.id} has no download url.`)
  const response = await fetch(media.url)
  if (!response.ok) throw new Error(`Downloading media ${media.id} failed: HTTP ${response.status}`)
  const bytes = new Uint8Array(await response.arrayBuffer())
  // The browser takes an image file's type from its extension first, so keep a real one.
  const EXT: Record<string, string> = { 'image/png': 'png', 'image/jpeg': 'jpg', 'image/webp': 'webp', 'image/gif': 'gif' }
  const ext = EXT[readImageDimensions(bytes)?.contentType ?? ''] ?? 'img'
  const file = join(into, `${media.id}.${ext}`)
  await writeFile(file, bytes)
  return file
}

/**
 * Uploads one composed slide through the revision's own media endpoints (the photo endpoints refuse a run-scoped
 * key): mint, PUT the bytes, confirm. The slide must be a JPEG, the format every stored slide has; a PNG here would
 * fail publishing to TikTok photo posts later, so it is refused now.
 */
export async function uploadRevisionSlide(revisionBase: string, params: SlideUploadParams, config: Config): Promise<SlideUploadResult> {
  const bytes = new Uint8Array(await readFile(params.filePath))
  const info = readImageDimensions(bytes)
  if (!info || info.contentType !== 'image/jpeg') {
    throw new Error(`The composed slide ${params.filePath} is ${info?.contentType ?? 'not an image'}, not image/jpeg; slides are stored as JPEG.`)
  }
  const warnings: string[] = []
  const media = await mintPutConfirmImage(
    bytes,
    info,
    { label: params.label, source: params.source, licence: params.licence, aiGenerated: params.aiGenerated },
    { mint: `${revisionBase}/media`, confirm: (id) => `${revisionBase}/media/${id}/confirm` },
    config,
    warnings
  )
  return { media, warnings }
}

function summarise(checks: SlideshowCheck[]): string {
  const errors = checks.filter((c) => c.severity === 'error')
  const shown = errors.slice(0, 5).map((c) => `slide ${c.slide} ${c.ratio} ${c.rule}: ${c.message}`)
  return `${errors.length} layout check${errors.length === 1 ? '' : 's'} failed; nothing was changed. ${shown.join(' | ')}${errors.length > 5 ? ' | ...' : ''}`
}

export async function renderSlides(params: RenderSlidesParams, config: Config, options: RenderSlidesOptions = {}): Promise<RenderSlidesResult> {
  const log = options.log ?? (() => {})
  const base = `${creativesBase(config)}/${params.creativeId}/revisions/${params.revisionId}`
  const detail = await apiGet<RevisionDetail>(base, config)
  if (detail.status && detail.status !== 'PENDING') {
    return { ok: false, outcome: 'not-pending', revisionId: params.revisionId, error: `Revision ${params.revisionId} is ${detail.status}, not PENDING; nothing to render.` }
  }

  const temp = await mkdtemp(join(tmpdir(), 'conductor-render-slides-'))
  try {
    // The revision detail carries every Media this job reads; a run-scoped key may not call the photo endpoints.
    if (!Array.isArray(detail.media)) throw new Error("The backend did not send the revision's media (media); it needs updating.")
    const mediaById = new Map(detail.media.map((m) => [m.id, m]))
    const mediaInfo = (id: string): MediaInfo => {
      const found = mediaById.get(id)
      if (!found) throw new Error(`The revision detail did not include media ${id}.`)
      return found
    }

    const changed = [...new Set(detail.changedSlides ?? [])].sort((a, b) => a - b)
    const spec = detail.spec
    if (changed.length === 0) throw new Error('The revision names no changed slides.')
    const bad = changed.find((n) => !Number.isInteger(n) || n < 1 || n > (spec?.slides?.length ?? 0))
    if (bad !== undefined) throw new Error(`The revision names slide ${bad}, which its spec does not have.`)

    // Which stored lists are re-rendered, grouped by ratio: exactly the backend's `renderLists` (it classifies a
    // `default` list by its images' shape, with a tolerance, and refused the edit if any list is not 9x16 / 4x5).
    // `replaced` is the list whose slide is being replaced at each ratio, the source of the new media's provenance;
    // a list named for its ratio is preferred over a `default` one.
    if (!Array.isArray(detail.renderLists)) throw new Error('The backend did not say which slide lists to re-render (renderLists); it needs updating.')
    const replaced = new Map<SlideRatio, string[]>()
    const ordered = [...detail.renderLists].sort((a, b) => Number(a.list === 'default') - Number(b.list === 'default'))
    for (const { list, ratio } of ordered) {
      if (!(RENDERED as string[]).includes(ratio)) throw new Error(`The backend asked for ${list} to be re-rendered at ${ratio}, which this job cannot make.`)
      const ids = detail.slides?.[list]
      if (!Array.isArray(ids) || ids.length === 0) throw new Error(`The revision names the ${list} list, which the Creative does not have.`)
      if (ids.length < Math.max(...changed)) throw new Error(`The ${list} list has ${ids.length} slides but the edit changes slide ${Math.max(...changed)}.`)
      if (!replaced.has(ratio)) replaced.set(ratio, ids)
    }
    const ratios = RENDERED.filter((r) => replaced.has(r))
    if (ratios.length === 0) throw new Error('This Creative has no 9x16 or 4x5 slides to re-render.')

    // Only the changed slides, with only the images they use, as files on this machine.
    const subSpec: SlideshowSpec = { ...spec, slides: changed.map((n) => spec.slides[n - 1]!) }
    const refs = new Map<string, string>()
    mapSpecImages(subSpec, (value) => {
      const m = value ? MEDIA_REF.exec(value) : null
      if (m) refs.set(value!, m[1]!)
      return value
    })
    const download = options.download ?? downloadMedia
    const local = new Map<string, string>()
    for (const [ref, id] of refs) local.set(ref, await download(mediaInfo(id), temp))
    const composable = mapSpecImages(subSpec, (value) => (value && isMediaRef(value) ? local.get(value) : value))

    log(`re-composing slide${changed.length === 1 ? '' : 's'} ${changed.join(', ')} at ${ratios.join(', ')}`)
    const compose = options.compose ?? ((s, out, o) => composeSlideshow(s, out, o))
    const style = (detail.slideStyle && Object.keys(detail.slideStyle).length > 0 ? detail.slideStyle : undefined) as ComposeOptions['style']
    const composed = await compose(composable, join(temp, 'composed'), {
      ratios,
      specDir: temp,
      format: 'jpeg',
      jpegQuality: SLIDE_JPEG_QUALITY,
      // a spec stored before the safe-zone bands grew re-renders with the old layout, and top/right intrusions warn
      lenientSafeZone: isLegacyLayout(spec),
      ...(style ? { style } : {}),
    })

    // The composer numbers the slides it was given 1..n; the Creative's slides are numbered by the full spec. The
    // provenance warning is about committing a draft from local files, which this is not.
    const checks = composed.checks
      .filter((c) => c.rule !== 'provenance')
      .map((c) => ({ ...c, slide: changed[c.slide - 1] ?? c.slide }))
    if (checks.some((c) => c.severity === 'error')) {
      const error = summarise(checks)
      await apiPost(`${base}/fail`, { error, checks }, config)
      return { ok: false, outcome: 'checks-failed', revisionId: params.revisionId, error, checks }
    }

    const upload = options.upload ?? ((p: SlideUploadParams, c: Config) => uploadRevisionSlide(base, p, c))
    const label = detail.displayId ?? (await apiGet<{ displayId?: string }>(`${creativesBase(config)}/${params.creativeId}`, config)).displayId ?? params.creativeId
    const slideMedia: Record<string, Record<string, string>> = {}
    let uploaded = 0
    for (const ratio of ratios) {
      const files = composed.slidePaths[ratio] ?? []
      if (files.length !== changed.length) throw new Error(`The composer produced ${files.length} ${ratio} slides, expected ${changed.length}.`)
      slideMedia[ratio] = {}
      for (let j = 0; j < changed.length; j++) {
        const slide = changed[j]!
        const old = mediaInfo(replaced.get(ratio)![slide - 1]!)
        const result = await upload(
          {
            filePath: files[j]!,
            label: `${label} slide ${slide} ${ratio} r${detail.revisionNo ?? '?'}`,
            ...(old.source ? { source: old.source } : {}),
            ...(old.licence ? { licence: old.licence } : {}),
            aiGenerated: !!old.aiGenerated,
          },
          config
        )
        for (const w of result.warnings ?? []) log(w)
        const id = result.media['id'] as string | undefined
        if (!id) throw new Error(`Uploading slide ${slide} (${ratio}) returned no media id.`)
        slideMedia[ratio][String(slide)] = id
        uploaded += 1
      }
    }

    // A 200 can still carry a FAILED revision (the backend refused the slides, or the Creative changed meanwhile).
    const settled = await apiPost<{ status?: string; error?: string | null }>(`${base}/complete`, { slideMedia, checks }, config)
    if (settled?.status === 'FAILED') {
      return { ok: false, outcome: 'rejected', revisionId: params.revisionId, error: settled.error || `Revision ${params.revisionId} was not applied.` }
    }
    return { ok: true, outcome: 'completed', revisionId: params.revisionId, slides: changed, ratios, uploaded, warnings: checks.length }
  } catch (err) {
    // The backend still holds the revision PENDING; say why it will not finish, then let the caller exit non-zero.
    const message = err instanceof Error ? err.message : String(err)
    await apiPost(`${base}/fail`, { error: message.slice(0, 1000) }, config).catch(() => {})
    throw err
  } finally {
    await rm(temp, { recursive: true, force: true }).catch(() => {})
  }
}
