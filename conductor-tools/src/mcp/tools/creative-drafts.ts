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
  /** A video clip on this machine; becomes the MOTION background clip (`local:clip`). */
  clipPath?: string
  /** An audio file on this machine; becomes the audio track of a MOTION or CAROUSEL (`local:audio`). */
  audioPath?: string
  /** CAROUSEL only: the slides as local image files (or existing media ids), ordered per placement,
   * e.g. {"4x5": ["/art/01.png", "/art/02.png"]}; each path becomes a `local:<key>` entry of `slides`. */
  slidePaths?: Record<string, string[]>
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

export interface DraftToolOptions {
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
  /** CAROUSEL with a track only: the slideshow MP4 rendered on this machine (the full video, not a sheet). */
  slideshowVideoPath?: string
  slideshowDurationSeconds?: number
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
  const { photoPath, clipPath, audioPath, slidePaths, baseCreativeId: _b, draftDir: _d, full: _f, ...fields } = params as PreviewCreativeDraftParams & {
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
  log: (...args: unknown[]) => void
}): Promise<PreviewCreativeDraftSuccess> {
  const { request, localFiles, draftDir, localProvenance } = input
  const slides = (request['slides'] ?? {}) as Record<string, string[]>
  const SHEET_SLIDES = 10
  const sheets: Array<{ placementKey: string; file: string; slides: number }> = []
  const notes: string[] = []
  let image: PreviewCreativeDraftSuccess['image']

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
        if (!image && bytes.byteLength <= MAX_INLINE_IMAGE_BYTES) image = { data: bytes, mimeType: 'image/jpeg' }
      } catch (err) {
        notes.push(`${placement}: could not compose a contact sheet (${err instanceof Error ? err.message : String(err)}) — open the slide files directly.`)
      }
    }
  }
  if (sheets.length && !image) notes.push('The contact sheet is larger than this tool can inline (~1 MB) — open the sheet files directly.')

  return {
    ok: true,
    committed: false,
    draftDir,
    previewOnly: true,
    ...(image ? { image } : {}),
    files: sheets.map((sh) => ({ placementKey: sh.placementKey, file: sh.file })),
    sheets,
    readiness: input.readiness,
    warnings: [],
    checks: [],
    passed: true,
    provenance: {
      recorded: Object.keys(localProvenance),
      needed: Object.keys(localFiles).filter((k) => !localProvenance[k]),
    },
    ...(notes.length ? { note: notes.join(' ') } : {}),
    nextStep:
      'Nothing has been saved to Conductor, and the slides are used exactly as they are (no brand layout is applied). ' +
      'Show the contact sheet(s) to the person and check the order, the crop and any burned-in text. ' +
      'Resolve any blocking readiness item (caption, alt text, photoChecked) you can fix now. ' +
      'Only after they approve, call commit_creative_draft({draftDir, photoChecked: true}); to change something, call preview_creative_draft again.',
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

  if (params.kind === 'CLIP') {
    return {
      ok: false,
      rendered: false,
      error:
        'A CLIP Creative is a finished video used as-is — there is no artwork to render and preview. ' +
        'Look at the video itself, then upload_creative_media and create_creative with kind CLIP.',
    }
  }

  if (params.slidePaths && params.kind && params.kind !== 'CAROUSEL') {
    return { ok: false, rendered: false, error: `slidePaths is for kind CAROUSEL, not ${params.kind}.` }
  }
  let isCarousel = params.kind === 'CAROUSEL' || (!!params.slidePaths && !params.kind)
  let baseAudio: { source?: string } | null | undefined
  if (params.baseCreativeId && (isCarousel || !params.kind)) {
    // Editing a saved Creative without naming its kind: a CAROUSEL base takes the carousel path, and
    // its saved audio decides whether the slideshow video is rendered.
    try {
      const base = await apiGet<{ kind?: string; audio?: { source?: string } | null }>(`${creativesBase(config)}/${params.baseCreativeId}`, config)
      if (!isCarousel) isCarousel = base?.kind === 'CAROUSEL'
      baseAudio = base?.audio
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

  const full = !!params.full
  // A CAROUSEL with a track gets its slideshow MP4 rendered here, so its spec must be a full render spec.
  const requestAudio = request['audio'] as { source?: string; trackId?: string } | undefined
  const carouselVideo = isCarousel && needsLocalRender('CAROUSEL', requestAudio ?? baseAudio)
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
  }
  await writeFile(join(draftDir, 'draft.json'), JSON.stringify(draft, null, 2))

  if (isCarousel) {
    const sheetResult = await carouselPreview({ request, localFiles, localMedia, localProvenance, draftDir, readiness: response.readiness, log })
    if (!carouselVideo) return sheetResult
    const video = await renderDraft({ spec, localFiles, outDir: draftDir }, log)
    if (!video.ok || !video.manifest) {
      return { ok: false, rendered: false, error: `The slideshow video could not be rendered: ${video.error ?? 'the draft render failed.'}`, draftDir }
    }
    const mp4 = video.manifest.frames.find((f) => f.contentType === 'video/mp4')
    if (!mp4) {
      return { ok: false, rendered: false, error: 'The slideshow render finished but produced no video.', draftDir }
    }
    const videoPath = join(draftDir, mp4.file)
    return {
      ...sheetResult,
      files: [...sheetResult.files, { placementKey: mp4.placementKey, file: videoPath, ...(mp4.width !== undefined ? { width: mp4.width } : {}), ...(mp4.height !== undefined ? { height: mp4.height } : {}) }],
      slideshowVideoPath: videoPath,
      ...(mp4.durationSeconds !== undefined ? { slideshowDurationSeconds: mp4.durationSeconds } : {}),
      warnings: video.manifest.warnings,
      nextStep:
        `Nothing has been saved to Conductor. The slideshow video (${mp4.durationSeconds !== undefined ? `${mp4.durationSeconds}s, ` : ''}with the track) is at ${videoPath}; ` +
        'the contact sheet shows the slides. Show the contact sheet and give the person the video path to watch (check the music level, the fade and the pacing), ' +
        'and check the order, the crop and any burned-in text. Resolve any blocking readiness item you can fix now (caption, alt text, photoChecked, the track\'s source and licence). ' +
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

  const warnings: string[] = []
  const uploaded: Record<string, string> = { ...(draft.uploaded ?? {}) }
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
    await writeFile(join(draftDir, 'draft.json'), JSON.stringify({ ...draft, uploaded }, null, 2))
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
    JSON.stringify({ ...draft, uploaded, committed: true, committedAt: new Date().toISOString(), creativeId }, null, 2)
  )

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
    warnings,
    ...(renderError ? { renderError } : {}),
    nextStep: rendered.ok
      ? 'Creative saved and rendered. Fix anything readiness lists, then attach_creative_to_post with the Post id.'
      : `The Creative is saved but its render failed (${renderError}). Call render_creative({creativeId}) to retry — do not commit this draft again.`,
  }
}
