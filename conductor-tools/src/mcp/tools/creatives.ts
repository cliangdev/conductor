import { readFile, mkdtemp, writeFile, rm } from 'node:fs/promises'
import { basename, extname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch, putBytes, ApiError } from '../api.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import { probeMedia, extractPoster, type MediaProbeResult } from '../../lib/media-probe.js'
import { composePosterSheet } from '../../lib/poster-sheet.js'
import { renderCreative as runLocalRender } from '../../lib/creative-render.js'

/**
 * Conductor Creatives: a Brand Kit (tokens, copy rules, approved lines) governs one or more
 * Creatives (photo + headline + layout, optionally a story/carousel sequence and lettered
 * variants), which render locally (Playwright, driven by this MCP server) to upload-ready JPEGs
 * that attach to a Post like any other media. See docs/mcp-tool-guidelines.md's action-verify and
 * dispatch-status patterns, both used below.
 */

const V2_PROJECT = (config: Config): string => `/api/v2/projects/${config.projectId}`
const creativesBase = (config: Config): string => `${V2_PROJECT(config)}/marketing/creatives`
const experimentsBase = (config: Config): string => `${V2_PROJECT(config)}/marketing/experiments`

/** Rounds a rate-like number to 4 decimals; leaves null/undefined alone. */
function round4(n: unknown): number | null | undefined {
  if (typeof n !== 'number') return n as null | undefined
  return Math.round(n * 10000) / 10000
}

/** The fields worth an agent's context for one variant's performance — a subset of CreativePerformanceEntry. */
function trimPerformanceEntry(entry: Record<string, unknown>): Record<string, unknown> {
  return {
    creativeId: entry['creativeId'],
    label: entry['label'],
    headline: entry['headline'],
    posts: entry['posts'],
    views: entry['views'],
    engagementRate: round4(entry['engagementRate']),
    avgViewPct: round4(entry['avgViewPct']),
    views72h: entry['views72h'],
  }
}

/** The fields worth an agent's context for the family's active/last experiment. */
function trimExperiment(experiment: Record<string, unknown>): Record<string, unknown> {
  return {
    id: experiment['id'],
    state: experiment['state'],
    metric: experiment['metric'],
    windowHours: experiment['windowHours'],
    winnerCreativeId: experiment['winnerCreativeId'],
    decidedAt: experiment['decidedAt'],
    summary: experiment['summary'],
    winnerLineConfirmedAt: experiment['winnerLineConfirmedAt'],
  }
}

/**
 * The backend's 422 body for a Creative validation failure is `{ message, violations: [{ field,
 * ruleId, message }] }` — not the RFC 7807 `detail`/`title` shape `ApiError` otherwise expects, so
 * its `message` ends up holding the raw JSON text. This re-parses that into one readable string
 * (`[field] message`, one per violation) so the model gets something it can act on directly rather
 * than a JSON string to parse itself. A 409 (stale `version`) already carries a plain-English
 * `detail` and passes through unchanged.
 */
async function withCreativeErrors<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn()
  } catch (err) {
    if (err instanceof ApiError && (err.status === 422 || err.status === 409)) {
      let violations: Array<{ field?: string; ruleId?: string; message: string }> | undefined
      try {
        const parsed = JSON.parse(err.message) as { violations?: unknown }
        if (Array.isArray(parsed.violations)) violations = parsed.violations as typeof violations
      } catch {
        // Not JSON (e.g. a plain 409 conflict message) — use it as-is below.
      }
      const message = violations
        ? violations.map((v) => `[${v.field ?? v.ruleId ?? '?'}] ${v.message}`).join('; ')
        : err.message
      throw new ApiError(err.status, message, { code: err.code, title: err.title })
    }
    throw err
  }
}

// --- Brand Kit ------------------------------------------------------------

export async function getBrandKit(params: { kitId?: string }, config: Config): Promise<Record<string, unknown>> {
  if (params.kitId) {
    return apiGet(`${V2_PROJECT(config)}/marketing/brand-kits/${params.kitId}`, config)
  }
  const kits = await apiGet<Array<Record<string, unknown>>>(`${V2_PROJECT(config)}/marketing/brand-kits`, config)
  const found = kits.find((k) => k['isDefault']) ?? kits[0]
  if (!found) {
    throw new Error('This project has no Brand Kit yet — one is created for every workspace by default; check Settings.')
  }
  return found
}

// --- Creatives --------------------------------------------------------------

export async function listCreatives(
  params: { state?: string; brandKitId?: string },
  config: Config
): Promise<Array<Record<string, unknown>>> {
  const query = new URLSearchParams()
  if (params.state) query.set('state', params.state)
  if (params.brandKitId) query.set('brandKitId', params.brandKitId)
  const qs = query.toString()
  return apiGet(`${creativesBase(config)}${qs ? `?${qs}` : ''}`, config)
}

export async function getCreative(params: { creativeId: string }, config: Config): Promise<Record<string, unknown>> {
  const [creative, readiness, all, performance] = await Promise.all([
    apiGet<Record<string, unknown>>(`${creativesBase(config)}/${params.creativeId}`, config),
    apiGet<Record<string, unknown>>(`${creativesBase(config)}/${params.creativeId}/readiness`, config),
    apiGet<Array<Record<string, unknown>>>(creativesBase(config), config),
    apiGet<Record<string, unknown>>(`${creativesBase(config)}/${params.creativeId}/performance`, config),
  ])
  const variants = all
    .filter((c) => c['number'] === creative['number'] && c['id'] !== creative['id'])
    .map((c) => ({ id: c['id'], displayId: c['displayId'], state: c['state'], headline: c['headline'] }))

  const activeExperimentId = creative['activeExperimentId'] as string | null | undefined
  let experiment: Record<string, unknown> | undefined
  if (activeExperimentId) {
    experiment = await apiGet<Record<string, unknown>>(`${experimentsBase(config)}/${activeExperimentId}`, config)
  } else {
    const experiments = await apiGet<Array<Record<string, unknown>>>(
      `${experimentsBase(config)}?creativeId=${params.creativeId}`,
      config
    )
    experiment = experiments[0]
  }

  const family = (performance['family'] as Array<Record<string, unknown>> | undefined) ?? []
  return {
    ...creative,
    readiness,
    variants,
    performance: family.map(trimPerformanceEntry),
    experiment: experiment ? trimExperiment(experiment) : null,
  }
}

/**
 * Starts a hook experiment on a Creative family (>= 2 lettered variants; one RUNNING experiment per
 * family at a time — a second attempt is refused with a 409). It settles on its own once every variant
 * has published and reported (or via the weekly "what's working" job) — nothing further to call. Read
 * the result through get_creative, whose experiment field carries the current state, and once DECIDED,
 * the winner and its numbers.
 */
export async function createExperiment(
  params: { creativeId: string; metric?: string; windowHours?: number },
  config: Config
): Promise<Record<string, unknown>> {
  return withCreativeErrors(() =>
    apiPost(
      experimentsBase(config),
      { creativeId: params.creativeId, metric: params.metric, windowHours: params.windowHours },
      config
    )
  )
}

export interface CreativeLayoutOverrides {
  band?: Record<string, number>
  padBottom?: Record<string, number>
}

export interface CreativeFields {
  brandKitId?: string
  name?: string
  state?: string
  /** STILL (default): brand-rendered photo/headline artwork. CLIP: a finished video used as-is, via
   * clipMedia — no brand layout, no photo/headline required. MOTION: the same brand layout animated
   * into a short video — set `motion` (and optionally `audio`) too. */
  kind?: string
  layout?: string
  theme?: string
  photoId?: string
  /** CLIP only: media ids per placement, e.g. {"default": mediaId, "9x16": mediaId}. "default"
   * covers any placement without its own entry. */
  clipMedia?: Record<string, string>
  /** MOTION only: the animation timeline (preset, duration, background motion/clip, end card) —
   * validated server-side; see the create_creative/update_creative tool schemas for the shape. */
  motion?: Record<string, unknown>
  /** MOTION only: the audio track (clip's own sound, a library track, or none) — validated
   * server-side; see the create_creative/update_creative tool schemas for the shape. */
  audio?: Record<string, unknown>
  focalOverride?: Record<string, string>
  headline?: string
  body?: string
  caption?: string
  altText?: string
  placements?: string[]
  sequenceKind?: string
  sequence?: Array<Record<string, unknown>>
  carouselRatio?: string
  /** 'plain' (default) or 'chip' — puts the logo lockup on a white pill, for busy photography. */
  lockup?: string
  /** Per-placement overrides of layout-derived numbers, in pixels — see conductor-creative/README.md. */
  layoutOverrides?: CreativeLayoutOverrides
}

export interface CreateCreativeParams extends CreativeFields {
  /** Cuts a lettered variant of this existing Creative instead of creating a fresh one — everything
   * else on this call except `headline` and `name` is ignored, matching the variants endpoint. */
  variantOf?: string
}

export async function createCreative(params: CreateCreativeParams, config: Config): Promise<Record<string, unknown>> {
  return withCreativeErrors(async () => {
    if (params.variantOf) {
      return apiPost(
        `${creativesBase(config)}/${params.variantOf}/variants`,
        { headline: params.headline, name: params.name },
        config
      )
    }
    return apiPost(creativesBase(config), { ...params, variantOf: undefined }, config)
  })
}

export interface UpdateCreativeParams extends CreativeFields {
  creativeId: string
  version: number
}

export async function updateCreative(params: UpdateCreativeParams, config: Config): Promise<Record<string, unknown>> {
  const { creativeId, ...patch } = params
  return withCreativeErrors(() => apiPatch(`${creativesBase(config)}/${creativeId}`, patch, config))
}

// --- Media (photo library: photos, video, audio) -----------------------------

const VIDEO_LENGTH_WARNING_SECONDS = 180

interface MediaSource {
  bytes: Uint8Array
  filename: string
  /** Set only when the bytes already live at a real path on disk (a given filePath) — probeMedia and
   * extractPoster need an actual file, so a URL download without one gets written to a temp file lazily,
   * only once we know it isn't an image (readImageDimensions works on bytes alone). */
  filePath?: string
}

async function readMediaSource(params: { filePath?: string; url?: string }): Promise<MediaSource> {
  if (params.filePath) {
    return { bytes: new Uint8Array(await readFile(params.filePath)), filename: basename(params.filePath), filePath: params.filePath }
  }
  const raw = params.url as string
  let parsed: URL
  try {
    parsed = new URL(raw)
  } catch {
    throw new Error(`"${raw}" is not a valid URL`)
  }
  if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
    throw new Error(`Only http(s) URLs can be fetched, not ${parsed.protocol}`)
  }
  const response = await fetch(parsed)
  if (!response.ok) {
    throw new Error(`Fetching ${raw} failed: HTTP ${response.status}`)
  }
  const bytes = new Uint8Array(await response.arrayBuffer())
  return { bytes, filename: basename(parsed.pathname) || 'media' }
}

function stripExtension(filename: string): string {
  const ext = extname(filename)
  return ext ? filename.slice(0, -ext.length) : filename
}

/** The fields worth an agent's context for one uploaded media item — trims whatever else the
 * backend's photo library response carries (checked/blocked review metadata, timestamps, etc.). */
const MEDIA_RESULT_FIELDS = [
  'id', 'label', 'mediaKind', 'contentType', 'width', 'height', 'durationSeconds', 'hasAudio',
  'posterUrl', 'url', 'sizeBytes', 'source', 'licence', 'aiGenerated', 'uploadStatus', 'checked', 'blocked',
] as const

function trimMedia(media: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const key of MEDIA_RESULT_FIELDS) {
    if (key in media) out[key] = media[key]
  }
  return out
}

async function uploadPoster(mediaId: string, localVideoPath: string, config: Config, warnings: string[]): Promise<void> {
  const posterPath = join(tmpdir(), `conductor-poster-${mediaId}.jpg`)
  try {
    await extractPoster(localVideoPath, posterPath, 1)
    const posterBytes = await readFile(posterPath)
    const posterMint = await apiPost<{ uploadUrl: string; gcsPath: string }>(
      `${V2_PROJECT(config)}/marketing/photos/${mediaId}/poster`,
      {},
      config
    )
    await putBytes(posterMint.uploadUrl, 'image/jpeg', posterBytes)
    await apiPost(`${V2_PROJECT(config)}/marketing/photos/${mediaId}/poster/confirm`, { gcsPath: posterMint.gcsPath }, config)
  } catch (err) {
    warnings.push(`Could not extract or upload a poster frame: ${err instanceof Error ? err.message : String(err)}`)
  } finally {
    await rm(posterPath, { force: true }).catch(() => {})
  }
}

export interface UploadCreativeMediaParams {
  filePath?: string
  url?: string
  label?: string
  source?: string
  licence?: string
  aiGenerated?: boolean
}

export interface UploadCreativeMediaResult {
  media: Record<string, unknown>
  warnings: string[]
}

/**
 * Uploads a photo, video or audio file into the project's media library from a local path or a
 * public URL, detecting its kind, dimensions/duration/audio automatically, and — for a video —
 * extracting and uploading a poster frame. Shared by `upload_creative_media` and the deprecated
 * `upload_creative_photo` alias, so the two never drift.
 */
export async function uploadCreativeMedia(params: UploadCreativeMediaParams, config: Config): Promise<UploadCreativeMediaResult> {
  if (!params.filePath && !params.url) {
    throw new Error('Pass filePath (a file on this machine) or url (a public http(s) URL).')
  }
  const source = await readMediaSource(params)
  const label = params.label ?? stripExtension(source.filename)
  const warnings: string[] = []

  const imageInfo = readImageDimensions(source.bytes)
  if (imageInfo) {
    const mint = await apiPost<{ id: string; uploadUrl: string | null }>(
      `${V2_PROJECT(config)}/marketing/photos`,
      {
        label,
        contentType: imageInfo.contentType,
        sizeBytes: source.bytes.byteLength,
        width: imageInfo.width,
        height: imageInfo.height,
        source: params.source ?? null,
        licence: params.licence ?? null,
        aiGenerated: !!params.aiGenerated,
      },
      config
    )
    if (!mint.uploadUrl) {
      throw new Error('The mint response carried no uploadUrl — cannot upload bytes.')
    }
    await putBytes(mint.uploadUrl, imageInfo.contentType, source.bytes)
    const confirmed = await apiPost<Record<string, unknown>>(
      `${V2_PROJECT(config)}/marketing/photos/${mint.id}/confirm`,
      { sizeBytes: source.bytes.byteLength },
      config
    )
    return { media: trimMedia(confirmed), warnings }
  }

  // Not an image — probe it as video/audio, which needs a real file on disk.
  let probePath = source.filePath
  let tempDir: string | undefined
  if (!probePath) {
    tempDir = await mkdtemp(join(tmpdir(), 'conductor-media-'))
    probePath = join(tempDir, source.filename || 'media')
    await writeFile(probePath, source.bytes)
  }

  let probe: MediaProbeResult
  try {
    probe = await probeMedia(probePath)
  } catch (err) {
    if (tempDir) await rm(tempDir, { recursive: true, force: true }).catch(() => {})
    throw new Error(
      `Could not read "${source.filename}" as a photo, video or audio file: ${err instanceof Error ? err.message : String(err)}`
    )
  }
  if (probe.kind !== 'VIDEO' && probe.kind !== 'AUDIO') {
    if (tempDir) await rm(tempDir, { recursive: true, force: true }).catch(() => {})
    throw new Error(`"${source.filename}" is not a recognizable photo, video or audio file.`)
  }
  if (probe.kind === 'VIDEO' && probe.durationSeconds && probe.durationSeconds > VIDEO_LENGTH_WARNING_SECONDS) {
    warnings.push(`This video is ${Math.round(probe.durationSeconds)}s long — longer than most platforms take.`)
  }

  try {
    const mint = await apiPost<{ id: string; uploadUrl: string | null }>(
      `${V2_PROJECT(config)}/marketing/photos`,
      {
        mediaKind: probe.kind,
        label,
        contentType: probe.contentType,
        sizeBytes: source.bytes.byteLength,
        width: probe.width ?? null,
        height: probe.height ?? null,
        durationSeconds: probe.durationSeconds ?? null,
        hasAudio: probe.kind === 'VIDEO' ? !!probe.hasAudio : undefined,
        source: params.source ?? null,
        licence: params.licence ?? null,
        aiGenerated: !!params.aiGenerated,
      },
      config
    )
    if (!mint.uploadUrl) {
      throw new Error('The mint response carried no uploadUrl — cannot upload bytes.')
    }
    await putBytes(mint.uploadUrl, probe.contentType, source.bytes)
    const confirmed = await apiPost<Record<string, unknown>>(
      `${V2_PROJECT(config)}/marketing/photos/${mint.id}/confirm`,
      { sizeBytes: source.bytes.byteLength },
      config
    )

    let finalMedia = confirmed
    if (probe.kind === 'VIDEO') {
      await uploadPoster(mint.id, probePath, config, warnings)
      finalMedia = await apiGet<Record<string, unknown>>(`${V2_PROJECT(config)}/marketing/photos/${mint.id}`, config).catch(
        () => confirmed
      )
    }
    return { media: trimMedia(finalMedia), warnings }
  } finally {
    if (tempDir) await rm(tempDir, { recursive: true, force: true }).catch(() => {})
  }
}

/** Deprecated alias for {@link uploadCreativeMedia}, kept so existing callers naming the old tool
 * still work — same implementation, same behavior, including for photos. */
export const uploadCreativePhoto = uploadCreativeMedia

// --- Rendering ----------------------------------------------------------------

interface RenderApiFrame {
  id?: string
  placementKey: string
  platform?: string
  sequenceIndex?: number | null
  url?: string
  width?: number
  height?: number
  sizeBytes?: number
  warnings?: string[]
  /** CLIP frames only — the frame is a video, and these describe it. */
  durationSeconds?: number
  hasAudio?: boolean
  posterUrl?: string
  contentType?: string
}

interface RenderApiItem {
  id: string
  state: string
  creativeVersion?: number
  previewOnly?: boolean
  error?: string | null
  frames?: RenderApiFrame[]
}

export async function renderCreativeTool(
  params: { creativeId: string; previewOnly?: boolean; renderer?: string; workflowRunId?: string },
  config: Config,
  onProgress?: (message: string) => void
): Promise<Record<string, unknown>> {
  // A MOTION render can run for a while (roughly 20s per placement for an 8s video, streamed one
  // ffmpeg progress line every ~25% per placement) — this tool call is still synchronous, but the
  // collected lines are returned so the caller sees what happened rather than just a final result.
  const progress: string[] = []
  const result = await runLocalRender(
    { creativeId: params.creativeId, previewOnly: !!params.previewOnly, renderer: params.renderer ?? 'mcp', workflowRunId: params.workflowRunId },
    config,
    (...args: unknown[]) => {
      const line = args.map((a) => (typeof a === 'string' ? a : String(a))).join(' ')
      progress.push(line)
      onProgress?.(line)
    }
  )
  return {
    renderId: result.renderId,
    state: result.state,
    ok: result.ok,
    error: result.error,
    frames: result.frames,
    ...(progress.length ? { log: progress.join('\n') } : {}),
    nextStep: result.ok
      ? 'Call preview_creative to look at the render, or get_creative for the stored frame list.'
      : 'Fix what `error` names and call render_creative again.',
  }
}

const MAX_INLINE_IMAGE_BYTES = 1_000_000 // ~1MB — no downscale available without an image library

export interface PreviewCreativeResult {
  renderId?: string
  state?: string
  url?: string
  image?: { data: Buffer; mimeType: string }
  note?: string
}

// Only a sheet rendered from the creative as it is now is worth showing: an older one would show the model
// an ad it has since edited. One too large to inline is skipped too, so a fresh (small) one is rendered.
function findCurrentSheetFrame(
  renders: RenderApiItem[],
  version: number | undefined
): { render: RenderApiItem; frame: RenderApiFrame } | undefined {
  for (const render of renders) {
    if (render.state !== 'SUCCEEDED') continue
    if (version !== undefined && render.creativeVersion !== version) continue
    const frame = (render.frames ?? []).find((f) => f.placementKey === 'sheet')
    if (frame && (frame.sizeBytes ?? 0) <= MAX_INLINE_IMAGE_BYTES) return { render, frame }
  }
  return undefined
}

// The latest SUCCEEDED, non-preview render (of the given version, when given) whose frames are
// videos (MOTION's MP4 placements, not the previewOnly 'sheet' contact sheet).
function findLatestMotionVideoRender(renders: RenderApiItem[], version: number | undefined): RenderApiItem | undefined {
  for (const render of renders) {
    if (render.state !== 'SUCCEEDED' || render.previewOnly) continue
    if (version !== undefined && render.creativeVersion !== version) continue
    const frames = render.frames ?? []
    if (frames.some((f) => f.durationSeconds != null || f.contentType === 'video/mp4')) return render
  }
  return undefined
}

/**
 * The text note shown alongside a MOTION creative's key-moments sheet: its animation duration, and
 * — when a full (non-preview) render of the SAME version already exists — the rendered MP4 URL per
 * placement, so a caller doesn't have to make a second call just to find them.
 */
function motionPreviewNote(
  motion: { durationSec?: number } | undefined,
  renders: RenderApiItem[],
  version: number | undefined
): string {
  const durationSec = motion?.durationSec ?? 8
  let note = `${durationSec}s animation — this image shows three key moments across the timeline, not the finished video.`
  const videoRender = findLatestMotionVideoRender(renders, version)
  if (videoRender) {
    const lines = (videoRender.frames ?? [])
      .filter((f) => f.url)
      .map((f) => `${f.placementKey}: ${f.url} (${f.durationSeconds ?? durationSec}s${f.hasAudio ? ', with audio' : ''})`)
    if (lines.length) {
      note += ` The current version already has a rendered video:\n${lines.join('\n')}`
    }
  }
  return note
}

// The latest SUCCEEDED render (of the creative's current version) that actually has frames — CLIP has
// no dedicated 'sheet' frame the way a STILL previewOnly render does, so any frame set will do.
function findLatestRenderWithFrames(renders: RenderApiItem[], version: number | undefined): RenderApiItem | undefined {
  for (const render of renders) {
    if (render.state !== 'SUCCEEDED') continue
    if (version !== undefined && render.creativeVersion !== version) continue
    if (render.frames && render.frames.length) return render
  }
  return undefined
}

/**
 * CLIP preview: there is no server-rendered contact sheet (previewOnly renders 422 for CLIP), so this
 * downloads each placement frame's poster JPEG and composes them into one small sheet locally with
 * ffmpeg (see poster-sheet.ts), falling back to just the first poster on its own if composition fails
 * — showing one frame beats showing nothing. Frame count is placement count, so it's always at least 1.
 */
async function previewClip(
  creativeId: string,
  version: number | undefined,
  renders: RenderApiItem[],
  config: Config
): Promise<PreviewCreativeResult> {
  let render = findLatestRenderWithFrames(renders, version)
  if (!render) {
    // previewOnly renders are refused for CLIP — the only way to get frames is a real render.
    const rendered = await runLocalRender({ creativeId, previewOnly: false, renderer: 'mcp' }, config)
    if (!rendered.ok || !rendered.frames.length) {
      return {
        renderId: rendered.renderId,
        state: rendered.state,
        note: `Rendering failed${rendered.error ? `: ${rendered.error}` : '.'} There is nothing to show.`,
      }
    }
    render = { id: rendered.renderId!, state: rendered.state ?? 'SUCCEEDED', frames: rendered.frames as RenderApiFrame[] }
  }

  const frames = (render.frames ?? []).filter((f) => f.posterUrl)
  if (!frames.length) {
    return { renderId: render.id, state: render.state, note: 'The render produced no poster frame to preview.' }
  }

  const summary = frames
    .map((f) => {
      const size = f.width && f.height ? `${f.width}x${f.height}` : ''
      const duration = f.durationSeconds != null ? `${f.durationSeconds}s` : ''
      return `${f.placementKey}: ${[size, duration].filter(Boolean).join(', ') || 'unknown'}`
    })
    .join('\n')

  const dir = await mkdtemp(join(tmpdir(), 'conductor-preview-'))
  try {
    const posterPaths: string[] = []
    let firstPoster: Buffer | undefined
    for (const frame of frames) {
      const response = await fetch(frame.posterUrl!)
      if (!response.ok) continue
      const bytes = Buffer.from(await response.arrayBuffer())
      if (!firstPoster) firstPoster = bytes
      const filePath = join(dir, `${frame.placementKey}.jpg`)
      await writeFile(filePath, bytes)
      posterPaths.push(filePath)
    }
    if (!posterPaths.length || !firstPoster) {
      return { renderId: render.id, state: render.state, note: 'Could not download any poster frame to preview.' }
    }

    try {
      const sheet = await composePosterSheet(posterPaths)
      return { renderId: render.id, state: render.state, image: { data: sheet, mimeType: 'image/jpeg' }, note: summary }
    } catch {
      return {
        renderId: render.id,
        state: render.state,
        image: { data: firstPoster, mimeType: 'image/jpeg' },
        note: `${summary}\n(Could not compose a combined sheet — showing the first placement's poster only.)`,
      }
    }
  } finally {
    await rm(dir, { recursive: true, force: true }).catch(() => {})
  }
}

/** Joins a MOTION note onto whatever note a fallback path already produced (a too-large image, a
 * download failure, ...) rather than replacing it — the caller still needs both pieces of context. */
function withMotionNote(note: string | undefined, motionNote: string | undefined): string | undefined {
  if (!motionNote) return note
  return note ? `${motionNote}\n\n${note}` : motionNote
}

export async function previewCreative(params: { creativeId: string }, config: Config): Promise<PreviewCreativeResult> {
  const [creative, renders] = await Promise.all([
    apiGet<{ version?: number; kind?: string; motion?: { durationSec?: number } }>(
      `${creativesBase(config)}/${params.creativeId}`,
      config
    ),
    apiGet<RenderApiItem[]>(`${creativesBase(config)}/${params.creativeId}/renders`, config),
  ])

  if (creative?.kind === 'CLIP') {
    return previewClip(params.creativeId, creative?.version, renders, config)
  }

  // For MOTION this note (duration, and any already-rendered MP4 URLs for the current version) is
  // worth attaching to whichever outcome below actually returns — success or a fallback message.
  const motionNote = creative?.kind === 'MOTION' ? motionPreviewNote(creative.motion, renders, creative.version) : undefined

  let found = findCurrentSheetFrame(renders, creative?.version)

  if (!found) {
    // MOTION's previewOnly render produces the same kind of 'sheet' frame as STILL's — a contact
    // sheet, just of `motionKeyTimes()` moments instead of one composition — so this call needs no
    // MOTION-specific branch.
    const rendered = await runLocalRender({ creativeId: params.creativeId, previewOnly: true, renderer: 'mcp' }, config)
    if (!rendered.ok || !rendered.renderId) {
      return {
        renderId: rendered.renderId,
        state: rendered.state,
        note: withMotionNote(`Rendering a preview failed${rendered.error ? `: ${rendered.error}` : '.'} There is nothing to show.`, motionNote),
      }
    }
    const sheetFrame = rendered.frames.find((f) => f.placementKey === 'sheet')
    if (!sheetFrame) {
      return { renderId: rendered.renderId, state: rendered.state, note: withMotionNote('The preview render produced no contact sheet frame.', motionNote) }
    }
    found = { render: { id: rendered.renderId, state: rendered.state ?? 'SUCCEEDED' }, frame: sheetFrame }
  }

  const { render, frame } = found
  if (!frame.url) {
    return { renderId: render.id, state: render.state, note: withMotionNote('The contact sheet frame has no download URL yet.', motionNote) }
  }
  if ((frame.sizeBytes ?? 0) > MAX_INLINE_IMAGE_BYTES) {
    return {
      renderId: render.id,
      state: render.state,
      url: frame.url,
      note: withMotionNote(
        `The rendered image is ${Math.round((frame.sizeBytes ?? 0) / 1024)} KB, larger than this tool can inline (~1 MB) — open the URL directly instead.`,
        motionNote
      ),
    }
  }

  const response = await fetch(frame.url)
  if (!response.ok) {
    return {
      renderId: render.id,
      state: render.state,
      url: frame.url,
      note: withMotionNote(`Could not download the frame (HTTP ${response.status}) — open the URL directly instead.`, motionNote),
    }
  }
  const bytes = new Uint8Array(await response.arrayBuffer())
  if (bytes.byteLength > MAX_INLINE_IMAGE_BYTES) {
    return {
      renderId: render.id,
      state: render.state,
      url: frame.url,
      note: withMotionNote('The rendered image is larger than this tool can inline — open the URL directly instead.', motionNote),
    }
  }
  // The `sheet` contact sheet is a 1x JPEG; the mime type comes off the response rather than being
  // hardcoded so an older PNG sheet is never mislabelled (see docs/creatives.md).
  const mimeType = (response.headers.get('content-type') || 'image/jpeg').split(';')[0].trim()
  return { renderId: render.id, state: render.state, url: frame.url, image: { data: Buffer.from(bytes), mimeType }, note: motionNote }
}

export async function attachCreativeToPost(
  params: { creativeId: string; postId?: string; workItemId?: string; renderId?: string },
  config: Config
): Promise<Record<string, unknown>> {
  // `postId` is the primary name (it matches get_post_status's own param); `workItemId` is kept as a
  // deprecated alias so existing callers don't break.
  const postId = params.postId ?? params.workItemId
  if (!postId) {
    throw new Error('postId is required (the Post\'s Work Item id).')
  }
  let renderId = params.renderId
  if (!renderId) {
    const renders = await apiGet<RenderApiItem[]>(`${creativesBase(config)}/${params.creativeId}/renders`, config)
    const latest = renders.find((r) => r.state === 'SUCCEEDED' && !r.previewOnly)
    if (!latest) {
      throw new Error(
        'No SUCCEEDED render exists for this Creative yet — call render_creative (previewOnly: false) first, or pass an explicit renderId.'
      )
    }
    renderId = latest.id
  }
  return apiPost(`${creativesBase(config)}/${params.creativeId}/attach`, { renderId, workItemId: postId }, config)
}
