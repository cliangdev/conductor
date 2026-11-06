/**
 * Shared pieces of `generate_creative_video`: types, the per-route capability table, the rate table and
 * cost estimate, input validation and the video placement fit. No network or disk here.
 */
import { placementFit, type PlacementFit } from './image-common.js'

export type VideoRoute = 'vertex' | 'gemini'
export type VideoTier = 'lite' | 'fast' | 'standard'
export type VideoAspect = '9:16' | '16:9'
export type VideoResolution = '720p' | '1080p'

export const VIDEO_TIERS: readonly VideoTier[] = ['lite', 'fast', 'standard']
export const VIDEO_ASPECTS: readonly VideoAspect[] = ['9:16', '16:9']
export const VIDEO_RESOLUTIONS: readonly VideoResolution[] = ['720p', '1080p']

export const DEFAULT_VIDEO_TIER: VideoTier = 'fast'
export const DEFAULT_VIDEO_DURATION = 4
export const DEFAULT_VIDEO_REGION = 'us-central1'
export const DEFAULT_PER_CALL_CAP_USD = 2
export const DEFAULT_DAILY_CAP_USD = 5
export const MAX_FRAME_BYTES = 20 * 1024 * 1024

export const VERTEX_MODELS: Record<VideoTier, string> = {
  lite: 'veo-3.1-lite-generate-001',
  fast: 'veo-3.1-fast-generate-001',
  standard: 'veo-3.1-generate-001',
}
export const OMNI_MODEL = 'gemini-omni-1.1-flash'

/** What each route accepts. Veo takes only these durations; Omni takes any whole second in its range. */
export const CAPABILITIES = {
  vertex: { durations: [4, 6, 8] as readonly number[], resolutions: VIDEO_RESOLUTIONS, aspects: VIDEO_ASPECTS, maxCount: 2 },
  gemini: { minDuration: 3, maxDuration: 10, resolutions: VIDEO_RESOLUTIONS, aspects: VIDEO_ASPECTS, maxCount: 2 },
} as const

/** A frame image already read from disk. */
export interface VideoFrame {
  base64: string
  mimeType: 'image/jpeg' | 'image/png'
}

/** Params after defaults; paths are validated and read by the orchestrator. */
export interface VideoRequest {
  prompt: string
  imagePath?: string
  lastFramePath?: string
  aspect: VideoAspect
  durationSeconds: number
  resolution: VideoResolution
  tier: VideoTier
  generateAudio: boolean
  count: number
  negativePrompt?: string
  seed?: number
  confirmCost: boolean
}

/** Raw MCP params. */
export interface VideoParams {
  prompt: string
  imagePath?: string
  lastFramePath?: string
  aspect?: string
  durationSeconds?: number
  resolution?: string
  tier?: string
  generateAudio?: boolean
  count?: number
  negativePrompt?: string
  seed?: number
  confirmCost?: boolean
}

/** Conservative (high-side) estimates in USD per second; overridable via config `videoRates`. Re-verify against the console. */
export const DEFAULT_VIDEO_RATES: Record<string, number> = {
  lite: 0.05,
  'lite+audio': 0.08,
  fast: 0.15,
  'fast+audio': 0.2,
  standard: 0.4,
  'standard+audio': 0.6,
  'omni-720p': 0.1,
  'omni-1080p': 0.15,
}

export function rateKey(route: VideoRoute, tier: VideoTier, resolution: VideoResolution, audio: boolean): string {
  if (route === 'gemini') return `omni-${resolution}`
  return audio ? `${tier}+audio` : tier
}

export function rateFor(
  route: VideoRoute,
  tier: VideoTier,
  resolution: VideoResolution,
  audio: boolean,
  overrides?: Record<string, number>
): { key: string; usdPerSecond: number } {
  const key = rateKey(route, tier, resolution, audio)
  const o = overrides?.[key]
  const usdPerSecond = typeof o === 'number' && Number.isFinite(o) && o >= 0 ? o : DEFAULT_VIDEO_RATES[key]!
  return { key, usdPerSecond }
}

const money = (n: number): string => `$${n.toFixed(2)}`
const round4 = (n: number): number => Math.round(n * 10000) / 10000

export interface CostEstimate {
  estimateUsd: number
  perClipUsd: number
  usdPerSecond: number
  breakdown: string
}

/** seconds x rate x count. Omni always makes audio, so its rate ignores `generateAudio`. */
export function estimateCost(req: Pick<VideoRequest, 'durationSeconds' | 'resolution' | 'tier' | 'generateAudio' | 'count'>, route: VideoRoute, overrides?: Record<string, number>): CostEstimate {
  const { key, usdPerSecond } = rateFor(route, req.tier, req.resolution, req.generateAudio, overrides)
  const perClipUsd = round4(req.durationSeconds * usdPerSecond)
  const estimateUsd = round4(perClipUsd * req.count)
  return {
    estimateUsd,
    perClipUsd,
    usdPerSecond,
    breakdown: `${req.count} clip${req.count === 1 ? '' : 's'} x ${req.durationSeconds} s x ${money(usdPerSecond)}/s (${key}) = ${money(estimateUsd)}, an estimate`,
  }
}

const oneOf = <T extends string>(list: readonly T[], v: unknown): v is T => typeof v === 'string' && (list as readonly string[]).includes(v)

/**
 * Applies defaults and checks everything that does not touch the disk. Throws a plain Error on the first
 * problem; returns warnings (never blocking) alongside the request.
 */
export function normalizeVideoParams(params: VideoParams, route: VideoRoute, region?: string): { request: VideoRequest; warnings: string[] } {
  const warnings: string[] = []
  const prompt = typeof params.prompt === 'string' ? params.prompt.trim() : ''
  if (!prompt) throw new Error('prompt is required — describe the motion, the subject and any sound.')

  const aspect = params.aspect ?? '9:16'
  if (!oneOf(VIDEO_ASPECTS, aspect)) throw new Error(`aspect must be one of ${VIDEO_ASPECTS.join(', ')}.`)
  const resolution = params.resolution ?? '1080p'
  if (!oneOf(VIDEO_RESOLUTIONS, resolution)) throw new Error(`resolution must be one of ${VIDEO_RESOLUTIONS.join(', ')}.`)
  const tier = params.tier ?? DEFAULT_VIDEO_TIER
  if (!oneOf(VIDEO_TIERS, tier)) throw new Error(`tier must be one of ${VIDEO_TIERS.join(', ')}.`)

  const durationSeconds = params.durationSeconds ?? DEFAULT_VIDEO_DURATION
  if (route === 'vertex') {
    if (!CAPABILITIES.vertex.durations.includes(durationSeconds)) throw new Error('durationSeconds must be 4, 6 or 8 for Veo.')
  } else if (!Number.isInteger(durationSeconds) || durationSeconds < CAPABILITIES.gemini.minDuration || durationSeconds > CAPABILITIES.gemini.maxDuration) {
    throw new Error('durationSeconds must be a whole number from 3 to 10 for Gemini Omni.')
  }

  const count = params.count ?? 1
  if (!Number.isInteger(count) || count < 1 || count > 2) throw new Error('count must be 1 or 2.')
  if (params.generateAudio !== undefined && typeof params.generateAudio !== 'boolean') throw new Error('generateAudio must be true or false.')
  if (params.confirmCost !== undefined && typeof params.confirmCost !== 'boolean') throw new Error('confirmCost must be true or false.')
  if (params.negativePrompt !== undefined && typeof params.negativePrompt !== 'string') throw new Error('negativePrompt must be text.')
  if (params.seed !== undefined && (!Number.isInteger(params.seed) || params.seed < 0)) throw new Error('seed must be a whole number, 0 or more.')
  if (params.lastFramePath && !params.imagePath) throw new Error('lastFramePath needs imagePath (the first frame).')

  if (route === 'gemini') {
    if (params.tier !== undefined) warnings.push(`tier "${params.tier}" is ignored on Gemini Omni (one model, ${OMNI_MODEL}).`)
    if (params.seed !== undefined) warnings.push('seed is ignored on Gemini Omni.')
    if (params.generateAudio !== true) warnings.push('Omni always makes audio; it is stripped from the saved file when ffmpeg is available.')
  } else if (region && region !== DEFAULT_VIDEO_REGION) {
    warnings.push(`Veo on Vertex is documented for ${DEFAULT_VIDEO_REGION} only; region "${region}" may be refused. Run \`conductor config set-vertex-region ${DEFAULT_VIDEO_REGION}\` if it fails.`)
  }

  return {
    request: {
      prompt,
      ...(params.imagePath ? { imagePath: params.imagePath } : {}),
      ...(params.lastFramePath ? { lastFramePath: params.lastFramePath } : {}),
      aspect,
      durationSeconds,
      resolution,
      tier,
      generateAudio: params.generateAudio ?? false,
      count,
      ...(params.negativePrompt?.trim() ? { negativePrompt: params.negativePrompt.trim() } : {}),
      ...(params.seed !== undefined ? { seed: params.seed } : {}),
      confirmCost: params.confirmCost === true,
    },
    warnings,
  }
}

/** Full-bleed fit per placement, plus a warning when 720p would be upscaled at 1080-wide placements. */
export function videoPlacementFit(width: number, height: number): { placementFit: Record<string, PlacementFit>; warnings: string[] } {
  const fit = placementFit(width, height)
  const warnings: string[] = []
  const over = Object.entries(fit).filter(([, f]) => !f.ok)
  if (over.length) {
    warnings.push(
      `This ${width}x${height} clip is upscaled at ${over.map(([p, f]) => `${p} (${f.scale}x)`).join(', ')} when shown full-bleed on a 1080-wide placement; fine for a draft, use 1080p for a final.`
    )
  }
  return { placementFit: fit, warnings }
}

/** Typed failure with a stable `code` and extra fields the MCP layer returns as a structured error. */
export class VideoError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly details: Record<string, unknown> = {}
  ) {
    super(message)
    this.name = 'VideoError'
  }
}

export const SETUP_VERTEX = '`conductor config set-vertex-project <id>` and `gcloud auth login`'
export const SETUP_GEMINI = '`conductor config set-gemini-api-key <key>`'

export class VideoRouteNotConfigured extends VideoError {
  /** `chosen` is config `videoRoute` when it names a route that is missing its setup. */
  constructor(chosen?: VideoRoute) {
    const message =
      chosen === 'vertex'
        ? `videoRoute is set to vertex, but no Vertex project is set. Run ${SETUP_VERTEX}, or switch with \`conductor config set-video-route gemini\`. Nothing was generated or billed.`
        : chosen === 'gemini'
          ? `videoRoute is set to gemini, but no Gemini API key was found (CONDUCTOR_GEMINI_API_KEY, GEMINI_API_KEY or config). Run ${SETUP_GEMINI}, or switch with \`conductor config set-video-route vertex\`. Nothing was generated or billed.`
          : `No video route is set up. Either run ${SETUP_VERTEX} (Veo on Vertex AI), or ${SETUP_GEMINI} (Gemini Omni). Nothing was generated or billed.`
    super('VIDEO_ROUTE_NOT_CONFIGURED', message, chosen ? { videoRoute: chosen } : {})
  }
}

/**
 * Marks a failure that happened after Google accepted the job (a lost poll, a failed download), so the
 * caller counts it as spent. Safety-filtered and timed-out results are billable without the mark.
 */
export function markBilled<E extends VideoError>(err: E): E {
  err.details['billed'] = true
  return err
}

export function isBilled(err: unknown): boolean {
  if (!(err instanceof VideoError)) return false
  return err instanceof VideoSafetyFiltered || err.code === 'VIDEO_TIMEOUT' || err.details['billed'] === true
}

export class VideoCostCapExceeded extends VideoError {
  constructor(estimateUsd: number, capUsd: number) {
    super('VIDEO_COST_CAP_EXCEEDED', `Estimated ${money(estimateUsd)} is over the per-call cap of ${money(capUsd)}. Use fewer or shorter clips, a cheaper tier, or raise the cap with \`conductor config set-video-caps --per-call <usd>\`.`, { estimateUsd, perCallCapUsd: capUsd })
  }
}

export class VideoDailyCapExceeded extends VideoError {
  constructor(estimateUsd: number, spentTodayUsd: number, capUsd: number) {
    super('VIDEO_DAILY_CAP_EXCEEDED', `Estimated ${money(estimateUsd)} plus ${money(spentTodayUsd)} already spent today passes the daily cap of ${money(capUsd)}. Wait until tomorrow or raise it with \`conductor config set-video-caps --daily <usd>\`.`, { estimateUsd, spentTodayUsd, dailyCapUsd: capUsd })
  }
}

export class VideoCostConfirmationRequired extends VideoError {
  constructor(info: { estimateUsd: number; breakdown: string; spentTodayUsd: number; dailyCapUsd: number; route: VideoRoute; model: string }) {
    super('VIDEO_COST_CONFIRMATION_REQUIRED', `This will cost about ${money(info.estimateUsd)} (${info.breakdown}). Tell the person, and call again with confirmCost: true only after they agree.`, info)
  }
}

export class VideoSafetyFiltered extends VideoError {
  constructor(reasons?: string) {
    super('VIDEO_SAFETY_FILTERED', `The model's safety filter blocked the video${reasons ? ` (${reasons})` : ''}. Google bills a filtered request, so the estimate was counted as spent. Rewrite the prompt (and check the first frame) and try again.`, reasons ? { reasons } : {})
  }
}

/** Maps an HTTP failure from either route to a plain message. */
export function httpFailure(route: VideoRoute, status: number, apiMessage: string, model: string): VideoError {
  const setup = route === 'vertex' ? SETUP_VERTEX : SETUP_GEMINI
  const name = route === 'vertex' ? 'Vertex AI' : 'the Gemini API'
  if (status === 404) {
    return new VideoError('VIDEO_MODEL_NOT_FOUND', `${name} says model ${model} was not found. Valid ids: ${route === 'vertex' ? Object.values(VERTEX_MODELS).join(', ') : OMNI_MODEL}. CONDUCTOR_VIDEO_MODEL overrides the id.`)
  }
  if (status === 429) return new VideoError('VIDEO_QUOTA', `${name} is out of quota or rate limited (429). Wait a few minutes or raise the quota. It says: ${apiMessage}`)
  if (status === 401 || status === 403) return new VideoError('VIDEO_AUTH', `${name} rejected the credentials (${status}). Check ${setup}. It says: ${apiMessage}`)
  return new VideoError('VIDEO_FAILED', `${name} video generation failed (${status}): ${apiMessage}`)
}

/** Extracts `error.message` from a JSON error body, falling back to the status text. Never includes request headers. */
export async function readErrorMessage(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: { message?: string } }
    return body.error?.message ?? res.statusText ?? 'unknown error'
  } catch {
    return res.statusText || 'unknown error'
  }
}
