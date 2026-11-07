import { execFileSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { lstat, mkdir, readdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { basename, isAbsolute, join, resolve, sep } from 'node:path'
import { Config } from '../config.js'
import { slugify } from '../../lib/slug.js'
import { composePosterSheet } from '../../lib/poster-sheet.js'
import { apiGet, apiPatch, apiPost, ApiError } from '../api.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import { probeMedia } from '../../lib/media-probe.js'
import { readProvenanceSidecar, type SidecarProvenance } from '../../lib/provenance-sidecar.js'
import { needsLocalRender } from '../../lib/creative-local-render.js'
import { renderCreative as runLocalRender, renderViaApi, renderDraft, type DraftCheck, type DraftManifest } from '../../lib/creative-render.js'
import {
  createCreative,
  getCreative,
  updateCreative,
  uploadCreativeMedia,
  type CreativeFields,
} from './creatives.js'
import { chooseDraftSpec, uploadSpecImages } from './slideshow-spec.js'
import { listTextFields, specLocalImages, type SlideshowSpec } from '../../lib/slideshow/index.js'
import { zonesFor } from '../../lib/safe-zone/zones.js'
import {
  checkClipPixels,
  checkSlidesPixels,
  describeChecks,
  givenDurations,
  hasErrors,
  hasTrack,
  inlineOverlay,
  pacingFor,
  platformsFor,
  writeOverlaySheets,
  type OverlaySheet,
  type PacingResult,
  type PixelToolOptions,
  type UiOverlay,
} from './safe-zone-pixels.js'

/**
 * Draft-first Creatives: render a Creative on this machine from a spec the backend resolves WITHOUT
 * saving anything, so a person looks at the artwork before a Creative row or a media upload exists.
 *
 *   preview_creative_draft  -> POST .../creatives/draft-spec (writes nothing server-side), render the
 *                              frames locally into `<project>/.conductor/drafts/<stamp>-<slug>/`, and
 *                              leave a `draft.json` describing what was previewed.
 *   commit_creative_draft   -> only after the person approved: upload the local files, create (or
 *                              update) the Creative, run the real render, mark the draft committed.
 *
 * Local files ride in the draft as `local:<key>` media ids (photo, clip, audio, per-beat photos); the
 * backend validates them as if they were media and echoes `local:<key>` back as the URL, and
 * conductor-creative/job/file-transport.mjs swaps each for a loopback URL while rendering.
 */

const V2_PROJECT = (config: Config): string => `/api/v2/projects/${config.projectId}`
const creativesBase = (config: Config): string => `${V2_PROJECT(config)}/marketing/creatives`

const MAX_INLINE_IMAGE_BYTES = 1_000_000 // same ceiling preview_creative uses
const DRAFT_MAX_AGE_MS = 14 * 24 * 60 * 60 * 1000
const LOCAL_REF = /^local:([a-z0-9][a-z0-9-]{0,63})$/

export const DRAFTS_SUBDIR = join('.conductor', 'drafts')

// --- Types -------------------------------------------------------------------

export interface PreviewCreativeDraftParams extends CreativeFields {
  /** A photo on this machine; becomes `photoId: "local:photo"`. */
  photoPath?: string
  /** A video clip on this machine. For MOTION it becomes the background clip (`local:clip`); for kind CLIP it is the finished video to check (9:16 unless its aspect says otherwise). */
  clipPath?: string
  /** kind CLIP only: finished videos on this machine per placement, e.g. {"9x16": "/clips/a.mp4"}. */
  clipPaths?: Record<string, string>
  /** The platform UI drawn over 9:16 frames: instagram | tiktok | both | none. Default for a 9:16 CAROUSEL or CLIP: both, written to disk, with the Instagram sheet shown. */
  uiOverlay?: UiOverlay
  /** An audio file on this machine; becomes the audio track of a MOTION or CAROUSEL (`local:audio`). */
  audioPath?: string
  /** CAROUSEL only: the slides as local image files (or existing media ids), ordered per placement,
   * e.g. {"4x5": ["/art/01.png", "/art/02.png"]}; each path becomes a `local:<key>` entry of `slides`. */
  slidePaths?: Record<string, string[]>
  /** CAROUSEL only: the slideshow spec the slides were composed from (a compose_slideshow output `spec.json` or a
   * source spec). Kept with the draft and stored on the Creative at commit so its text stays editable. Default: the
   * `spec.json` beside the slide files, when compose_slideshow wrote it for them. */
  slideshowSpecPath?: string
  /** Preview edits on top of this saved Creative: only the fields given here change. */
  baseCreativeId?: string
  /** Where to write the draft (default `<projectRoot>/.conductor/drafts/<YYYYMMDD-HHMMSS>-<slug>/`). */
  draftDir?: string
  /** Render every placement at full size instead of just the contact sheet. */
  full?: boolean
}

export interface DraftFile {
  version: 1
  createdAt: string
  committed: boolean
  committedAt?: string
  creativeId?: string
  projectId: string
  baseCreativeId?: string
  full: boolean
  /** The create-creative fields exactly as previewed, local media still as `local:<key>`. */
  request: Record<string, unknown>
  /** key -> absolute path of the local file behind `local:<key>`. */
  localFiles: Record<string, string>
  localMedia: Record<string, LocalMediaMeta>
  /** key -> provenance from the file's `.provenance.json` sidecar (only keys that have one; absent in older drafts). */
  localProvenance?: Record<string, SidecarProvenance>
  /** sha256 of the spec the backend returned. */
  specSha256: string
  /** CAROUSEL: where the slideshow spec was read from. The draft keeps its own copy below, so a later edit of that file cannot change what is committed. */
  slideshowSpecPath?: string
  /** CAROUSEL: the slideshow spec as previewed (image paths absolute, no resolvedStyle); sent to the Creative at commit with each image uploaded and rewritten to `media:<id>`. */
  slideshowSpec?: SlideshowSpec
  /** absolute image path -> media id, filled in as commit uploads the spec's images (so a retry never re-uploads). */
  specImages?: Record<string, string>
  /** key -> media id, filled in as `commit_creative_draft` uploads (so a retry never re-uploads). */
  uploaded?: Record<string, string>
}

interface LocalMediaMeta {
  kind: 'IMAGE' | 'VIDEO' | 'AUDIO'
  width?: number
  height?: number
  durationSeconds?: number
  hasAudio?: boolean
}

export interface DraftToolOptions extends PixelToolOptions {
  /** Override the project root (tests). Default: the git root of the cwd, else the cwd. */
  projectRoot?: string
  log?: (...args: unknown[]) => void
}

export interface PreviewCreativeDraftFailure {
  ok: false
  rendered: false
  error: string
  status?: number
  violations?: Array<{ field?: string; ruleId?: string; message: string }>
  draftDir?: string
}

export interface PreviewCreativeDraftSuccess {
  ok: true
  committed: false
  draftDir: string
  previewOnly: boolean
  image?: { data: Buffer; mimeType: string }
  files: Array<{ placementKey: string; index?: number; file: string; width?: number; height?: number }>
  /** CAROUSEL only: one contact sheet image per placement (per ten slides). */
  sheets?: Array<{ placementKey: string; file: string; slides: number }>
  /** CAROUSEL only: the slideshow spec kept with the draft (it travels with the Creative at commit); absent when none applies. */
  slideshowSpec?: { path: string; slides: number; textFields: number }
  /** CAROUSEL with a track only: the slideshow MP4 rendered on this machine (the full video, not a sheet). */
  slideshowVideoPath?: string
  slideshowDurationSeconds?: number
  /** CAROUSEL with a track: seconds per 9x16 slide (computed from the slides' text, or the ones passed). */
  slideDurations?: number[]
  /** CAROUSEL with a track: where the timing came from. `computed: false` means the caller's durations were kept. */
  pacing?: { basis: string; computed: boolean }
  /** The platform UI drawn over the 9:16 frames, one sheet per platform, on disk. The image shown is the Instagram one. */
  overlaySheets?: OverlaySheet[]
  readiness?: unknown
  warnings: Array<{ placementKey?: string; index?: number; message: string }>
  /**
   * What the full render would check on every placement (text spill, the platform-reserved safe zones,
   * contrast, fonts, images, artboard size), run on the draft BEFORE anything is saved — the contact
   * sheet alone never runs these. An "error" check would fail that placement's real render.
   */
  checks: DraftCheck[]
  /** True when no check has severity "error". Only then is the draft ready to show for approval. */
  passed: boolean
  /** Which local files carry sidecar provenance already, and which still need it from commit_creative_draft's params. */
  provenance: { recorded: string[]; needed: string[] }
  note?: string
  nextStep: string
}

export type PreviewCreativeDraftResult = PreviewCreativeDraftSuccess | PreviewCreativeDraftFailure

// --- Paths and cleanup ---------------------------------------------------------

/** The git root of `cwd` when there is one, else `cwd` itself. */
export function findProjectRoot(cwd: string = process.cwd()): string {
  try {
    const root = execFileSync('git', ['rev-parse', '--show-toplevel'], {
      cwd,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    }).trim()
    return root || cwd
  } catch {
    return cwd
  }
}

function draftsRoot(projectRoot: string): string {
  return join(resolve(projectRoot), DRAFTS_SUBDIR)
}

export function stamp(now: Date): string {
  const p = (n: number, w = 2) => String(n).padStart(w, '0')
  return `${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}${p(now.getSeconds())}`
}

async function readDraftFile(draftDir: string): Promise<DraftFile | undefined> {
  try {
    return JSON.parse(await readFile(join(draftDir, 'draft.json'), 'utf8')) as DraftFile
  } catch {
    return undefined
  }
}

/**
 * Removes draft folders under `<projectRoot>/.conductor/drafts` that are committed or older than 14
 * days. Deliberately narrow: it only lists that one directory, only touches real (non-symlink)
 * sub-folders that hold a `draft.json` written by these tools, and re-checks that each target resolves
 * inside the drafts directory before removing it. `keep` names folders to skip (the draft a tool is
 * working on, so a second commit still gets "already committed" rather than "not found"). Never throws — pruning is housekeeping.
 */
export async function pruneDrafts(projectRoot: string, now: Date = new Date(), keep: string[] = []): Promise<string[]> {
  const root = draftsRoot(projectRoot)
  const removed: string[] = []
  let entries: string[]
  try {
    entries = await readdir(root)
  } catch {
    return removed
  }
  for (const name of entries) {
    const dir = resolve(root, name)
    if (!dir.startsWith(root + sep) || keep.map((k) => resolve(k)).includes(dir)) continue
    try {
      const info = await lstat(dir)
      if (!info.isDirectory() || info.isSymbolicLink()) continue
      const draft = await readDraftFile(dir)
      if (!draft) continue
      const created = Date.parse(draft.createdAt)
      const age = now.getTime() - (Number.isNaN(created) ? info.mtimeMs : created)
      if (draft.committed || age > DRAFT_MAX_AGE_MS) {
        await rm(dir, { recursive: true, force: true })
        removed.push(dir)
      }
    } catch {
      /* leave it */
    }
  }
  return removed
}

// --- Building the draft request --------------------------------------------------

type Beat = Record<string, unknown>

/** Splits the tool params into the create-creative body (with `local:<key>` references) and the key ->
 * file map, refusing a combination that names the same slot twice. */
function buildDraftRequest(params: PreviewCreativeDraftParams): { request: Record<string, unknown>; localFiles: Record<string, string> } {
  const { photoPath, clipPath, clipPaths: _cp, uiOverlay: _ui, audioPath, slidePaths, slideshowSpecPath: _s, baseCreativeId: _b, draftDir: _d, full: _f, ...fields } = params as PreviewCreativeDraftParams & {
    variantOf?: unknown
  }
  const request: Record<string, unknown> = { ...fields }
  delete request['variantOf']
  const localFiles: Record<string, string> = {}
  const abs = (p: string) => (isAbsolute(p) ? p : resolve(p))

  if (photoPath) {
    if (request['photoId']) throw new Error('Pass photoPath or photoId, not both.')
    request['photoId'] = 'local:photo'
    localFiles['photo'] = abs(photoPath)
  }

  if (clipPath) {
    const motion = (request['motion'] ?? {}) as Record<string, unknown>
    const background = (motion['background'] ?? {}) as Record<string, unknown>
    if (background['clipMediaId']) throw new Error('Pass clipPath or motion.background.clipMediaId, not both.')
    request['motion'] = { ...motion, background: { ...background, source: 'clip', clipMediaId: 'local:clip' } }
    localFiles['clip'] = abs(clipPath)
  }

  if (audioPath) {
    const audio = (request['audio'] ?? {}) as Record<string, unknown>
    if (audio['trackId']) throw new Error('Pass audioPath or audio.trackId, not both.')
    request['audio'] = { source: 'track', ...audio, trackId: 'local:audio' }
    localFiles['audio'] = abs(audioPath)
  }

  if (slidePaths) {
    const slides: Record<string, string[]> = { ...((request['slides'] as Record<string, string[]> | undefined) ?? {}) }
    const keyByPath = new Map<string, string>()
    let n = 0
    for (const [placement, entries] of Object.entries(slidePaths)) {
      if (slides[placement]) throw new Error(`Pass slidePaths or slides for "${placement}", not both.`)
      if (!Array.isArray(entries)) throw new Error(`slidePaths.${placement} must be an array of image paths.`)
      slides[placement] = entries.map((entry) => {
        const path = abs(entry)
        // An existing file is a slide to upload; anything else must look like a media id.
        if (!existsSync(path)) {
          if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(entry)) return entry
          throw new Error(`slidePaths.${placement}: "${entry}" is neither a file on this machine nor a media id.`)
        }
        let key = keyByPath.get(path)
        if (!key) {
          key = `slide-${++n}`
          keyByPath.set(path, key)
          localFiles[key] = path
        }
        return `local:${key}`
      })
    }
    request['slides'] = slides
    // slidePaths implies CAROUSEL: without the kind the server would judge (and commit) a STILL.
    if (request['kind'] === undefined) request['kind'] = 'CAROUSEL'
  }

  if (Array.isArray(request['sequence'])) {
    request['sequence'] = (request['sequence'] as Beat[]).map((beat, i) => {
      const { photoPath: beatPath, ...rest } = beat as Beat & { photoPath?: string }
      if (!beatPath) return rest
      if (rest['photoId']) throw new Error(`sequence[${i}]: pass photoPath or photoId, not both.`)
      const key = `beat-${i + 1}`
      localFiles[key] = abs(beatPath)
      return { ...rest, photoId: `local:${key}` }
    })
  }

  return { request, localFiles }
}

/** Reads one local file's metadata the way `upload_creative_media` does: image dimensions from the
 * bytes, everything else (video/audio) from ffprobe. */
async function readLocalMediaMeta(path: string): Promise<LocalMediaMeta> {
  const bytes = new Uint8Array(await readFile(path))
  const image = readImageDimensions(bytes)
  if (image) return { kind: 'IMAGE', width: image.width, height: image.height }
  const probe = await probeMedia(path)
  if (probe.kind !== 'VIDEO' && probe.kind !== 'AUDIO') {
    throw new Error(`"${basename(path)}" is not a recognizable photo, video or audio file.`)
  }
  return {
    kind: probe.kind,
    ...(probe.width !== undefined ? { width: probe.width } : {}),
    ...(probe.height !== undefined ? { height: probe.height } : {}),
    ...(probe.durationSeconds !== undefined ? { durationSeconds: probe.durationSeconds } : {}),
    ...(probe.kind === 'VIDEO' ? { hasAudio: !!probe.hasAudio } : {}),
  }
}

function replaceLocalRefs(value: unknown, idFor: (key: string) => string): unknown {
  if (typeof value === 'string') {
    const m = LOCAL_REF.exec(value)
    return m ? idFor(m[1]!) : value
  }
  if (Array.isArray(value)) return value.map((v) => replaceLocalRefs(v, idFor))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value as Record<string, unknown>).map(([k, v]) => [k, replaceLocalRefs(v, idFor)]))
  }
  return value
}


/**
 * CAROUSEL preview: the slides are finished images, so there is nothing to render. This composes one
 * contact sheet per placement (slides left to right, in order; ten per sheet) so the person can check
 * the order and the look, and hands back the backend's readiness.
 */
async function carouselPreview(input: {
  request: Record<string, unknown>
  localFiles: Record<string, string>
  localMedia: Record<string, LocalMediaMeta>
  localProvenance: Record<string, SidecarProvenance>
  draftDir: string
  readiness: unknown
  /** The slideshow spec kept with the draft, and notes about one that was dropped. */
  spec?: { path: string; slides: number; textFields: number }
  specNotes: string[]
  /** The pixel check of the 9x16 slides, the overlay sheets written for them, and notes about both. */
  pixel: { checks: DraftCheck[]; overlaySheets: OverlaySheet[]; notes: string[] }
  /** CAROUSEL with a track: the slide timing. */
  pacing?: PacingResult
  log: (...args: unknown[]) => void
}): Promise<PreviewCreativeDraftSuccess> {
  const { request, localFiles, draftDir, localProvenance, pixel, pacing } = input
  const slides = (request['slides'] ?? {}) as Record<string, string[]>
  const SHEET_SLIDES = 10
  const sheets: Array<{ placementKey: string; file: string; slides: number }> = []
  const notes: string[] = [...input.specNotes, ...pixel.notes]
  let cleanImage: PreviewCreativeDraftSuccess['image']

  for (const [placement, refs] of Object.entries(slides)) {
    const paths: string[] = []
    for (const ref of refs) {
      const m = LOCAL_REF.exec(ref)
      if (m && localFiles[m[1]!]) paths.push(localFiles[m[1]!]!)
      else notes.push(`${placement}: ${ref} is an existing media id, so it is not on the sheet.`)
    }
    for (let from = 0; from < paths.length; from += SHEET_SLIDES) {
      const chunk = paths.slice(from, from + SHEET_SLIDES)
      const name = `sheet-${placement}${paths.length > SHEET_SLIDES ? `-${from / SHEET_SLIDES + 1}` : ''}.jpg`
      try {
        const bytes = await composePosterSheet(chunk)
        await writeFile(join(draftDir, name), bytes)
        sheets.push({ placementKey: placement, file: join(draftDir, name), slides: chunk.length })
        if (!cleanImage && bytes.byteLength <= MAX_INLINE_IMAGE_BYTES) cleanImage = { data: bytes, mimeType: 'image/jpeg' }
      } catch (err) {
        notes.push(`${placement}: could not compose a contact sheet (${err instanceof Error ? err.message : String(err)}) — open the slide files directly.`)
      }
    }
  }
  if (sheets.length && !cleanImage) notes.push('The contact sheet is larger than this tool can inline (~1 MB) — open the sheet files directly.')

  // With the platform UI drawn on, the overlay sheet is THE image (one image per result); the clean sheet stays on disk.
  const overlay = await inlineOverlay(pixel.overlaySheets)
  if (overlay.note) notes.push(overlay.note)
  const image = overlay.image ?? cleanImage

  const checks = pixel.checks
  const passed = !hasErrors(checks)
  const failing = checks.filter((c) => c.severity === 'error')
  const advisories = checks.filter((c) => c.severity !== 'error')
  const warnings: PreviewCreativeDraftSuccess['warnings'] = (pacing?.warnings ?? []).map((message) => ({ message }))
  const showWhat = overlay.image
    ? `Show the UI overlay image (the platform UI drawn over the slides) and say the clean contact sheet is at ${sheets[0]?.file ?? 'the draft folder'}. `
    : 'Show the contact sheet(s) to the person '
  const looks = 'and check the order, the crop and any burned-in text. '
  const length = pacing ? `Say the total length (${pacing.totalSeconds} s) when showing it. ` : ''

  return {
    ok: true,
    committed: false,
    draftDir,
    previewOnly: true,
    ...(image ? { image } : {}),
    files: sheets.map((sh) => ({ placementKey: sh.placementKey, file: sh.file })),
    sheets,
    ...(pixel.overlaySheets.length ? { overlaySheets: pixel.overlaySheets } : {}),
    ...(pacing ? { slideDurations: pacing.slideDurations, slideshowDurationSeconds: pacing.totalSeconds, pacing: { basis: pacing.basis, computed: pacing.computed } } : {}),
    ...(input.spec ? { slideshowSpec: input.spec } : {}),
    readiness: input.readiness,
    warnings,
    checks,
    passed,
    provenance: {
      recorded: Object.keys(localProvenance),
      needed: Object.keys(localFiles).filter((k) => !localProvenance[k]),
    },
    ...(notes.length ? { note: notes.join(' ') } : {}),
    nextStep: !passed
      ? `NOT ready for approval: ${failing.length} safe-zone check${failing.length === 1 ? '' : 's'} failed (${describeChecks(failing)}). ` +
        'Nothing has been saved. Fix the slides first (move the text below y=290 at 1080x1920, re-export the image) and call preview_creative_draft again BEFORE showing them. ' +
        'Do not ask the person to approve and do not call commit_creative_draft while a check fails; tell them what you changed.'
      : 'Nothing has been saved to Conductor, and the slides are used exactly as they are (no brand layout is applied). ' +
        showWhat +
        looks +
        length +
        'Resolve any blocking readiness item (caption, alt text, photoChecked) you can fix now. ' +
        (input.spec
          ? `The slideshow spec (${input.spec.slides} slides, ${input.spec.textFields} editable text fields) is kept with the draft and is stored on the Creative at commit, so its text can be edited later. `
          : '') +
        (advisories.length ? `Warnings (they do not block, but tell the person when you show it): ${describeChecks(advisories)} ` : '') +
        'Only after they approve, call commit_creative_draft({draftDir, photoChecked: true}); to change something, call preview_creative_draft again.',
  }
}


// --- CLIP preview ---------------------------------------------------------------------

const PLACEMENT_RATIOS: Array<[string, number]> = [['9x16', 9 / 16], ['4x5', 4 / 5], ['1x1', 1], ['16x9', 16 / 9]]

/** The placement a video's aspect belongs to ('default' when it matches none). */
function placementForAspect(width: number, height: number): string {
  const ratio = width / height
  const hit = PLACEMENT_RATIOS.find(([, r]) => Math.abs(ratio / r - 1) < 0.03)
  return hit ? hit[0] : 'default'
}

/**
 * CLIP preview: the video is used as-is, so there is nothing to render and nothing to send to Conductor.
 * Each 9:16 clip is sampled (every 0.5 s and at each cut), checked for text under the platform UI, and
 * drawn with the Instagram/TikTok chrome over its early frames and cuts. Only a minimal draft.json is written (for pruning) —
 * a CLIP has no commit step; once the checks pass, upload_creative_media + create_creative kind CLIP.
 */
async function clipPreview(params: PreviewCreativeDraftParams, options: DraftToolOptions, projectRoot: string, projectId: string): Promise<PreviewCreativeDraftResult> {
  const abs = (p: string) => (isAbsolute(p) ? p : resolve(p))
  const given: Record<string, string> = params.clipPaths ? { ...params.clipPaths } : params.clipPath ? { '9x16': params.clipPath } : {}
  if (!Object.keys(given).length) {
    return { ok: false, rendered: false, error: 'For kind CLIP pass clipPath (one video file) or clipPaths ({"9x16": "/path/clip.mp4"}). The video is checked and shown here; nothing is sent to Conductor.' }
  }
  const clips: Array<{ placement: string; path: string; width?: number; height?: number; durationSeconds?: number }> = []
  try {
    for (const [key, raw] of Object.entries(given)) {
      const path = abs(raw)
      try {
        await stat(path)
      } catch {
        throw new Error(`The clip for ${key} does not exist: ${path}`)
      }
      const probe = await probeMedia(path)
      if (probe.kind !== 'VIDEO') throw new Error(`"${basename(path)}" is not a video file.`)
      // One file given as clipPath: its own aspect decides the placement.
      const placement = !params.clipPaths && probe.width && probe.height ? placementForAspect(probe.width, probe.height) : key
      clips.push({ placement, path, width: probe.width, height: probe.height, durationSeconds: probe.durationSeconds })
    }
  } catch (err) {
    return { ok: false, rendered: false, error: err instanceof Error ? err.message : String(err) }
  }

  const draftDir = params.draftDir
    ? resolve(params.draftDir)
    : join(draftsRoot(projectRoot), `${stamp(new Date())}-${slugify((params.name as string | undefined) ?? 'clip')}`)
  await mkdir(draftDir, { recursive: true })
  if (!params.draftDir) await writeFile(join(draftsRoot(projectRoot), '.gitignore'), '*\n').catch(() => {})
  // A minimal draft.json, so pruneDrafts ages the overlay images out like any other draft. commit_creative_draft
  // refuses it by its request.kind (a CLIP has no commit step).
  const clipDraft: DraftFile = {
    version: 1,
    createdAt: new Date().toISOString(),
    committed: false,
    projectId,
    full: false,
    request: { kind: 'CLIP' },
    localFiles: {},
    localMedia: {},
    specSha256: '',
  }
  await writeFile(join(draftDir, 'draft.json'), JSON.stringify(clipDraft, null, 2))

  const checks: DraftCheck[] = []
  const overlaySheets: OverlaySheet[] = []
  const notes: string[] = []
  for (const clip of clips) {
    const vertical = !!clip.width && !!clip.height && zonesFor(clip.width, clip.height).length > 0
    if (!vertical) {
      notes.push(`${clip.placement}: ${basename(clip.path)} is ${clip.width ?? '?'}x${clip.height ?? '?'}, not 9:16, so it was not checked for text under the platform UI and no overlay is drawn.`)
      continue
    }
    const r = await checkClipPixels(clip.path, { ...options, placementKey: clip.placement, outDir: draftDir, baseName: `overlay-${clip.placement}`, uiOverlay: params.uiOverlay })
    checks.push(...r.checks)
    overlaySheets.push(...r.overlaySheets)
    notes.push(...r.notes)
  }
  const overlay = await inlineOverlay(overlaySheets)
  if (overlay.note) notes.push(overlay.note)
  if (!overlay.image && overlaySheets.length === 0 && clips.some((c) => c.width && c.height && zonesFor(c.width, c.height).length)) {
    notes.push('No UI overlay image was drawn — open the clip itself.')
  }

  const passed = !hasErrors(checks)
  const failing = checks.filter((c) => c.severity === 'error')
  const advisories = checks.filter((c) => c.severity !== 'error')
  return {
    ok: true,
    committed: false,
    draftDir,
    previewOnly: true,
    ...(overlay.image ? { image: overlay.image } : {}),
    files: clips.map((c) => ({ placementKey: c.placement, file: c.path, ...(c.width ? { width: c.width } : {}), ...(c.height ? { height: c.height } : {}) })),
    ...(overlaySheets.length ? { overlaySheets } : {}),
    warnings: [],
    checks,
    passed,
    provenance: { recorded: [], needed: [] },
    ...(notes.length ? { note: notes.join(' ') } : {}),
    nextStep: !passed
      ? `NOT ready for approval: ${failing.length} safe-zone check${failing.length === 1 ? '' : 's'} failed (${describeChecks(failing)}). Nothing has been sent to Conductor. ` +
        'Re-export the video with the text moved below y=290 at 1080x1920 (and out of the other UI areas) and call preview_creative_draft again BEFORE showing it. Do not ask the person to approve while a check fails.'
      : 'Nothing has been sent to Conductor, and a CLIP has no commit step. ' +
        (overlay.image ? 'Show the UI overlay image (the first seconds and each cut with the Instagram UI drawn on; the TikTok sheet is on disk) and ask whether to approve. ' : 'Ask the person to watch the clip and approve. ') +
        (advisories.length ? `Warnings (they do not block, but tell the person when you show it): ${describeChecks(advisories)} ` : '') +
        'Only after they approve, call upload_creative_media for each file, then create_creative with kind CLIP and clipMedia ({"9x16": mediaId}).',
  }
}

// --- preview_creative_draft ---------------------------------------------------------

function summariseChecks(checks: DraftCheck[]): string {
  return checks
    .map((c) => `${c.placementKey}${c.index !== undefined ? `[${c.index}]` : ''}: ${c.message}`)
    .join('; ')
}

export async function previewCreativeDraft(
  params: PreviewCreativeDraftParams,
  config: Config,
  options: DraftToolOptions = {}
): Promise<PreviewCreativeDraftResult> {
  const log = options.log ?? (() => {})
  const projectRoot = options.projectRoot ?? findProjectRoot()
  await pruneDrafts(projectRoot)

  if (params.kind === 'CLIP') return clipPreview(params, options, projectRoot, config.projectId)

  if (params.slidePaths && params.kind && params.kind !== 'CAROUSEL') {
    return { ok: false, rendered: false, error: `slidePaths is for kind CAROUSEL, not ${params.kind}.` }
  }
  let isCarousel = params.kind === 'CAROUSEL' || (!!params.slidePaths && !params.kind)
  let baseAudio: { source?: string } | null | undefined
  let baseHasSpec = false
  if (params.slideshowSpecPath && !isCarousel && !params.baseCreativeId) {
    return { ok: false, rendered: false, error: 'slideshowSpecPath is for kind CAROUSEL (with slidePaths).' }
  }
  if (params.baseCreativeId && (isCarousel || !params.kind)) {
    // Editing a saved Creative without naming its kind: a CAROUSEL base takes the carousel path, and
    // its saved audio decides whether the slideshow video is rendered.
    try {
      const base = await apiGet<{ kind?: string; audio?: { source?: string } | null; slideshowSpec?: unknown }>(`${creativesBase(config)}/${params.baseCreativeId}`, config)
      if (!isCarousel) isCarousel = base?.kind === 'CAROUSEL'
      baseAudio = base?.audio
      baseHasSpec = !!base?.slideshowSpec
    } catch {
      /* unknown kind: treat as a rendered Creative */
    }
  }

  let built: ReturnType<typeof buildDraftRequest>
  try {
    built = buildDraftRequest(params)
  } catch (err) {
    return { ok: false, rendered: false, error: err instanceof Error ? err.message : String(err) }
  }
  const { request, localFiles } = built

  const localMedia: Record<string, LocalMediaMeta> = {}
  const localProvenance: Record<string, SidecarProvenance> = {}
  try {
    for (const [key, path] of Object.entries(localFiles)) {
      try {
        await stat(path)
      } catch {
        throw new Error(`The file for local:${key} does not exist: ${path}`)
      }
      localMedia[key] = await readLocalMediaMeta(path)
      const recorded = await readProvenanceSidecar(path)
      if (recorded) localProvenance[key] = recorded
    }
  } catch (err) {
    return { ok: false, rendered: false, error: err instanceof Error ? err.message : String(err) }
  }

  // The slideshow spec the slides came from rides in the draft, not in the draft-spec call (which only knows
  // uploaded media, not local files); commit uploads its images and sends it with the Creative.
  let slideshow: { spec: SlideshowSpec; path: string } | undefined
  const specNotes: string[] = []
  if (isCarousel) {
    const choice = await chooseDraftSpec({
      slideshowSpecPath: params.slideshowSpecPath,
      slideFiles: Object.entries(localFiles).filter(([k]) => k.startsWith('slide-')).map(([, v]) => v),
      slideLists: (request['slides'] ?? {}) as Record<string, string[]>,
      projectRoot,
    })
    if (choice.error) return { ok: false, rendered: false, error: choice.error }
    specNotes.push(...choice.notes)
    if (choice.spec && choice.path) slideshow = { spec: choice.spec, path: choice.path }
    else if (params.baseCreativeId && baseHasSpec && params.slidePaths) {
      specNotes.push('The base Creative has a slideshow spec, and these slides replace its slides without one, so committing will clear that spec. Pass slideshowSpecPath to keep one.')
    }
  }

  const full = !!params.full
  // A CAROUSEL with a track gets its slideshow MP4 rendered here, so its spec must be a full render spec.
  const requestAudio = request['audio'] as { source?: string; trackId?: string } | undefined
  const carouselVideo = isCarousel && needsLocalRender('CAROUSEL', requestAudio ?? baseAudio)

  // The 9x16 slides, as files where they are on this machine. They are timed from their text (CAROUSEL with a
  // track) and checked for text under the platform UI; slides that are existing media are not on this machine.
  const slideLists = (request['slides'] ?? {}) as Record<string, string[]>
  const isVertical = (key: string | undefined) => {
    const m = key ? localMedia[key] : undefined
    return !!m?.width && !!m?.height && Math.abs(m.width / m.height - 9 / 16) < 0.01
  }
  const keyOf = (ref: string) => LOCAL_REF.exec(ref)?.[1]
  const pixelKey = isCarousel
    ? slideLists['9x16']
      ? '9x16'
      : (slideLists['default'] ?? []).some((r) => isVertical(keyOf(r)))
        ? 'default'
        : undefined
    : undefined
  const slideInputs = pixelKey
    ? slideLists[pixelKey]!.map((ref, index) => {
        const key = keyOf(ref)
        return { index, ...(key && localFiles[key] ? { path: localFiles[key]! } : {}) }
      })
    : undefined
  const slideshowField = request['slideshow'] as Record<string, unknown> | undefined
  let pacing: PacingResult | undefined
  if (carouselVideo && slideInputs?.length) {
    pacing = await pacingFor({
      count: slideInputs.length,
      altText: request['slideAltText'] as string[] | undefined,
      // OCR only fills in a slide with no "Text:" in its alt text, and only on a 9:16 file.
      paths: slideInputs.map((si, i) => (si.path && isVertical(keyOf(slideLists[pixelKey!]![i]!)) ? si.path : undefined)),
      ocr: true,
      engine: options.ocrEngine,
      given: givenDurations(slideshowField),
    })
    // Written into the request before it is sent and saved, so commit_creative_draft stores the durations.
    if (pacing.computed) request['slideshow'] = { ...slideshowField, slideDurations: pacing.slideDurations }
  }
  const body: Record<string, unknown> = { ...request, previewOnly: carouselVideo ? false : !full }
  if (params.baseCreativeId) body['baseCreativeId'] = params.baseCreativeId
  if (Object.keys(localMedia).length) {
    // Keys with sidecar provenance also tell the backend where the file came from (readiness checks it).
    body['localMedia'] = Object.fromEntries(
      Object.entries(localMedia).map(([key, meta]) => {
        const p = localProvenance[key]
        return [key, p ? { ...meta, source: p.source, licence: p.licence, aiGenerated: p.aiGenerated } : meta]
      })
    )
  }

  let response: { spec: Record<string, unknown>; readiness?: unknown }
  try {
    response = await apiPost(`${creativesBase(config)}/draft-spec`, body, config)
  } catch (err) {
    if (err instanceof ApiError && err.status === 422) {
      const violations = err.violations
      const error = violations?.length
        ? violations.map((v) => `[${v.field ?? v.ruleId ?? '?'}] ${v.message}`).join('; ')
        : err.message
      return { ok: false, rendered: false, status: 422, error, ...(violations?.length ? { violations } : {}) }
    }
    throw err
  }
  // A CAROUSEL has no layout to render, so its spec is a stub the client ignores.
  const spec = response.spec ?? (isCarousel ? {} : undefined)
  if (!spec) throw new Error('The draft-spec response carried no spec.')

  const draftDir = params.draftDir
    ? resolve(params.draftDir)
    : join(draftsRoot(projectRoot), `${stamp(new Date())}-${slugify((params.headline as string | undefined) ?? (params.name as string | undefined))}`)
  await mkdir(draftDir, { recursive: true })
  if (!params.draftDir) {
    // Keep the drafts (images, copies of work in progress) out of git without asking anyone to edit .gitignore.
    await writeFile(join(draftsRoot(projectRoot), '.gitignore'), '*\n').catch(() => {})
  }

  const draft: DraftFile = {
    version: 1,
    createdAt: new Date().toISOString(),
    committed: false,
    projectId: config.projectId,
    ...(params.baseCreativeId ? { baseCreativeId: params.baseCreativeId } : {}),
    full,
    request,
    localFiles,
    localMedia,
    ...(Object.keys(localProvenance).length ? { localProvenance } : {}),
    specSha256: createHash('sha256').update(JSON.stringify(spec)).digest('hex'),
    ...(slideshow ? { slideshowSpecPath: slideshow.path, slideshowSpec: slideshow.spec } : {}),
  }
  await writeFile(join(draftDir, 'draft.json'), JSON.stringify(draft, null, 2))

  if (isCarousel) {
    const pixel = slideInputs?.length
      ? await checkSlidesPixels(slideInputs, {
          ...options,
          placementKey: '9x16',
          zoom: carouselVideo && slideshowField?.['zoom'] !== false,
          outDir: draftDir,
          baseName: 'overlay-9x16',
          uiOverlay: params.uiOverlay,
        })
      : { checks: [] as DraftCheck[], overlaySheets: [] as OverlaySheet[], notes: [] as string[] }
    const sheetResult = await carouselPreview({
      request,
      localFiles,
      localMedia,
      localProvenance,
      draftDir,
      readiness: response.readiness,
      specNotes,
      pixel,
      ...(pacing ? { pacing } : {}),
      ...(slideshow ? { spec: { path: slideshow.path, slides: slideshow.spec.slides.length, textFields: listTextFields(slideshow.spec).length } } : {}),
      log,
    })
    // A failing check means the slides get fixed and re-previewed first, so the video is not made yet.
    if (!carouselVideo || !sheetResult.passed) return sheetResult
    const video = await renderDraft({ spec, localFiles, outDir: draftDir }, log)
    if (!video.ok || !video.manifest) {
      return { ok: false, rendered: false, error: `The slideshow video could not be rendered: ${video.error ?? 'the draft render failed.'}`, draftDir }
    }
    const mp4 = video.manifest.frames.find((f) => f.contentType === 'video/mp4')
    if (!mp4) {
      return { ok: false, rendered: false, error: 'The slideshow render finished but produced no video.', draftDir }
    }
    const videoPath = join(draftDir, mp4.file)
    const total = mp4.durationSeconds ?? pacing?.totalSeconds
    const advisories = sheetResult.checks.filter((c) => c.severity !== 'error')
    return {
      ...sheetResult,
      files: [...sheetResult.files, { placementKey: mp4.placementKey, file: videoPath, ...(mp4.width !== undefined ? { width: mp4.width } : {}), ...(mp4.height !== undefined ? { height: mp4.height } : {}) }],
      slideshowVideoPath: videoPath,
      ...(total !== undefined ? { slideshowDurationSeconds: total } : {}),
      warnings: [...sheetResult.warnings, ...video.manifest.warnings],
      nextStep:
        `Nothing has been saved to Conductor. The slideshow video (${total !== undefined ? `${total}s, ` : ''}with the track) is at ${videoPath}; ` +
        (sheetResult.overlaySheets
          ? `the image is the slides with the Instagram UI drawn over them (the clean contact sheet is at ${sheetResult.sheets?.[0]?.file ?? draftDir}). Show the image`
          : 'the contact sheet shows the slides. Show the contact sheet') +
        ' and give the person the video path to watch (check the music level, the fade and the pacing)' +
        (total !== undefined ? `; say the total length (${total} s) when showing it` : '') +
        ', and check the order, the crop and any burned-in text. Resolve any blocking readiness item you can fix now (caption, alt text, photoChecked, the track\'s source and licence). ' +
        (advisories.length ? `Warnings (they do not block, but tell the person when you show it): ${describeChecks(advisories)} ` : '') +
        'Only after they approve, call commit_creative_draft({draftDir, photoChecked: true, source, licence}) with the track\'s provenance; to change something, call preview_creative_draft again.',
    }
  }

  const rendered = await renderDraft({ spec, localFiles, outDir: draftDir }, log)
  if (!rendered.ok || !rendered.manifest) {
    return { ok: false, rendered: false, error: rendered.error ?? 'The draft render failed.', draftDir }
  }
  const manifest: DraftManifest = rendered.manifest

  const files = manifest.frames.map((f) => ({
    placementKey: f.placementKey,
    ...(f.index !== undefined ? { index: f.index } : {}),
    file: join(draftDir, f.file),
    ...(f.width !== undefined ? { width: f.width } : {}),
    ...(f.height !== undefined ? { height: f.height } : {}),
  }))

  // The contact sheet when there is one; in a full render, the first frame (its poster, for a video).
  const shown = manifest.frames.find((f) => f.placementKey === 'sheet') ?? manifest.frames[0]
  let image: PreviewCreativeDraftSuccess['image']
  let note: string | undefined
  if (shown) {
    const shownFile = join(draftDir, shown.poster ?? shown.file)
    const bytes = await readFile(shownFile)
    if (bytes.byteLength > MAX_INLINE_IMAGE_BYTES) {
      note = `The image is ${Math.round(bytes.byteLength / 1024)} KB, larger than this tool can inline (~1 MB) — open ${shownFile} directly.`
    } else {
      const mimeType = shown.poster ? 'image/jpeg' : shown.contentType.startsWith('image/') ? shown.contentType : undefined
      if (mimeType) image = { data: bytes, mimeType }
    }
  }
  const spec2 = spec as { creative?: { kind?: string; motion?: { durationSec?: number } } }
  if (!full && spec2.creative?.kind === 'MOTION') {
    const secs = spec2.creative.motion?.durationSec ?? 8
    note = `${secs}s animation — this image shows three key moments across the timeline, not the finished video.${note ? ` ${note}` : ''}`
  }

  // A 9x16 or story frame image (a full preview) also gets the platform UI drawn over it, on disk only; the
  // contact sheet stays the image shown. A contact-sheet-only preview has no frame to draw on, so it is skipped.
  const overlaySheets: OverlaySheet[] = []
  if (platformsFor(params.uiOverlay).length) {
    for (const key of ['9x16', 'story']) {
      const frames = manifest.frames
        .filter((f) => f.placementKey === key)
        .map((f) => ({ f, file: f.contentType.startsWith('image/') ? f.file : f.poster }))
        .filter((x): x is { f: DraftManifest['frames'][number]; file: string } => !!x.file)
      if (!frames.length) continue
      const written = await writeOverlaySheets(
        frames.map(({ f, file }) => ({ png: join(draftDir, file), label: f.index !== undefined ? `${key} ${f.index + 1}` : key })),
        platformsFor(params.uiOverlay),
        draftDir,
        `overlay-${key}`,
        key,
        options,
      )
      overlaySheets.push(...written.sheets)
    }
  }

  // A full draft render already failed (ok: false) on any error, so reaching here with no `checks` means
  // every placement was rendered and checked.
  const checks = manifest.checks ?? []
  const failing = checks.filter((c) => c.severity === 'error')
  const passed = manifest.passed ?? failing.length === 0
  const advisories = checks.filter((c) => c.severity !== 'error')

  return {
    ok: true,
    committed: false,
    draftDir,
    previewOnly: !full,
    ...(image ? { image } : {}),
    files,
    ...(overlaySheets.length ? { overlaySheets } : {}),
    readiness: response.readiness,
    warnings: manifest.warnings,
    checks,
    passed,
    provenance: {
      recorded: Object.keys(localProvenance),
      needed: Object.keys(localFiles).filter((k) => !localProvenance[k]),
    },
    ...(note ? { note } : {}),
    nextStep: passed
      ? 'Nothing has been saved to Conductor. All placement checks passed. Show the image to the person and ask whether to approve it. ' +
        'Only after they approve, call commit_creative_draft({draftDir}); to change something, call preview_creative_draft again.' +
        (advisories.length
          ? ` Warnings (they do not block, but tell the person when you show the image): ${summariseChecks(advisories)}`
          : '')
      : `NOT ready for approval: ${failing.length} placement check${failing.length === 1 ? '' : 's'} failed (${summariseChecks(failing)}) — ` +
        'the full render would fail them after the person approved. Nothing has been saved. Fix the design (shorter copy, a different layout, ' +
        'layoutOverrides, typeOverrides, or drop the body) and call preview_creative_draft again until `passed` is true. Do not ask the person to ' +
        'approve and do not call commit_creative_draft while a check fails; tell them what you changed.',
  }
}

// --- commit_creative_draft ---------------------------------------------------------------

export interface CommitCreativeDraftParams {
  draftDir: string
  /** Provenance recorded on every uploaded file (optional; see upload_creative_media). */
  source?: string
  licence?: string
  aiGenerated?: boolean
  /** True only when the person approved the draft after looking at it: records that the uploaded photo(s) carry no burned-in text, logo or claim. */
  photoChecked?: boolean
}

export interface CommitCreativeDraftResult {
  ok: boolean
  creativeId: string
  displayId?: string
  version?: number
  frames: unknown[]
  readiness?: unknown
  uploaded: Record<string, string>
  /** CAROUSEL: whether the slideshow spec was stored with the Creative, and how many of its images were uploaded for it. */
  slideshowSpec?: { attached: boolean; images: number }
  warnings: string[]
  renderError?: string
  nextStep: string
}

/**
 * Commits an approved draft: uploads each local file, creates (or, with a baseCreativeId, updates) the
 * Creative with the real media ids swapped in, then runs the full local render. The draft is marked
 * committed as soon as the Creative exists — before the render — so a failed render can never lead to
 * a second commit creating a duplicate; re-run render_creative instead.
 */
export async function commitCreativeDraft(
  params: CommitCreativeDraftParams,
  config: Config,
  options: DraftToolOptions = {}
): Promise<CommitCreativeDraftResult> {
  const log = options.log ?? (() => {})
  const projectRoot = options.projectRoot ?? findProjectRoot()
  const draftDir = resolve(params.draftDir)
  await pruneDrafts(projectRoot, new Date(), [draftDir])

  const draft = await readDraftFile(draftDir)
  if (!draft) throw new Error(`No draft.json found in ${draftDir} — pass the draftDir preview_creative_draft returned.`)
  if (draft.request?.kind === 'CLIP') {
    throw new Error(
      'This is a CLIP preview, which has no commit step. Upload the clip with upload_creative_media, then create_creative with kind CLIP.'
    )
  }
  if (draft.committed) {
    throw new Error(
      `This draft was already committed${draft.creativeId ? ` (Creative ${draft.creativeId})` : ''}. ` +
        'Preview a new draft to make further changes, or use update_creative.'
    )
  }
  if (draft.projectId && draft.projectId !== config.projectId) {
    throw new Error(`This draft was made for project ${draft.projectId}, not the active project ${config.projectId}.`)
  }

  // Check every file is still there before the first upload, so a missing one leaves nothing half-done.
  for (const [key, path] of Object.entries(draft.localFiles)) {
    if (draft.uploaded?.[key]) continue
    try {
      await stat(path)
    } catch {
      throw new Error(`The file for local:${key} no longer exists: ${path}`)
    }
  }

  // Same for the slideshow spec's image files.
  if (draft.slideshowSpec) {
    for (const path of specLocalImages(draft.slideshowSpec)) {
      if (draft.specImages?.[path]) continue
      try {
        await stat(path)
      } catch {
        throw new Error(`The slideshow spec's image no longer exists: ${path}`)
      }
    }
  }

  const warnings: string[] = []
  const uploaded: Record<string, string> = { ...(draft.uploaded ?? {}) }
  const specImages: Record<string, string> = { ...(draft.specImages ?? {}) }
  const persist = () => writeFile(join(draftDir, 'draft.json'), JSON.stringify({ ...draft, uploaded, specImages }, null, 2))
  for (const [key, path] of Object.entries(draft.localFiles)) {
    if (uploaded[key]) continue
    // A file's own sidecar provenance wins; the call's params cover files without one.
    const own = draft.localProvenance?.[key]
    const result = await uploadCreativeMedia(
      own ? { filePath: path, ...own } : { filePath: path, source: params.source, licence: params.licence, aiGenerated: params.aiGenerated },
      config
    )
    const id = result.media['id'] as string | undefined
    if (!id) throw new Error(`Uploading ${basename(path)} returned no media id.`)
    uploaded[key] = id
    warnings.push(...result.warnings)
    // Persist as we go: a failure further on must not re-upload what already landed.
    await persist()
  }

  if (params.photoChecked === true) {
    // Only files this draft uploaded (never library photos); re-PATCHing on a retry is idempotent.
    for (const [key, id] of Object.entries(uploaded)) {
      if (draft.localMedia?.[key]?.kind !== 'IMAGE') continue
      await apiPatch(`${V2_PROJECT(config)}/marketing/photos/${id}`, { checked: true }, config)
    }
  }

  const fields = replaceLocalRefs(draft.request, (key) => {
    const id = uploaded[key]
    if (!id) throw new Error(`The draft references local:${key} but no file was uploaded for it.`)
    return id
  }) as Record<string, unknown>

  // The slideshow spec: each distinct input image (background photos, screenshots, grid and week images) is
  // uploaded once, with its own sidecar provenance when it has one, and the spec goes with the Creative as media refs.
  let specImagesUploaded = 0
  if (draft.slideshowSpec) {
    const stored = await uploadSpecImages(draft.slideshowSpec, config, {
      fallback: { source: params.source, licence: params.licence, aiGenerated: params.aiGenerated },
      done: specImages,
      onUploaded: persist,
    })
    specImagesUploaded = stored.uploaded
    warnings.push(...stored.warnings)
    fields['slideshowSpec'] = stored.spec
  }

  let created: Record<string, unknown>
  if (draft.baseCreativeId) {
    const current = await getCreative({ creativeId: draft.baseCreativeId }, config)
    created = await updateCreative(
      { ...(fields as CreativeFields), creativeId: draft.baseCreativeId, version: current['version'] as number },
      config
    )
  } else {
    created = await createCreative(fields as CreativeFields, config)
  }
  const creativeId = (created['id'] as string | undefined) ?? draft.baseCreativeId
  if (!creativeId) throw new Error('The Creative was saved but the response carried no id.')

  await writeFile(
    join(draftDir, 'draft.json'),
    JSON.stringify({ ...draft, uploaded, specImages, committed: true, committedAt: new Date().toISOString(), creativeId }, null, 2)
  )
  if (draft.slideshowSpec && !created['slideshowSpec']) {
    warnings.push('The server did not return slideshowSpec on the saved Creative; it may be an older backend that ignores the field. Check with get_creative.')
  }

  // A CAROUSEL is finished images: the server assembles its frames in one call, unless it has a track,
  // in which case the slideshow MP4 is encoded here (renderCreative routes it, checking the saved audio).
  const isCarousel = draft.request['kind'] === 'CAROUSEL' || created['kind'] === 'CAROUSEL'
  const savedAudio = (created['audio'] ?? draft.request['audio']) as { source?: string } | null | undefined
  const rendered = isCarousel && !needsLocalRender('CAROUSEL', savedAudio)
    ? await renderViaApi({ creativeId, previewOnly: false, renderer: 'mcp' }, config)
    : await runLocalRender({ creativeId, previewOnly: false, renderer: 'mcp' }, config, log)
  const verified = await getCreative({ creativeId }, config).catch(() => undefined)

  const renderError = rendered.ok ? undefined : rendered.error ?? 'The render failed.'
  return {
    ok: rendered.ok,
    creativeId,
    displayId: (verified?.['displayId'] ?? created['displayId']) as string | undefined,
    version: (verified?.['version'] ?? created['version']) as number | undefined,
    frames: rendered.frames,
    readiness: verified?.['readiness'],
    uploaded,
    ...(draft.slideshowSpec ? { slideshowSpec: { attached: !!created['slideshowSpec'], images: specImagesUploaded } } : {}),
    warnings,
    ...(renderError ? { renderError } : {}),
    nextStep: rendered.ok
      ? 'Creative saved and rendered. Fix anything readiness lists, then attach_creative_to_post with the Post id.'
      : `The Creative is saved but its render failed (${renderError}). Call render_creative({creativeId}) to retry — do not commit this draft again.`,
  }
}
