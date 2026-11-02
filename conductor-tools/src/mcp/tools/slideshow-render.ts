import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { Config } from '../config.js'
import { apiGet, apiPost } from '../api.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import {
  composeSlideshow,
  isMediaRef,
  MEDIA_REF,
  mapSpecImages,
  type ComposeOptions,
  type ComposeResult,
  type SlideRatio,
  type SlideshowCheck,
  type SlideshowSpec,
} from '../../lib/slideshow/index.js'
import { uploadCreativeMedia, type UploadCreativeMediaParams, type UploadCreativeMediaResult } from './creatives.js'

/**
 * `conductor creative render-slides`: the job half of editing a slideshow's text. The backend holds a PENDING
 * revision (the edited spec); this re-composes only the slides whose text changed, at each ratio the Creative has
 * slides for, and hands the new images back. It runs in the render Workflow (image conductor-render, run-scoped key)
 * and on a person's own machine with their own login. Nothing here decides whether the edit applies: the backend
 * does, in /complete.
 */

const creativesBase = (config: Config): string => `/api/v2/projects/${config.projectId}/marketing/creatives`
const photoUrl = (config: Config, id: string): string => `/api/v2/projects/${config.projectId}/marketing/photos/${id}`

/** The ratios a text edit re-renders. Any other list (1x1) is left as it is; the backend notes that on /complete. */
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
  slideStyle?: Record<string, unknown> | null
  /** The Creative's display id (12a), when the backend sends it; used only to label the new media. */
  displayId?: string
}

interface MediaInfo {
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

export interface RenderSlidesOptions {
  /** Tests: stand-ins for the browser compose, the image download and the upload. */
  compose?: (spec: SlideshowSpec, outDir: string, options: ComposeOptions) => Promise<Pick<ComposeResult, 'slidePaths' | 'checks'>>
  download?: (media: MediaInfo, into: string) => Promise<string>
  upload?: (params: UploadCreativeMediaParams, config: Config) => Promise<UploadCreativeMediaResult>
  log?: (message: string) => void
}

export type RenderSlidesResult =
  | { ok: true; outcome: 'completed'; revisionId: string; slides: number[]; ratios: SlideRatio[]; uploaded: number; warnings: number }
  | { ok: false; outcome: 'checks-failed'; revisionId: string; error: string; checks: SlideshowCheck[] }
  | { ok: false; outcome: 'not-pending'; revisionId: string; error: string }

/** The ratio of a stored image by its size: 1080x1920 is 9x16, 1080x1350 is 4x5; anything else is not re-rendered. */
export function ratioOfSize(width: number | null | undefined, height: number | null | undefined): SlideRatio | undefined {
  if (width === 1080 && height === 1920) return '9x16'
  if (width === 1080 && height === 1350) return '4x5'
  return undefined
}

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
    const mediaCache = new Map<string, Promise<MediaInfo>>()
    const mediaInfo = (id: string): Promise<MediaInfo> => {
      let found = mediaCache.get(id)
      if (!found) {
        found = apiGet<MediaInfo>(photoUrl(config, id), config)
        mediaCache.set(id, found)
      }
      return found
    }

    const changed = [...new Set(detail.changedSlides ?? [])].sort((a, b) => a - b)
    const spec = detail.spec
    if (changed.length === 0) throw new Error('The revision names no changed slides.')
    const bad = changed.find((n) => !Number.isInteger(n) || n < 1 || n > (spec?.slides?.length ?? 0))
    if (bad !== undefined) throw new Error(`The revision names slide ${bad}, which its spec does not have.`)

    // Which stored lists are re-rendered, grouped by ratio. A `default` list counts as the ratio of its images;
    // an explicit 9x16 / 4x5 list is preferred as the source of the slide being replaced (for its provenance).
    const lists = Object.entries(detail.slides ?? {}).filter((e): e is [string, string[]] => Array.isArray(e[1]) && e[1].length > 0)
    const replaced = new Map<SlideRatio, string[]>()
    for (const [placement, ids] of [...lists].sort(([a], [b]) => Number(a === 'default') - Number(b === 'default'))) {
      let ratio: SlideRatio | undefined = (RENDERED as string[]).includes(placement) ? (placement as SlideRatio) : undefined
      if (!ratio && placement === 'default') {
        const first = await mediaInfo(ids[0]!)
        ratio = ratioOfSize(first.width, first.height)
      }
      if (!ratio) {
        log(`${placement} list is not 1080x1920 or 1080x1350; left as it is`)
        continue
      }
      if (ids.length < Math.max(...changed)) throw new Error(`The ${placement} list has ${ids.length} slides but the edit changes slide ${Math.max(...changed)}.`)
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
    for (const [ref, id] of refs) local.set(ref, await download(await mediaInfo(id), temp))
    const composable = mapSpecImages(subSpec, (value) => (value && isMediaRef(value) ? local.get(value) : value))

    log(`re-composing slide${changed.length === 1 ? '' : 's'} ${changed.join(', ')} at ${ratios.join(', ')}`)
    const compose = options.compose ?? ((s, out, o) => composeSlideshow(s, out, o))
    const style = (detail.slideStyle && Object.keys(detail.slideStyle).length > 0 ? detail.slideStyle : undefined) as ComposeOptions['style']
    const composed = await compose(composable, join(temp, 'composed'), { ratios, specDir: temp, ...(style ? { style } : {}) })

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

    const upload = options.upload ?? uploadCreativeMedia
    const label = detail.displayId ?? (await apiGet<{ displayId?: string }>(`${creativesBase(config)}/${params.creativeId}`, config)).displayId ?? params.creativeId
    const slideMedia: Record<string, Record<string, string>> = {}
    let uploaded = 0
    for (const ratio of ratios) {
      const files = composed.slidePaths[ratio] ?? []
      if (files.length !== changed.length) throw new Error(`The composer produced ${files.length} ${ratio} slides, expected ${changed.length}.`)
      slideMedia[ratio] = {}
      for (let j = 0; j < changed.length; j++) {
        const slide = changed[j]!
        const old = await mediaInfo(replaced.get(ratio)![slide - 1]!)
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
        const id = result.media['id'] as string | undefined
        if (!id) throw new Error(`Uploading slide ${slide} (${ratio}) returned no media id.`)
        slideMedia[ratio][String(slide)] = id
        uploaded += 1
      }
    }

    await apiPost(`${base}/complete`, { slideMedia, checks }, config)
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
