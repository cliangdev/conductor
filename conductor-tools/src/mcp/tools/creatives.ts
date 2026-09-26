import { readFile } from 'node:fs/promises'
import { basename } from 'node:path'
import { Config } from '../config.js'
import { apiGet, apiPost, apiPatch, putBytes, ApiError } from '../api.js'
import { readImageDimensions } from '../../lib/image-dimensions.js'
import { renderCreative as runLocalRender } from '../../lib/creative-render.js'

/**
 * Conductor Creatives: a Brand Kit (tokens, copy rules, approved lines) governs one or more
 * Creatives (photo + headline + layout, optionally a story/carousel sequence and lettered
 * variants), which render locally (Playwright, driven by this MCP server) to upload-ready PNGs
 * that attach to a Post like any other media. See docs/mcp-tool-guidelines.md's action-verify and
 * dispatch-status patterns, both used below.
 */

const V2_PROJECT = (config: Config): string => `/api/v2/projects/${config.projectId}`
const creativesBase = (config: Config): string => `${V2_PROJECT(config)}/marketing/creatives`

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
  const [creative, readiness, all] = await Promise.all([
    apiGet<Record<string, unknown>>(`${creativesBase(config)}/${params.creativeId}`, config),
    apiGet<Record<string, unknown>>(`${creativesBase(config)}/${params.creativeId}/readiness`, config),
    apiGet<Array<Record<string, unknown>>>(creativesBase(config), config),
  ])
  const variants = all
    .filter((c) => c['number'] === creative['number'] && c['id'] !== creative['id'])
    .map((c) => ({ id: c['id'], displayId: c['displayId'], state: c['state'], headline: c['headline'] }))
  return { ...creative, readiness, variants }
}

export interface CreativeFields {
  brandKitId?: string
  name?: string
  state?: string
  layout?: string
  theme?: string
  photoId?: string
  focalOverride?: Record<string, string>
  headline?: string
  body?: string
  caption?: string
  altText?: string
  placements?: string[]
  sequenceKind?: string
  sequence?: Array<Record<string, unknown>>
  carouselRatio?: string
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

// --- Photos -----------------------------------------------------------------

async function readPhotoSource(params: {
  filePath?: string
  url?: string
}): Promise<{ bytes: Uint8Array; filename: string }> {
  if (params.filePath) {
    return { bytes: new Uint8Array(await readFile(params.filePath)), filename: basename(params.filePath) }
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
  return { bytes, filename: basename(parsed.pathname) || 'photo' }
}

export async function uploadCreativePhoto(
  params: {
    filePath?: string
    url?: string
    label?: string
    source?: string
    licence?: string
    aiGenerated?: boolean
  },
  config: Config
): Promise<Record<string, unknown>> {
  if (!params.filePath && !params.url) {
    throw new Error('Pass filePath (a file on this machine) or url (a public http(s) URL).')
  }
  const { bytes, filename } = await readPhotoSource(params)
  const info = readImageDimensions(bytes)
  if (!info) {
    throw new Error(
      `Could not read "${filename}" as a PNG, JPEG or WebP — Creative photos accept only those three formats.`
    )
  }

  const mint = await apiPost<{ id: string; uploadUrl: string | null }>(
    `${V2_PROJECT(config)}/marketing/photos`,
    {
      label: params.label ?? filename,
      contentType: info.contentType,
      sizeBytes: bytes.byteLength,
      width: info.width,
      height: info.height,
      source: params.source ?? null,
      licence: params.licence ?? null,
      aiGenerated: !!params.aiGenerated,
    },
    config
  )
  if (!mint.uploadUrl) {
    throw new Error('The mint response carried no uploadUrl — cannot upload bytes.')
  }
  await putBytes(mint.uploadUrl, info.contentType, bytes)
  return apiPost(`${V2_PROJECT(config)}/marketing/photos/${mint.id}/confirm`, { sizeBytes: bytes.byteLength }, config)
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
}

interface RenderApiItem {
  id: string
  state: string
  previewOnly?: boolean
  error?: string | null
  frames?: RenderApiFrame[]
}

export async function renderCreativeTool(
  params: { creativeId: string; previewOnly?: boolean; renderer?: string; workflowRunId?: string },
  config: Config
): Promise<Record<string, unknown>> {
  const result = await runLocalRender(
    { creativeId: params.creativeId, previewOnly: !!params.previewOnly, renderer: params.renderer ?? 'mcp', workflowRunId: params.workflowRunId },
    config
  )
  return {
    renderId: result.renderId,
    state: result.state,
    ok: result.ok,
    error: result.error,
    frames: result.frames,
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

function findSheetFrame(renders: RenderApiItem[]): { render: RenderApiItem; frame: RenderApiFrame } | undefined {
  for (const render of renders) {
    const frame = (render.frames ?? []).find((f) => f.placementKey === 'sheet')
    if (frame) return { render, frame }
  }
  return undefined
}

export async function previewCreative(params: { creativeId: string }, config: Config): Promise<PreviewCreativeResult> {
  const renders = await apiGet<RenderApiItem[]>(`${creativesBase(config)}/${params.creativeId}/renders`, config)
  let found = findSheetFrame(renders)

  if (!found) {
    const rendered = await runLocalRender({ creativeId: params.creativeId, previewOnly: true, renderer: 'mcp' }, config)
    if (!rendered.ok || !rendered.renderId) {
      return {
        renderId: rendered.renderId,
        state: rendered.state,
        note: `Rendering a preview failed${rendered.error ? `: ${rendered.error}` : '.'} There is nothing to show.`,
      }
    }
    const sheetFrame = rendered.frames.find((f) => f.placementKey === 'sheet')
    if (!sheetFrame) {
      return { renderId: rendered.renderId, state: rendered.state, note: 'The preview render produced no contact sheet frame.' }
    }
    found = { render: { id: rendered.renderId, state: rendered.state ?? 'SUCCEEDED' }, frame: sheetFrame }
  }

  const { render, frame } = found
  if (!frame.url) {
    return { renderId: render.id, state: render.state, note: 'The contact sheet frame has no download URL yet.' }
  }
  if ((frame.sizeBytes ?? 0) > MAX_INLINE_IMAGE_BYTES) {
    return {
      renderId: render.id,
      state: render.state,
      url: frame.url,
      note: `The rendered image is ${Math.round((frame.sizeBytes ?? 0) / 1024)} KB, larger than this tool can inline (~1 MB) — open the URL directly instead.`,
    }
  }

  const response = await fetch(frame.url)
  if (!response.ok) {
    return { renderId: render.id, state: render.state, url: frame.url, note: `Could not download the frame (HTTP ${response.status}) — open the URL directly instead.` }
  }
  const bytes = new Uint8Array(await response.arrayBuffer())
  if (bytes.byteLength > MAX_INLINE_IMAGE_BYTES) {
    return { renderId: render.id, state: render.state, url: frame.url, note: 'The rendered image is larger than this tool can inline — open the URL directly instead.' }
  }
  // The `sheet` contact sheet is PNG today, but the mime type comes off the response rather than being
  // hardcoded so this never silently mislabels a frame if that ever changes (see docs/creatives.md).
  const mimeType = (response.headers.get('content-type') || 'image/png').split(';')[0].trim()
  return { renderId: render.id, state: render.state, url: frame.url, image: { data: Buffer.from(bytes), mimeType } }
}

export async function attachCreativeToPost(
  params: { creativeId: string; workItemId: string; renderId?: string },
  config: Config
): Promise<Record<string, unknown>> {
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
  return apiPost(`${creativesBase(config)}/${params.creativeId}/attach`, { renderId, workItemId: params.workItemId }, config)
}
