/**
 * Picks the video route and runs the gate, in this order: route configured, inputs valid, per-call cap,
 * daily cap, cost confirmation. Only then does it call Veo (Vertex) or Gemini Omni. Spend is recorded after
 * a successful call, and after any failure Google still bills (safety filter, timeout, a job lost after it
 * was accepted, a failed download): see isBilled.
 */
import { existsSync, statSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { extname, isAbsolute, resolve } from 'node:path'
import { readConfig } from './config.js'
import { generateOmniClip, resolveGeminiKey, type OmniDeps } from './gemini-omni.js'
import { generateWithVertex, type VertexDeps } from './vertex-veo.js'
import { record, spentToday, type SpendOptions } from './video-spend.js'
import {
  DEFAULT_DAILY_CAP_USD,
  DEFAULT_PER_CALL_CAP_USD,
  DEFAULT_VIDEO_REGION,
  MAX_FRAME_BYTES,
  OMNI_MODEL,
  VERTEX_MODELS,
  VideoCostCapExceeded,
  VideoCostConfirmationRequired,
  VideoDailyCapExceeded,
  VideoRouteNotConfigured,
  estimateCost,
  isBilled,
  normalizeVideoParams,
  type VideoFrame,
  type VideoParams,
  type VideoRequest,
  type VideoRoute,
} from './video-common.js'

export interface RouteChoice {
  route: VideoRoute
  /** Region for Vertex. */
  region: string
  project?: string
  apiKey?: string
}

/** Config `videoRoute`; unset: vertex if a project is set, else gemini if a key resolves. Null when neither works. */
export function pickVideoRoute(): RouteChoice | null {
  const cfg = readConfig()
  const project = cfg?.vertexProject?.trim()
  const region = cfg?.vertexRegion?.trim() || DEFAULT_VIDEO_REGION
  const key = resolveGeminiKey()
  const vertex = project ? { route: 'vertex' as const, region, project } : null
  const gemini = key ? { route: 'gemini' as const, region, apiKey: key.key } : null
  if (cfg?.videoRoute === 'vertex') return vertex
  if (cfg?.videoRoute === 'gemini') return gemini
  return vertex ?? gemini
}

export function videoModel(route: VideoRoute, tier: VideoRequest['tier']): string {
  return process.env['CONDUCTOR_VIDEO_MODEL']?.trim() || (route === 'vertex' ? VERTEX_MODELS[tier] : OMNI_MODEL)
}

export function videoCaps(): { perCallUsd: number; dailyUsd: number } {
  const cfg = readConfig()
  const ok = (n: unknown, d: number): number => (typeof n === 'number' && Number.isFinite(n) && n > 0 ? n : d)
  return { perCallUsd: ok(cfg?.videoPerCallCapUsd, DEFAULT_PER_CALL_CAP_USD), dailyUsd: ok(cfg?.videoDailyCapUsd, DEFAULT_DAILY_CAP_USD) }
}

const FRAME_TYPES: Record<string, VideoFrame['mimeType']> = { '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.png': 'image/png' }

function checkFrame(label: string, p: string, cwd: string): { abs: string; mimeType: VideoFrame['mimeType'] } {
  const abs = isAbsolute(p) ? p : resolve(cwd, p)
  if (!existsSync(abs) || !statSync(abs).isFile()) throw new Error(`${label} not found: ${p}`)
  const mimeType = FRAME_TYPES[extname(abs).toLowerCase()]
  if (!mimeType) throw new Error(`${label} must be a JPEG or PNG: ${p}`)
  if (statSync(abs).size > MAX_FRAME_BYTES) throw new Error(`${label} is over 20 MB: ${p}`)
  return { abs, mimeType }
}

export interface GenerateVideosOptions {
  onProgress?: (message: string) => void
  cwd?: string
  spend?: SpendOptions
  vertex?: VertexDeps
  omni?: OmniDeps
}

export interface GenerateVideosResult {
  videos: Buffer[]
  request: VideoRequest
  route: VideoRoute
  model: string
  /** The estimate for everything asked for. */
  estimateUsd: number
  /** The estimate for one clip. */
  perClipUsd: number
  /** What was counted as spent for this call (less than estimateUsd when an Omni run stopped early). */
  billedUsd: number
  spentTodayUsd: number
  dailyCapUsd: number
  warnings: string[]
  sourceImagePath?: string
  lastFramePath?: string
}

export async function generateVideos(params: VideoParams, options: GenerateVideosOptions = {}): Promise<GenerateVideosResult> {
  const cwd = options.cwd ?? process.cwd()
  // 1. A route must be set up.
  const choice = pickVideoRoute()
  if (!choice) {
    const chosen = readConfig()?.videoRoute
    throw new VideoRouteNotConfigured(chosen === 'vertex' || chosen === 'gemini' ? chosen : undefined)
  }
  // 2. Inputs against what the route can do.
  const { request, warnings } = normalizeVideoParams(params, choice.route, choice.region)
  const image = request.imagePath ? checkFrame('imagePath', request.imagePath, cwd) : undefined
  const last = request.lastFramePath ? checkFrame('lastFramePath', request.lastFramePath, cwd) : undefined
  const model = videoModel(choice.route, request.tier)
  // 3. Caps, then confirmation.
  const caps = videoCaps()
  const est = estimateCost(request, choice.route, readConfig()?.videoRates)
  if (est.estimateUsd > caps.perCallUsd) throw new VideoCostCapExceeded(est.estimateUsd, caps.perCallUsd)
  const before = spentToday(options.spend)
  if (before + est.estimateUsd > caps.dailyUsd) throw new VideoDailyCapExceeded(est.estimateUsd, before, caps.dailyUsd)
  if (!request.confirmCost) {
    throw new VideoCostConfirmationRequired({ estimateUsd: est.estimateUsd, breakdown: est.breakdown, spentTodayUsd: before, dailyCapUsd: caps.dailyUsd, route: choice.route, model })
  }

  const load = async (f?: { abs: string; mimeType: VideoFrame['mimeType'] }): Promise<VideoFrame | undefined> =>
    f ? { base64: (await readFile(f.abs)).toString('base64'), mimeType: f.mimeType } : undefined
  const frames = { image: await load(image), lastFrame: await load(last) }
  const clean = <T extends object>(o: T) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined)) as T

  const videos: Buffer[] = []
  let billedUsd = 0
  const bill = (usd: number) => {
    record(usd, options.spend)
    billedUsd += usd
  }
  try {
    if (choice.route === 'vertex') {
      const out = await generateWithVertex(request, clean(frames), { project: choice.project!, region: choice.region, model, onProgress: options.onProgress }, options.vertex)
      videos.push(...out.videos)
      warnings.push(...out.warnings)
      bill(est.estimateUsd)
    } else {
      for (let i = 0; i < request.count; i++) {
        if (request.count > 1) options.onProgress?.(`Clip ${i + 1} of ${request.count}`)
        try {
          const out = await generateOmniClip(request, clean(frames), { apiKey: choice.apiKey!, model, onProgress: options.onProgress }, options.omni)
          videos.push(out.video)
          warnings.push(...out.warnings)
          bill(est.perClipUsd)
        } catch (err) {
          // Keep the clips already made (and billed) rather than throwing them away.
          if (videos.length === 0) throw err
          if (isBilled(err)) bill(est.perClipUsd)
          warnings.push(`Clip ${i + 1} of ${request.count} failed (${(err as Error).message}); returning the ${videos.length} that finished.`)
          break
        }
      }
    }
  } catch (err) {
    // Google bills a safety-filtered request, and a timed-out or lost job may still finish and be billed.
    if (isBilled(err)) bill(choice.route === 'vertex' ? est.estimateUsd : est.perClipUsd)
    throw err
  }

  return {
    videos,
    request,
    route: choice.route,
    model,
    estimateUsd: est.estimateUsd,
    perClipUsd: est.perClipUsd,
    billedUsd: Math.round(billedUsd * 10000) / 10000,
    spentTodayUsd: spentToday(options.spend),
    dailyCapUsd: caps.dailyUsd,
    warnings,
    ...(image ? { sourceImagePath: image.abs } : {}),
    ...(last ? { lastFramePath: last.abs } : {}),
  }
}
