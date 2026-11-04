import { execFile } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { basename, dirname, isAbsolute, join, resolve } from 'node:path'
import { promisify } from 'node:util'
import type { Config } from '../config.js'
import { apiGet } from '../api.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import { resolveFfmpegPath } from '../../lib/media-probe.js'
import { readProvenanceSidecar, type SidecarProvenance } from '../../lib/provenance-sidecar.js'
import {
  composeSlideshow,
  isMediaRef,
  listTextFields,
  mapSpecImages,
  mediaRef,
  prepareSpecForCreative,
  specLocalImages,
  type ComposeOptions,
  type ComposeResult,
  type SlideRatio,
  type SlideshowSpec,
} from '../../lib/slideshow/index.js'
import { updateCreative, uploadCreativeMedia } from './creatives.js'

/**
 * A slideshow spec travels with its CAROUSEL Creative (so its text can be edited later). This file holds the three
 * pieces that move it: picking the spec at preview time, uploading its images at commit time, and the backfill
 * tool for Creatives that were committed before specs were kept (attach_slideshow_spec).
 */

const creativesBase = (config: Config): string => `/api/v2/projects/${config.projectId}/marketing/creatives`

/** Slides are only ever composed at these two ratios; other placements cannot be re-composed and compared. */
const COMPOSE_RATIOS: Record<string, SlideRatio> = { '9x16': '9x16', '4x5': '4x5' }

// --- Preview time ---------------------------------------------------------------

export interface DraftSpecChoice {
  /** The spec to keep in the draft (images absolute, no resolvedStyle), absent when none applies. */
  spec?: SlideshowSpec
  path?: string
  /** Why a spec was dropped or ignored, for the preview's note. Empty when all is well. */
  notes: string[]
  /** A failure to report instead of a draft: an explicit slideshowSpecPath that cannot be used. */
  error?: string
}

async function readSpecJson(file: string): Promise<{ ok: true; raw: unknown } | { ok: false; error: string }> {
  try {
    return { ok: true, raw: JSON.parse(await readFile(file, 'utf8')) }
  } catch (err) {
    return { ok: false, error: `Could not read ${file}: ${err instanceof Error ? err.message : String(err)}` }
  }
}

/**
 * Picks the slideshow spec for a CAROUSEL draft. An explicit `slideshowSpecPath` must be usable (the person asked
 * for it); otherwise a `spec.json` beside the slide files is used when it is the one compose_slideshow wrote for them
 * (its id prefixes the file names). A spec whose slide count differs from a placement's list is dropped with a note,
 * never failing the draft: the slides themselves still go through.
 */
export async function chooseDraftSpec(input: {
  slideshowSpecPath?: string
  /** Absolute slide file paths from `slidePaths`. */
  slideFiles: string[]
  /** The request's `slides` lists (local:<key> or media ids): their lengths must match the spec. */
  slideLists: Record<string, string[]>
  projectRoot: string
}): Promise<DraftSpecChoice> {
  const notes: string[] = []
  let file: string | undefined
  let explicit = false
  if (input.slideshowSpecPath) {
    explicit = true
    file = isAbsolute(input.slideshowSpecPath) ? input.slideshowSpecPath : resolve(input.projectRoot, input.slideshowSpecPath)
  } else {
    const dirs = [...new Set(input.slideFiles.map((f) => dirname(f)))]
    const found: Array<{ file: string; id: unknown }> = []
    for (const dir of dirs) {
      const candidate = join(dir, 'spec.json')
      if (!existsSync(candidate)) continue
      const read = await readSpecJson(candidate)
      const id = read.ok && typeof read.raw === 'object' && read.raw !== null ? (read.raw as { id?: unknown }).id : undefined
      const ours = input.slideFiles.filter((f) => dirname(f) === dir)
      if (typeof id === 'string' && ours.every((f) => basename(f).startsWith(`${id}-`))) found.push({ file: candidate, id })
    }
    if (found.length === 0) return { notes }
    if (found.length > 1) return { notes: [`Several spec.json files sit beside the slides (${found.map((f) => f.file).join(', ')}); none was kept. Pass slideshowSpecPath to choose one.`] }
    file = found[0]!.file
  }

  const read = await readSpecJson(file)
  if (!read.ok) return explicit ? { notes, error: read.error } : { notes: [`${read.error}; the slideshow spec was not kept.`] }
  const prepared = prepareSpecForCreative(read.raw, dirname(file))
  if (!prepared.ok) {
    const message = `${file} is not a usable slideshow spec: ${prepared.errors.join('; ')}`
    return explicit ? { notes, error: message } : { notes: [`${message}. It was not kept.`] }
  }
  const missing = specLocalImages(prepared.spec).filter((p) => !existsSync(p))
  if (missing.length > 0) {
    const message = `The slideshow spec ${file} names image files that are not on this machine (${missing.slice(0, 3).join(', ')}${missing.length > 3 ? ', ...' : ''})`
    return explicit ? { notes, error: message } : { notes: [`${message}; it was not kept.`] }
  }
  const count = prepared.spec.slides.length
  const mismatch = Object.entries(input.slideLists).filter(([, list]) => list.length > 0 && list.length !== count)
  if (mismatch.length > 0) {
    return {
      notes: [`The slideshow spec at ${file} has ${count} slides but ${mismatch.map(([p, l]) => `${p} has ${l.length}`).join(', ')}; the spec was not kept (the slides are unchanged). Re-compose with the same spec, or fix slidePaths.`],
    }
  }
  return { spec: prepared.spec, path: file, notes }
}

// --- Commit time ----------------------------------------------------------------

export interface UploadSpecImagesOptions {
  /** Used for an image that has no `.provenance.json` of its own. */
  fallback: SidecarProvenance | { source?: string; licence?: string; aiGenerated?: boolean }
  /** absolute path -> media id, from an earlier attempt; updated as uploads land. */
  done: Record<string, string>
  /** Called after each upload so the caller can persist `done`. */
  onUploaded?: () => Promise<void>
}

/**
 * Uploads every image file of the spec once (deduped by absolute path), honouring each file's own sidecar like the
 * slides do, and returns a copy of the spec with every image rewritten to `media:<id>`. Already-`media:` refs pass.
 */
export async function uploadSpecImages(
  spec: SlideshowSpec,
  config: Config,
  options: UploadSpecImagesOptions
): Promise<{ spec: SlideshowSpec; uploaded: number; warnings: string[] }> {
  const warnings: string[] = []
  let uploaded = 0
  for (const path of specLocalImages(spec)) {
    if (options.done[path]) continue
    const own = await readProvenanceSidecar(path)
    const result = await uploadCreativeMedia(own ? { filePath: path, ...own } : { filePath: path, ...options.fallback }, config)
    const id = result.media['id'] as string | undefined
    if (!id) throw new Error(`Uploading ${basename(path)} returned no media id.`)
    options.done[path] = id
    uploaded += 1
    warnings.push(...result.warnings)
    await options.onUploaded?.()
  }
  const rewritten = mapSpecImages(spec, (value) => {
    if (!value || isMediaRef(value)) return value
    const id = options.done[value]
    if (!id) throw new Error(`The slideshow spec names ${value} but no upload was made for it.`)
    return mediaRef(id)
  })
  delete (rewritten as { resolvedStyle?: unknown }).resolvedStyle
  return { spec: rewritten, uploaded, warnings }
}

// --- Image similarity -----------------------------------------------------------

const execFileAsync = promisify(execFile)

/** Tiles the similarity is measured over: a text edit changes a few tiles a lot, which a whole-frame score would average away. */
const TILE_COLS = 6
const TILE_ROWS = 8

/** The ffmpeg filter graph that scores `TILE_COLS x TILE_ROWS` tiles of the two inputs (all scaled to `size`) with SSIM. */
export function similarityFilter(size: { width: number; height: number }): string {
  const { width, height } = size
  const tw = Math.floor(width / TILE_COLS)
  const th = Math.floor(height / TILE_ROWS)
  const n = TILE_COLS * TILE_ROWS
  const labels = (prefix: string) => Array.from({ length: n }, (_, i) => `[${prefix}${i}]`).join('')
  const parts = [
    `[0:v]scale=${width}:${height}:flags=bicubic,format=rgb24,split=${n}${labels('a')}`,
    `[1:v]scale=${width}:${height}:flags=bicubic,format=rgb24,split=${n}${labels('b')}`,
  ]
  for (let i = 0; i < n; i++) {
    const x = (i % TILE_COLS) * tw
    const y = Math.floor(i / TILE_COLS) * th
    parts.push(`[a${i}]crop=${tw}:${th}:${x}:${y}[ac${i}]`, `[b${i}]crop=${tw}:${th}:${x}:${y}[bc${i}]`)
    // every tile but the last ends in a sink; the last is the graph's output (-f null)
    parts.push(i < n - 1 ? `[ac${i}][bc${i}]ssim,nullsink` : `[ac${i}][bc${i}]ssim`)
  }
  return parts.join(';')
}

/**
 * How alike two images are, as the LOWEST SSIM over a grid of tiles (1.0 = identical), both scaled to `size` first.
 * The lowest tile, not the whole-frame score, because a changed headline is a small area of a busy photo: the
 * whole frame still scores 0.98 while the tiles under the text drop well below 0.9. Uses ffmpeg, which the package
 * already resolves for media probing, so no image library is added.
 */
export async function imageSimilarity(a: string, b: string, size: { width: number; height: number }): Promise<number> {
  const ffmpeg = await resolveFfmpegPath()
  const { stderr } = await execFileAsync(ffmpeg, ['-hide_banner', '-nostats', '-i', a, '-i', b, '-lavfi', similarityFilter(size), '-f', 'null', '-'], { maxBuffer: 16 * 1024 * 1024 })
  // SSIM can be slightly negative on unrelated tiles; an unreadable score counts as no similarity
  const scores = [...stderr.matchAll(/All:(-?[0-9.]+|-?nan)/g)].map((m) => (Number.isFinite(Number(m[1])) ? Number(m[1]) : 0))
  if (scores.length !== TILE_COLS * TILE_ROWS) throw new Error(`ffmpeg scored ${scores.length} tiles for ${basename(a)} against ${basename(b)}, expected ${TILE_COLS * TILE_ROWS}`)
  return Math.min(...scores)
}

// --- attach_slideshow_spec ------------------------------------------------------

/**
 * Below this (the lowest tile's SSIM, see imageSimilarity) the re-composed slide is not the stored slide. Measured
 * with this composer on a busy and a smooth photo: the same spec re-composed scores 1.0; a PNG slide re-saved as a
 * JPEG at ordinary quality scores 0.99 (0.96 on a worst-case synthetic test card); one changed letter of a headline
 * 0.83, a changed line 0.77 to 0.81; a changed box colour 0.999 (it is luminance-based, so it is not caught). 0.95
 * sits between recompression noise and a text difference, so a spec that really is the source of the slides passes,
 * and one that has drifted (edited since, or slides composed before the bundled fonts) is refused unless `force`.
 */
export const SIMILARITY_THRESHOLD = 0.95

export interface AttachSlideshowSpecParams {
  creativeId: string
  specPath: string
  force?: boolean
}

export interface SlideSimilarity {
  placement: string
  slide: number
  ssim: number
}

export interface AttachSlideshowSpecOptions {
  /** Where a relative specPath resolves from (default: the cwd). */
  cwd?: string
  /** Tests: stand-ins for the browser compose, the image download and the SSIM. */
  compose?: (spec: SlideshowSpec, outDir: string, options: ComposeOptions) => Promise<Pick<ComposeResult, 'slidePaths'>>
  download?: (mediaId: string, into: string) => Promise<string>
  similarity?: typeof imageSimilarity
  log?: (message: string) => void
}

export type AttachSlideshowSpecResult =
  | {
      ok: true
      creativeId: string
      attached: true
      images: number
      slideStyleSet: boolean
      compared: number
      minSimilarity: number | null
      lowest: SlideSimilarity[]
      skipped: string[]
      forced: boolean
      warnings: string[]
      textFields: number
      nextStep: string
    }
  | { ok: false; error: string; similarity?: { threshold: number; minSimilarity: number; below: SlideSimilarity[] }; skipped?: string[] }

const round = (n: number): number => Math.round(n * 1000) / 1000

/** Fetches one stored slide image to a file under `into` (the photo's signed url). */
async function downloadMedia(config: Config, mediaId: string, into: string): Promise<string> {
  const photo = await apiGet<{ url?: string }>(`/api/v2/projects/${config.projectId}/marketing/photos/${mediaId}`, config)
  if (!photo.url) throw new Error(`Media ${mediaId} has no download url.`)
  const response = await fetch(photo.url)
  if (!response.ok) throw new Error(`Downloading media ${mediaId} failed: HTTP ${response.status}`)
  const file = join(into, `${mediaId}.img`)
  await writeFile(file, new Uint8Array(await response.arrayBuffer()))
  return file
}

/** The ratio a stored list is, from its first image: 1080x1920 is 9x16, 1080x1350 is 4x5; anything else is not comparable. */
async function ratioOfImage(file: string): Promise<SlideRatio | undefined> {
  const info = readImageDimensions(new Uint8Array(await readFile(file)))
  if (!info) return undefined
  if (info.width === 1080 && info.height === 1920) return '9x16'
  if (info.width === 1080 && info.height === 1350) return '4x5'
  return undefined
}

export async function attachSlideshowSpec(
  params: AttachSlideshowSpecParams,
  config: Config,
  options: AttachSlideshowSpecOptions = {}
): Promise<AttachSlideshowSpecResult> {
  if (!params.creativeId) return { ok: false, error: 'creativeId is required.' }
  if (!params.specPath) return { ok: false, error: 'specPath is required: a slideshow spec .json (a source spec or a compose_slideshow output spec.json).' }
  const log = options.log ?? (() => {})
  const specFile = isAbsolute(params.specPath) ? params.specPath : resolve(options.cwd ?? process.cwd(), params.specPath)

  const read = await readSpecJson(specFile)
  if (!read.ok) return { ok: false, error: read.error }
  const prepared = prepareSpecForCreative(read.raw, dirname(specFile))
  if (!prepared.ok) return { ok: false, error: `${specFile} is not a usable slideshow spec:\n${prepared.errors.map((e) => `  - ${e}`).join('\n')}` }
  const { spec, resolvedStyle } = prepared
  if (specImagesAreMedia(spec)) {
    return { ok: false, error: 'This spec already names uploaded images (media:<id>). Attach the local spec (the source specs/*.json or the compose_slideshow output spec.json) so the slides can be re-composed and checked.' }
  }
  const missing = specLocalImages(spec).filter((p) => !existsSync(p))
  if (missing.length > 0) return { ok: false, error: `The spec names image files that are not on this machine: ${missing.slice(0, 5).join(', ')}${missing.length > 5 ? ', ...' : ''}` }

  const creative = await apiGet<Record<string, unknown>>(`${creativesBase(config)}/${params.creativeId}`, config)
  if (creative['kind'] !== 'CAROUSEL') return { ok: false, error: `Creative ${params.creativeId} is a ${String(creative['kind'])}; only a CAROUSEL has a slideshow spec.` }
  if (creative['slideshowSpec'] && !params.force) {
    return { ok: false, error: 'This Creative already has a slideshow spec. Replacing it can discard text edits made since; pass force: true to replace it.' }
  }
  const stored = Object.entries((creative['slides'] ?? {}) as Record<string, string[]>).filter(([, ids]) => Array.isArray(ids) && ids.length > 0)
  if (stored.length === 0) return { ok: false, error: 'This Creative has no slides yet; commit its slides first.' }
  const wrong = stored.filter(([, ids]) => ids.length !== spec.slides.length)
  if (wrong.length > 0) {
    return { ok: false, error: `The spec has ${spec.slides.length} slides but ${wrong.map(([p, ids]) => `${p} has ${ids.length}`).join(', ')}; the spec does not describe these slides (force cannot override this).` }
  }

  const temp = await mkdtemp(join(tmpdir(), 'conductor-attach-spec-'))
  try {
    const download = options.download ?? ((id: string, into: string) => downloadMedia(config, id, into))
    const similarity = options.similarity ?? imageSimilarity

    // Which stored lists can be re-composed: 9x16 and 4x5 by name; default (or 1x1) by the shape of its images.
    const comparable: Array<{ placement: string; ratio: SlideRatio; ids: string[]; first: string }> = []
    const skipped: string[] = []
    for (const [placement, ids] of stored) {
      const first = await download(ids[0]!, temp)
      const ratio = COMPOSE_RATIOS[placement] ?? (await ratioOfImage(first))
      if (!ratio) skipped.push(`${placement} (its images are not 1080x1920 or 1080x1350, so they cannot be re-composed)`)
      else comparable.push({ placement, ratio, ids, first })
    }
    if (comparable.length === 0 && !params.force) {
      return { ok: false, error: 'None of this Creative\'s slide lists can be re-composed (only 9x16 and 4x5), so the spec cannot be checked against them. Pass force: true to attach it anyway.', skipped }
    }

    const results: SlideSimilarity[] = []
    if (comparable.length > 0) {
      const ratios = [...new Set(comparable.map((c) => c.ratio))]
      const compose = options.compose ?? ((s, out, o) => composeSlideshow(s, out, o))
      log(`re-composing ${spec.slides.length} slides at ${ratios.join(', ')}`)
      const composed = await compose(spec, join(temp, 'composed'), { ratios, specDir: dirname(specFile), ...(resolvedStyle ? { style: resolvedStyle } : {}) })
      for (const { placement, ratio, ids, first } of comparable) {
        const fresh = composed.slidePaths[ratio] ?? []
        const height = ratio === '9x16' ? 1920 : 1350
        for (let i = 0; i < ids.length; i++) {
          const storedFile = i === 0 ? first : await download(ids[i]!, temp)
          const mine = fresh[i]
          if (!mine) throw new Error(`The composer produced ${fresh.length} ${ratio} slides, not ${ids.length}.`)
          results.push({ placement, slide: i + 1, ssim: round(await similarity(mine, storedFile, { width: 1080, height })) })
        }
      }
    }
    const lowest = [...results].sort((a, b) => a.ssim - b.ssim)
    const min = lowest[0]?.ssim ?? null
    const below = results.filter((r) => r.ssim < SIMILARITY_THRESHOLD)
    if (below.length > 0 && !params.force) {
      return {
        ok: false,
        error:
          `${below.length} of ${results.length} slides do not match this spec when re-composed (SSIM below ${SIMILARITY_THRESHOLD}; lowest ${min}): ` +
          `${below.slice(0, 6).map((b) => `${b.placement} slide ${b.slide} = ${b.ssim}`).join(', ')}${below.length > 6 ? ', ...' : ''}. ` +
          'Either this is not the spec these slides were made from, or it was edited afterwards, or the slides were made with different fonts. ' +
          'Nothing was attached. Pass force: true to attach it anyway (the slides stay as they are; only later edits will re-render from the spec).',
        similarity: { threshold: SIMILARITY_THRESHOLD, minSimilarity: min ?? 0, below: below.slice(0, 20) },
        ...(skipped.length ? { skipped } : {}),
      }
    }

    // Upload the spec's images (once each), rewrite the references and patch the Creative.
    const done: Record<string, string> = {}
    const upload = await uploadSpecImages(spec, config, { fallback: {}, done })
    const hasStyle = creative['slideStyle'] && Object.keys(creative['slideStyle'] as object).length > 0
    const setStyle = !hasStyle && !!resolvedStyle
    const patched = await updateCreative(
      {
        creativeId: params.creativeId,
        version: creative['version'] as number,
        slideshowSpec: upload.spec as unknown as Record<string, unknown>,
        ...(setStyle ? { slideStyle: resolvedStyle } : {}),
      },
      config
    )
    const warnings = [...upload.warnings]
    if (!patched['slideshowSpec']) warnings.push('The server did not return slideshowSpec on the updated Creative; it may be an older backend that ignores the field. Check with get_creative.')
    const fields = listTextFields(spec).length
    return {
      ok: true,
      creativeId: params.creativeId,
      attached: true,
      images: upload.uploaded,
      slideStyleSet: setStyle,
      compared: results.length,
      minSimilarity: min,
      lowest: lowest.slice(0, 5),
      skipped,
      forced: below.length > 0 || (comparable.length === 0 && !!params.force),
      warnings,
      textFields: fields,
      nextStep:
        `Attached a ${spec.slides.length}-slide spec (${fields} editable text fields) to Creative ${params.creativeId}` +
        `${results.length ? `; ${results.length} stored slides re-composed, lowest similarity ${min}` : '; no slide could be compared'}. ` +
        'Call get_creative to verify: slideshowSpec and textFields should now be present.',
    }
  } finally {
    await rm(temp, { recursive: true, force: true }).catch(() => {})
  }
}

function specImagesAreMedia(spec: SlideshowSpec): boolean {
  let any = false
  mapSpecImages(spec, (v) => {
    if (v && isMediaRef(v)) any = true
    return v
  })
  return any
}
