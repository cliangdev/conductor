import { readFile, readdir, mkdtemp, writeFile, rm } from 'node:fs/promises'
import { basename, extname, join } from 'node:path'
import { tmpdir } from 'node:os'
import { Config } from '../config.js'
import { SlideshowStyleError, validateSlideshowStyle } from '../../lib/slideshow/style.js'
import { slugify } from './creative-drafts.js'
import { apiGet, apiPost, apiPatch, putBytes, ApiError } from '../api.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import { probeMedia, extractPoster, type MediaProbeResult } from '../../lib/media-probe.js'
import { composePosterSheet } from '../../lib/poster-sheet.js'
import { readProvenanceSidecar } from '../../lib/provenance-sidecar.js'
import { renderCreative as runLocalRender, renderViaApi } from '../../lib/creative-render.js'
import { needsLocalRender } from '../../lib/creative-local-render.js'

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
 * ruleId, message }] }` — `mcp/api.ts` already lifts that `violations` array verbatim onto the
 * `ApiError`, same as `fieldErrors` for a Brand Kit. Prefer formatting from `err.violations` when
 * present (`[field] message`, one per violation) so the model gets something it can act on directly;
 * otherwise fall back to `err.message` (the RFC 7807 `detail`, already a readable sentence — e.g. a
 * 409's stale-`version` conflict) as returned, unchanged.
 */
async function withCreativeErrors<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn()
  } catch (err) {
    if (err instanceof ApiError && (err.status === 422 || err.status === 409) && err.violations?.length) {
      const message = err.violations.map((v) => `[${v.field ?? v.ruleId ?? '?'}] ${v.message}`).join('; ')
      throw new ApiError(err.status, message, { code: err.code, title: err.title, detail: err.detail, violations: err.violations })
    }
    throw err
  }
}

// --- Brand Kit ------------------------------------------------------------

/**
 * The backend's 422 body for a Brand Kit validation failure (`PatchBrandKitRequest`/
 * `CreateBrandKitRequest`) is an RFC 7807 problem whose `detail` already names each failing field
 * (e.g. "copyRules[0].pattern: does not compile as a regex"), plus a `fieldErrors` array in the body
 * that `mcp/api.ts` lifts onto the `ApiError` verbatim. Prefer formatting from `fieldErrors` when
 * present (covers every field, not just what fit in `detail`); otherwise fall back to `detail` as
 * returned — it is already a readable sentence, not JSON to re-parse.
 */
async function withBrandKitErrors<T>(fn: () => Promise<T>): Promise<T> {
  try {
    return await fn()
  } catch (err) {
    if (err instanceof ApiError && err.status === 422 && err.fieldErrors?.length) {
      const message = err.fieldErrors.map((f) => (f.field ? `${f.field}: ${f.message}` : f.message)).join('; ')
      throw new ApiError(err.status, message, { code: err.code, title: err.title, detail: err.detail, fieldErrors: err.fieldErrors })
    }
    throw err
  }
}

/** The project's default Brand Kit — the "no kitId given" resolution shared by `getBrandKit`,
 * `updateBrandKit` and `uploadBrandImage`. */
async function resolveDefaultBrandKit(config: Config): Promise<Record<string, unknown>> {
  return (await listKitsWithDefault(config)).kit
}

async function listKitsWithDefault(config: Config): Promise<{ kit: Record<string, unknown>; kits: Array<Record<string, unknown>> }> {
  const kits = await apiGet<Array<Record<string, unknown>>>(`${V2_PROJECT(config)}/marketing/brand-kits`, config)
  const found = kits.find((k) => k['isDefault']) ?? kits[0]
  if (!found) {
    throw new Error('This project has no Brand Kit yet — one is created for every workspace by default; check Settings.')
  }
  return { kit: found, kits }
}

export async function getBrandKit(params: { kitId?: string }, config: Config): Promise<Record<string, unknown>> {
  let kit: Record<string, unknown>
  let otherKits: Array<{ id: unknown; name: unknown; configured: unknown; hasSlideshowStyle: boolean }> | undefined
  if (params.kitId) {
    kit = await apiGet<Record<string, unknown>>(`${V2_PROJECT(config)}/marketing/brand-kits/${params.kitId}`, config)
  } else {
    const listed = await listKitsWithDefault(config)
    kit = listed.kit
    // Small fields only: the caller picks by name, then passes the id as `brandKitId`.
    const others = listed.kits
      .filter((k) => k !== listed.kit)
      .map((k) => ({ id: k['id'], name: k['name'], configured: k['configured'], hasSlideshowStyle: k['slideshowStyle'] != null }))
    if (others.length > 0) otherKits = others
  }
  const base = otherKits ? { ...kit, otherKits } : kit

  // A self-hosted backend that predates this field simply won't send it — treat that as configured
  // (nothing to warn about) rather than as unset. Only an explicit `false` means "looks unset".
  if (kit['configured'] !== false) return base
  return {
    ...base,
    nextStep:
      'This Brand Kit looks unset — call update_brand_kit (name, tokens, font, CTA claim, copy rules) ' +
      'and upload_brand_image (logo) before creating a Creative with it.',
  }
}

export interface UpdateBrandKitParams {
  kitId?: string
  name?: string
  fontFamily?: string
  fontUrl?: string
  /** Merged onto the kit's existing tokens — only the keys given here change; every other existing
   * token is kept. Against a backend new enough to merge atomically server-side, this is sent as a
   * partial. Against an older one (no boolean `configured` field in the GET below — see
   * {@link updateBrandKit}), it's merged client-side from the kit this call just read and sent as the
   * full map instead, so the older backend's plain "replace tokens with what I sent" PATCH still ends
   * up correct. */
  tokens?: Record<string, string>
  ctaClaim?: string
  accentPhraseRequired?: boolean
  /** Replaces the whole list. */
  copyRules?: Array<Record<string, unknown>>
  /** Replaces the whole list. Mutually exclusive with addApprovedLines. */
  approvedLines?: string[]
  /** Appended to the existing list — atomically server-side against a backend that supports it,
   * otherwise appended (and deduped) client-side and sent as the full list. Mutually exclusive with
   * approvedLines. */
  addApprovedLines?: string[]
  enabledPlacements?: string[]
  knowledgePagePath?: string
  /** Replaces the kit's whole slideshow style (the look of composed slideshows); null clears it. */
  slideshowStyle?: Record<string, unknown> | null
}

/**
 * Always GETs the kit first — both to resolve its id when `kitId` is omitted, and to detect whether
 * this backend supports the atomic partial-merge PATCH semantics for `tokens`/`addApprovedLines` at
 * all: a boolean `configured` field in the response is the tell (it shipped alongside partial-merge
 * support), so its *absence* means an older backend that will simply overwrite `tokens`/`approvedLines`
 * with whatever this PATCH sends. That older-backend case matters in practice — the CLI can publish
 * ahead of a self-hosted or slow-to-redeploy backend — so this merges `tokens` (full map) and
 * `addApprovedLines` (appended + deduped onto the existing list) client-side from the kit just read,
 * and sends full values instead of partials, rather than losing whatever the GET didn't name.
 */
export async function updateBrandKit(params: UpdateBrandKitParams, config: Config): Promise<Record<string, unknown>> {
  if (params.approvedLines !== undefined && params.addApprovedLines !== undefined) {
    throw new Error('Pass either approvedLines (replaces the whole list) or addApprovedLines (appends), not both.')
  }

  const kit = params.kitId
    ? await apiGet<Record<string, unknown>>(`${V2_PROJECT(config)}/marketing/brand-kits/${params.kitId}`, config)
    : await resolveDefaultBrandKit(config)
  const kitId = kit['id'] as string
  const supportsPartialMerge = typeof kit['configured'] === 'boolean'

  const patch: Record<string, unknown> = {}
  if (params.name !== undefined) patch['name'] = params.name
  if (params.fontFamily !== undefined) patch['fontFamily'] = params.fontFamily
  if (params.fontUrl !== undefined) patch['fontUrl'] = params.fontUrl
  if (params.ctaClaim !== undefined) patch['ctaClaim'] = params.ctaClaim
  if (params.accentPhraseRequired !== undefined) patch['accentPhraseRequired'] = params.accentPhraseRequired
  if (params.copyRules !== undefined) patch['copyRules'] = params.copyRules
  if (params.enabledPlacements !== undefined) patch['enabledPlacements'] = params.enabledPlacements
  if (params.knowledgePagePath !== undefined) patch['knowledgePagePath'] = params.knowledgePagePath
  if (params.slideshowStyle !== undefined) {
    assertStyle(params.slideshowStyle)
    patch['slideshowStyle'] = params.slideshowStyle
  }

  if (params.tokens !== undefined) {
    if (supportsPartialMerge) {
      patch['tokens'] = params.tokens
    } else {
      const existingTokens =
        kit['tokens'] && typeof kit['tokens'] === 'object' && !Array.isArray(kit['tokens'])
          ? (kit['tokens'] as Record<string, string>)
          : {}
      patch['tokens'] = { ...existingTokens, ...params.tokens }
    }
  }

  if (params.approvedLines !== undefined) {
    // A full replacement is unambiguous either way — no merge semantics needed.
    patch['approvedLines'] = params.approvedLines
  } else if (params.addApprovedLines !== undefined) {
    if (supportsPartialMerge) {
      patch['addApprovedLines'] = params.addApprovedLines
    } else {
      const existingLines = Array.isArray(kit['approvedLines']) ? (kit['approvedLines'] as string[]) : []
      patch['approvedLines'] = [...new Set([...existingLines, ...params.addApprovedLines])]
    }
  }

  return withBrandKitErrors(() => apiPatch(`${V2_PROJECT(config)}/marketing/brand-kits/${kitId}`, patch, config))
}

function assertStyle(style: unknown): void {
  if (style === null) return
  const errors = validateSlideshowStyle(style, 'slideshowStyle')
  if (errors.length > 0) throw new SlideshowStyleError(errors)
}

export interface CreateBrandKitParams {
  name: string
  /** Defaults to a slug derived from the name. */
  slug?: string
  /** Copy tokens, font, CTA claim, copy rules, approved lines, placements and Knowledge page from this kit
   * unless given here. Logos and other images are not copied. */
  copyFrom?: string
  tokens?: Record<string, string>
  fontFamily?: string
  fontUrl?: string
  ctaClaim?: string
  accentPhraseRequired?: boolean
  copyRules?: Array<Record<string, unknown>>
  approvedLines?: string[]
  enabledPlacements?: string[]
  knowledgePagePath?: string
  slideshowStyle?: Record<string, unknown> | null
}

const COPIED_KIT_FIELDS = [
  'tokens',
  'fontFamily',
  'fontUrl',
  'ctaClaim',
  'accentPhraseRequired',
  'copyRules',
  'approvedLines',
  'enabledPlacements',
  'knowledgePagePath',
] as const

/** Creates a Brand Kit (never the default one). With `copyFrom`, starts from that kit's voice and
 * tokens; any field passed here wins over the copied one. */
export async function createBrandKit(params: CreateBrandKitParams, config: Config): Promise<Record<string, unknown>> {
  const name = typeof params.name === 'string' ? params.name.trim() : ''
  if (!name) throw new Error('name is required.')
  const explicitSlug = params.slug?.trim()
  const slug = explicitSlug || slugify(name, 60, '')
  if (!slug) throw new Error('Could not derive a slug from the name; pass slug (letters, digits, hyphens).')

  const body: Record<string, unknown> = { slug, name }
  let source: Record<string, unknown> | undefined
  if (params.copyFrom) {
    source = await apiGet<Record<string, unknown>>(`${V2_PROJECT(config)}/marketing/brand-kits/${params.copyFrom}`, config)
    for (const f of COPIED_KIT_FIELDS) if (source[f] !== undefined && source[f] !== null) body[f] = source[f]
  }
  for (const f of COPIED_KIT_FIELDS) if (params[f] !== undefined) body[f] = params[f]
  if (params.slideshowStyle !== undefined) {
    assertStyle(params.slideshowStyle)
    body['slideshowStyle'] = params.slideshowStyle
  }

  // A derived slug that is taken gets -2 ... -9 (like the web app); a slug the caller chose is never changed.
  const candidates = explicitSlug ? [slug] : [slug, ...[2, 3, 4, 5, 6, 7, 8, 9].map((n) => `${slug.slice(0, 58).replace(/-+$/g, '')}-${n}`)]
  let created: Record<string, unknown> | undefined
  for (const candidate of candidates) {
    try {
      created = await withBrandKitErrors(() => apiPost<Record<string, unknown>>(`${V2_PROJECT(config)}/marketing/brand-kits`, { ...body, slug: candidate }, config))
      break
    } catch (err) {
      if (!(err instanceof ApiError && err.status === 409)) throw err
    }
  }
  if (!created) {
    throw new Error(
      explicitSlug
        ? `A Brand Kit with the slug "${slug}" already exists; pass a different slug.`
        : `Could not find a free slug for "${name}" (${slug} through ${slug}-9 are taken); pass a slug.`
    )
  }
  const copied = source ? ` Copied the voice, tokens and font from "${String(source['name'] ?? params.copyFrom)}"; its logos and other images were NOT copied.` : ''
  return {
    ...created,
    nextStep:
      'Call get_brand_kit to verify.' +
      copied +
      ' Add the logo, wordmark and badge with upload_brand_image (kitId = this kit); the default kit is unchanged.',
  }
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
   * clipMedia — no brand layout, no photo/headline required. CAROUSEL: finished images used as-is,
   * via slides. MOTION: the same brand layout animated
   * into a short video — set `motion` (and optionally `audio`) too. */
  kind?: string
  /** CAROUSEL only: finished images used as-is, ordered per placement — media ids keyed by
   * `default`, `4x5`, `9x16` or `1x1`. A placement without its own list uses `default`. */
  slides?: Record<string, string[]>
  /** CAROUSEL only: alt text per slide, index-aligned (entry N describes slide N in every placement). */
  slideAltText?: string[]
  layout?: string
  theme?: string
  photoId?: string
  /** CLIP only: media ids per placement, e.g. {"default": mediaId, "9x16": mediaId}. "default"
   * covers any placement without its own entry. */
  clipMedia?: Record<string, string>
  /** MOTION only: the animation timeline (preset, duration, background motion/clip, end card) —
   * validated server-side; see the create_creative/update_creative tool schemas for the shape. */
  motion?: Record<string, unknown>
  /** The audio of a MOTION, CAROUSEL or CLIP (clip's own sound, a library track, a mix, or none, per
   * kind) — validated server-side; see the create_creative/update_creative tool schemas for the shape. */
  audio?: Record<string, unknown>
  /** CAROUSEL only: slide pacing and zoom of the slideshow video made when audio.source is track. */
  slideshow?: Record<string, unknown>
  /** CAROUSEL only: the look the slides were composed with (compose_slideshow's `style`), so it can be reused as a kit style. */
  slideStyle?: Record<string, unknown>
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
  /** Pinned headline type per placement: {"9x16": [fontSize, lineHeight?, letterSpacing?]} — fontSize in
   * artboard px, lineHeight a multiplier, letterSpacing in px. A placement with no entry is auto-fitted. */
  typeOverrides?: Record<string, number[]>
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
  /** Optional: when omitted, the Creative's current version is read first. */
  version?: number
}

export async function updateCreative(params: UpdateCreativeParams, config: Config): Promise<Record<string, unknown>> {
  const { creativeId, ...patch } = params
  const url = `${creativesBase(config)}/${creativeId}`
  try {
    return await withCreativeErrors(async () => {
      let version = patch.version
      if (version === undefined || version === null) {
        const current = await apiGet<{ version?: number }>(url, config)
        version = current.version
      }
      return apiPatch(url, { ...patch, version }, config)
    })
  } catch (err) {
    // A 409 carrying violations is a readiness/structural rule (already formatted above); a bare 409 is a stale version.
    if (err instanceof ApiError && err.status === 409 && !err.violations?.length) {
      throw new Error('This Creative changed since you read it — get_creative and retry')
    }
    throw err
  }
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
  /** For a URL source only: the response's Content-Type header, sans any `; charset=...` suffix.
   * Untrusted (any server can send anything), but a real `image/svg+xml` is a strong signal a local
   * file's extension alone isn't — see `brandImageContentType`. */
  contentType?: string
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
  const contentType = response.headers.get('content-type')?.split(';')[0]?.trim()
  return { bytes, filename: basename(parsed.pathname) || 'media', contentType }
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
  'blockedReason', 'postCount', 'creativeCount',
] as const

function trimMedia(media: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const key of MEDIA_RESULT_FIELDS) {
    if (key in media) out[key] = media[key]
  }
  return out
}

export interface ListCreativeMediaParams {
  kind?: string
  includeBlocked?: boolean
  /** Case-insensitive label prefix, applied server-side. */
  labelPrefix?: string
}

/**
 * The project's media library, trimmed to what's worth an agent's context — call this before
 * uploading a new photo/video/audio file, so an existing, already-checked one gets reused instead
 * of a duplicate upload.
 */
export async function listCreativeMedia(
  params: ListCreativeMediaParams,
  config: Config
): Promise<Array<Record<string, unknown>>> {
  const query = new URLSearchParams()
  if (params.kind) query.set('mediaKind', params.kind)
  if (params.includeBlocked) query.set('includeBlocked', 'true')
  if (params.labelPrefix) query.set('labelPrefix', params.labelPrefix)
  const qs = query.toString()
  const items = await apiGet<Array<Record<string, unknown>>>(
    `${V2_PROJECT(config)}/marketing/photos${qs ? `?${qs}` : ''}`,
    config
  )
  return items.map(trimMedia)
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
  // A local file's sidecar supplies provenance only when the caller passed none of it.
  if (params.filePath && params.source === undefined && params.licence === undefined && params.aiGenerated === undefined) {
    const sidecar = await readProvenanceSidecar(params.filePath)
    if (sidecar) params = { ...params, ...sidecar }
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

// --- Batch upload ------------------------------------------------------------

export const MAX_BATCH_FILES = 100
const BATCH_CONCURRENCY = 3
const SERVER_TEXT_LIMIT = 200
const DEFAULT_FOLDER_EXTENSIONS = ['.jpg', '.jpeg', '.png', '.webp', '.mp4', '.mov', '.m4v', '.webm']

export interface BatchFileSpec {
  filePath?: string
  url?: string
  label?: string
  source?: string
  licence?: string
  aiGenerated?: boolean
}

export interface UploadCreativeMediaBatchParams {
  files?: BatchFileSpec[]
  folder?: string
  /** Folder mode: filename pattern (`*`, `?`, `{a,b}`) — default every image/video file. */
  glob?: string
  /** Folder mode: label per file; `{stem}` is the filename without its extension (default `{stem}`). */
  labelTemplate?: string
  /** Shared provenance, applied to every file unless the file sets its own or has a sidecar. */
  source?: string
  licence?: string
  aiGenerated?: boolean
}

export interface BatchUploadEntry {
  file: string
  label: string
  id?: string
  error?: string
  warnings?: string[]
}

export interface UploadCreativeMediaBatchResult {
  total: number
  uploaded: number
  failed: number
  results: BatchUploadEntry[]
  warnings: string[]
}

/** Turns a simple filename pattern (`*`, `?`, `{a,b}`) into a case-insensitive RegExp. */
export function globToRegExp(glob: string): RegExp {
  let out = ''
  let braces = 0
  for (const ch of glob) {
    if (ch === '*') out += '.*'
    else if (ch === '?') out += '.'
    else if (ch === '{') { out += '(?:'; braces++ }
    else if (ch === '}' && braces > 0) { out += ')'; braces-- }
    else if (ch === ',' && braces > 0) out += '|'
    else out += ch.replace(/[.+^$()|[\]\\]/g, '\\$&')
  }
  return new RegExp(`^${out}${')'.repeat(braces)}$`, 'i')
}

function sourceLabel(spec: BatchFileSpec): string {
  if (spec.filePath) return spec.filePath
  return spec.url ?? ''
}

function defaultLabel(spec: BatchFileSpec): string {
  if (spec.filePath) return stripExtension(basename(spec.filePath))
  try {
    return stripExtension(basename(new URL(spec.url as string).pathname)) || 'media'
  } catch {
    return 'media'
  }
}

/** Lists a folder's matching files, sorted by name (numeric-aware, so 2 sorts before 10). */
async function listFolderFiles(folder: string, glob?: string): Promise<string[]> {
  const entries = await readdir(folder, { withFileTypes: true })
  const pattern = glob ? globToRegExp(glob) : undefined
  return entries
    .filter((e) => e.isFile() && !e.name.startsWith('.'))
    .map((e) => e.name)
    .filter((name) => (pattern ? pattern.test(name) : DEFAULT_FOLDER_EXTENSIONS.includes(extname(name).toLowerCase())))
    .sort((a, b) => a.localeCompare(b, undefined, { numeric: true }))
    .map((name) => join(folder, name))
}

/**
 * Uploads many files into the media library — a list (`files`) or every matching file of a `folder`,
 * in name order, three at a time. One bad file never stops the rest: each gets its own result entry
 * (`id` or `error`). Precedence for provenance matches the single upload: a file's own
 * source/licence/aiGenerated, else its `.provenance.json` sidecar, else the shared values.
 */
export async function uploadCreativeMediaBatch(
  params: UploadCreativeMediaBatchParams,
  config: Config
): Promise<UploadCreativeMediaBatchResult> {
  let specs: BatchFileSpec[]
  if (params.folder) {
    const paths = await listFolderFiles(params.folder, params.glob)
    const template = params.labelTemplate ?? '{stem}'
    specs = paths.map((filePath) => ({ filePath, label: template.split('{stem}').join(stripExtension(basename(filePath))) }))
  } else {
    specs = params.files ?? []
  }
  if (specs.length === 0) {
    throw new Error(params.folder ? `No matching files in ${params.folder}.` : 'files is empty — pass at least one file.')
  }
  if (specs.length > MAX_BATCH_FILES) {
    throw new Error(
      `${specs.length} files is over the ${MAX_BATCH_FILES}-file limit for one call — ${params.folder ? 'narrow it with glob, or ' : ''}upload in several batches.`
    )
  }

  const results: BatchUploadEntry[] = new Array(specs.length)
  const warnings: string[] = []
  const tooLong = (field: string, value: string | undefined, who: string): void => {
    if (value && value.length > SERVER_TEXT_LIMIT) {
      warnings.push(`${who}: ${field} is ${value.length} characters — the server accepts at most ${SERVER_TEXT_LIMIT} and will reject this upload.`)
    }
  }

  const uploadOne = async (spec: BatchFileSpec): Promise<BatchUploadEntry> => {
    const file = sourceLabel(spec)
    const label = spec.label ?? defaultLabel(spec)
    try {
      if (!!spec.filePath === !!spec.url) throw new Error('Each file needs exactly one of filePath or url.')
      let provenance: Pick<BatchFileSpec, 'source' | 'licence' | 'aiGenerated'>
      if (spec.source !== undefined || spec.licence !== undefined || spec.aiGenerated !== undefined) {
        provenance = {
          source: spec.source ?? params.source,
          licence: spec.licence ?? params.licence,
          aiGenerated: spec.aiGenerated ?? params.aiGenerated,
        }
      } else {
        const sidecar = spec.filePath ? await readProvenanceSidecar(spec.filePath) : null
        provenance = sidecar ?? { source: params.source, licence: params.licence, aiGenerated: params.aiGenerated }
      }
      tooLong('label', label, file)
      tooLong('source', provenance.source, file)
      tooLong('licence', provenance.licence, file)
      const { media, warnings: fileWarnings } = await uploadCreativeMedia(
        { filePath: spec.filePath, url: spec.url, label, ...provenance },
        config
      )
      return { file, label, id: media['id'] as string | undefined, ...(fileWarnings.length ? { warnings: fileWarnings } : {}) }
    } catch (err) {
      return { file, label, error: err instanceof Error ? err.message : String(err) }
    }
  }

  let next = 0
  const worker = async (): Promise<void> => {
    while (next < specs.length) {
      const i = next++
      results[i] = await uploadOne(specs[i]!)
    }
  }
  await Promise.all(Array.from({ length: Math.min(BATCH_CONCURRENCY, specs.length) }, worker))

  const failed = results.filter((r) => r.error).length
  return { total: results.length, uploaded: results.length - failed, failed, results, warnings }
}

/** The `upload_creative_media` entry point: the single form, or the batch form (`files` / `folder`). */
export async function uploadCreativeMediaAny(
  params: UploadCreativeMediaParams & UploadCreativeMediaBatchParams,
  config: Config
): Promise<UploadCreativeMediaResult | UploadCreativeMediaBatchResult> {
  const forms = [params.filePath, params.url, params.files, params.folder].filter((v) => v !== undefined && v !== '').length
  if (forms !== 1) {
    throw new Error('Pass exactly one of filePath, url, files or folder.')
  }
  if (params.files || params.folder) return uploadCreativeMediaBatch(params, config)
  return uploadCreativeMedia(params, config)
}

const BRAND_IMAGE_SLOTS = ['mark', 'wordmark_dark', 'wordmark_light', 'badge'] as const

const PNG_MAGIC = [0x89, 0x50, 0x4e, 0x47]
const JPEG_MAGIC = [0xff, 0xd8, 0xff]
const GIF_MAGIC = [0x47, 0x49, 0x46, 0x38] // "GIF8" (87a or 89a)

function startsWithBytes(bytes: Uint8Array, magic: number[]): boolean {
  if (bytes.length < magic.length) return false
  return magic.every((b, i) => bytes[i] === b)
}

/** True when `bytes` start with a magic number for a known raster format — PNG, JPEG, GIF or WebP
 * (RIFF....WEBP). Used to reject a raster file merely named `*.svg` even if, by chance, its bytes
 * decode to text containing `<svg`. */
function startsWithRasterMagic(bytes: Uint8Array): boolean {
  if (startsWithBytes(bytes, PNG_MAGIC)) return true
  if (startsWithBytes(bytes, JPEG_MAGIC)) return true
  if (startsWithBytes(bytes, GIF_MAGIC)) return true
  if (
    bytes.length >= 12 &&
    startsWithBytes(bytes, [0x52, 0x49, 0x46, 0x46]) && // "RIFF"
    bytes[8] === 0x57 && bytes[9] === 0x45 && bytes[10] === 0x42 && bytes[11] === 0x50 // "WEBP"
  ) {
    return true
  }
  return false
}

/** Decodes the first 64 KB of `bytes` as text — UTF-16 (LE or BE, by BOM) when a BOM says so,
 * UTF-8 otherwise (Illustrator and other SVG exporters overwhelmingly write UTF-8 with no BOM). */
function decodeHead(bytes: Uint8Array): string {
  const head = bytes.subarray(0, 65536)
  if (head.length >= 2 && head[0] === 0xff && head[1] === 0xfe) {
    return Buffer.from(head).toString('utf16le')
  }
  if (head.length >= 2 && head[0] === 0xfe && head[1] === 0xff) {
    const swapped = Buffer.alloc(head.length - (head.length % 2))
    for (let i = 0; i + 1 < swapped.length; i += 2) {
      swapped[i] = head[i + 1]
      swapped[i + 1] = head[i]
    }
    return swapped.toString('utf16le')
  }
  return Buffer.from(head).toString('utf8')
}

/**
 * True when `bytes` look like SVG markup. Deliberately lenient: real-world SVGs (especially
 * Illustrator exports) can lead with an XML declaration, an `<?xml-stylesheet?>` processing
 * instruction, a `<!DOCTYPE svg ...>` with a large internal subset, several comments, or just a long
 * prolog before the root element — so rather than anchoring a regex to the start of the file, this
 * just checks whether a `<svg` root tag appears anywhere in the first 64 KB (decoded as UTF-16 when a
 * BOM is present, else UTF-8). SVG has no magic bytes `readImageDimensions` can sniff (it's plain
 * text), so this is the content-based fallback for a local file; a URL's Content-Type header is
 * checked first, in `brandImageContentType`, since it's cheaper and doesn't need decoding. A file that
 * starts with a known raster magic number is never treated as SVG, even if it happens to be named
 * `*.svg` — its bytes are binary, not markup, whatever a stray `<svg` byte sequence might suggest.
 */
function looksLikeSvg(bytes: Uint8Array): boolean {
  if (startsWithRasterMagic(bytes)) return false
  return /<svg[\s>]/i.test(decodeHead(bytes))
}

/**
 * Content type for a Brand Kit image slot upload — PNG/JPEG/WebP via the same magic-byte sniff as a
 * Creative photo, or SVG. SVG detection never trusts the filename alone (a non-SVG file named `.svg`
 * would otherwise be sent to the renderer mislabelled): for a URL source, a fetched `image/svg+xml`
 * Content-Type is authoritative; otherwise the bytes themselves are sniffed for real SVG/XML markup.
 */
function brandImageContentType(bytes: Uint8Array, filename: string, fetchedContentType?: string): string {
  if (fetchedContentType === 'image/svg+xml') return 'image/svg+xml'
  const info = readImageDimensions(bytes)
  if (info) return info.contentType
  if (looksLikeSvg(bytes)) return 'image/svg+xml'
  throw new Error(`"${filename}" is not a recognizable PNG, JPEG, WebP or SVG image.`)
}

export interface UploadBrandImageParams {
  kitId?: string
  slot: string
  filePath?: string
  url?: string
}

/**
 * Uploads a workspace's logo mark, wordmark or badge into one Brand Kit image slot from a local file
 * or a public URL — mint, PUT the bytes, confirm, in one call.
 */
export async function uploadBrandImage(params: UploadBrandImageParams, config: Config): Promise<Record<string, unknown>> {
  if (!(BRAND_IMAGE_SLOTS as readonly string[]).includes(params.slot)) {
    throw new Error(`slot must be one of ${BRAND_IMAGE_SLOTS.join(', ')}`)
  }
  if (!params.filePath && !params.url) {
    throw new Error('Pass filePath (a file on this machine) or url (a public http(s) URL).')
  }

  const kitId = params.kitId ?? ((await resolveDefaultBrandKit(config))['id'] as string)
  const source = await readMediaSource({ filePath: params.filePath, url: params.url })
  const contentType = brandImageContentType(source.bytes, source.filename, source.contentType)

  const mint = await apiPost<{ uploadUrl: string; gcsPath: string }>(
    `${V2_PROJECT(config)}/marketing/brand-kits/${kitId}/images/${params.slot}`,
    { contentType, sizeBytes: source.bytes.byteLength },
    config
  )
  await putBytes(mint.uploadUrl, contentType, source.bytes)
  return apiPost(
    `${V2_PROJECT(config)}/marketing/brand-kits/${kitId}/images/${params.slot}/confirm`,
    { gcsPath: mint.gcsPath },
    config
  )
}

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
    if (latest) {
      renderId = latest.id
    } else {
      // CLIP and CAROUSEL render in one synchronous call (on the server, or here when their audio needs
      // encoding), so there is no reason to make the caller do it as a separate step.
      const creative = await apiGet<{ kind?: string; audio?: { source?: string } | null }>(`${creativesBase(config)}/${params.creativeId}`, config)
      if (creative?.kind !== 'CLIP' && creative?.kind !== 'CAROUSEL') {
        throw new Error(
          'No SUCCEEDED render exists for this Creative yet — call render_creative (previewOnly: false) first, or pass an explicit renderId.'
        )
      }
      const renderParams = { creativeId: params.creativeId, previewOnly: false, renderer: 'mcp' }
      const rendered = needsLocalRender(creative.kind, creative.audio)
        ? await runLocalRender(renderParams, config)
        : await renderViaApi(renderParams, config)
      if (!rendered.ok || !rendered.renderId) {
        throw new Error(`Rendering this ${creative.kind} Creative before attaching failed: ${rendered.error ?? 'unknown error'}`)
      }
      renderId = rendered.renderId
    }
  }
  return apiPost(`${creativesBase(config)}/${params.creativeId}/attach`, { renderId, workItemId: postId }, config)
}
