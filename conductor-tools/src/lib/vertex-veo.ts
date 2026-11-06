/**
 * Veo 3.1 on Vertex AI. Starts a long-running prediction, polls `fetchPredictOperation` until it is done,
 * and returns the MP4 bytes. The bearer token comes from CONDUCTOR_VERTEX_ACCESS_TOKEN or
 * `gcloud auth print-access-token` (falling back to application-default credentials); it is never logged, returned or put in an error message.
 */
import { execFile } from 'node:child_process'
import { VideoError, VideoSafetyFiltered, httpFailure, markBilled, readErrorMessage, SETUP_VERTEX, type VideoFrame, type VideoRequest } from './video-common.js'

export const POLL_INTERVAL_MS = 10_000
export const GENERATION_TIMEOUT_MS = 10 * 60_000
/**
 * gcloud may hand back a token it cached itself with only minutes left, so this cache is kept short. The
 * real expiry is unknown here; a 401/403 also clears it.
 */
const TOKEN_TTL_MS = 5 * 60_000
/** Consecutive failed status checks (network errors, 429, 5xx) tolerated before giving up on a running job. */
export const MAX_POLL_FAILURES = 3
export const DOWNLOAD_TIMEOUT_MS = 2 * 60_000

export interface VertexConfig {
  project: string
  region: string
  model: string
  onProgress?: (message: string) => void
}

export interface VertexDeps {
  fetch?: typeof fetch
  sleep?: (ms: number) => Promise<void>
  now?: () => number
  getToken?: () => Promise<string>
  pollIntervalMs?: number
  timeoutMs?: number
  downloadTimeoutMs?: number
}

let cached: { token: string; expires: number } | null = null

/** Test hook. */
export function resetVertexTokenCache(): void {
  cached = null
}

function runGcloud(args: string[]): Promise<string> {
  return new Promise((resolve, reject) => {
    execFile('gcloud', args, { timeout: 20_000 }, (err, stdout) => {
      if (err) reject(err)
      else resolve(String(stdout).trim())
    })
  })
}

export async function getVertexToken(now: () => number = Date.now): Promise<string> {
  const env = process.env['CONDUCTOR_VERTEX_ACCESS_TOKEN']?.trim()
  if (env) return env
  if (cached && cached.expires > now()) return cached.token
  let token = ''
  try {
    // The user login first; application-default credentials (ADC) as the fallback.
    token = await runGcloud(['auth', 'print-access-token']).catch(() => runGcloud(['auth', 'application-default', 'print-access-token']))
  } catch {
    throw new VideoError('VIDEO_AUTH', `Could not get a Google Cloud access token. Install the gcloud CLI and run ${SETUP_VERTEX}, or set CONDUCTOR_VERTEX_ACCESS_TOKEN.`)
  }
  if (!token) throw new VideoError('VIDEO_AUTH', `gcloud returned no access token. Run ${SETUP_VERTEX}.`)
  cached = { token, expires: now() + TOKEN_TTL_MS }
  return token
}

export function vertexBaseUrl(project: string, region: string, model: string): string {
  return `https://${region}-aiplatform.googleapis.com/v1/projects/${project}/locations/${region}/publishers/google/models/${model}`
}

const frame = (f: VideoFrame) => ({ bytesBase64Encoded: f.base64, mimeType: f.mimeType })

export function buildVertexBody(req: VideoRequest, frames: { image?: VideoFrame; lastFrame?: VideoFrame }): Record<string, unknown> {
  return {
    instances: [
      {
        prompt: req.prompt,
        ...(frames.image ? { image: frame(frames.image) } : {}),
        ...(frames.lastFrame ? { lastFrame: frame(frames.lastFrame) } : {}),
      },
    ],
    parameters: {
      aspectRatio: req.aspect,
      durationSeconds: req.durationSeconds,
      sampleCount: req.count,
      // Vertex defaults generateAudio to true, so it is always sent.
      generateAudio: req.generateAudio,
      resolution: req.resolution,
      personGeneration: 'allow_adult',
      ...(req.negativePrompt ? { negativePrompt: req.negativePrompt } : {}),
      ...(req.seed !== undefined ? { seed: req.seed } : {}),
    },
  }
}

interface VertexVideo {
  bytesBase64Encoded?: string
  gcsUri?: string
}
interface Operation {
  name?: string
  done?: boolean
  error?: { code?: number; message?: string; status?: string }
  response?: { videos?: VertexVideo[]; raiMediaFilteredCount?: number; raiMediaFilteredReasons?: string[] }
}

function operationFailure(err: NonNullable<Operation['error']>, model: string): VideoError {
  const message = err.message ?? 'unknown error'
  if (err.code === 8 || err.status === 'RESOURCE_EXHAUSTED') return httpFailure('vertex', 429, message, model)
  if (err.code === 5 || err.status === 'NOT_FOUND') return httpFailure('vertex', 404, message, model)
  if (err.code === 7 || err.code === 16 || err.status === 'PERMISSION_DENIED' || err.status === 'UNAUTHENTICATED') return httpFailure('vertex', 403, message, model)
  if (/responsible ai|safety|blocked by/i.test(message)) return new VideoSafetyFiltered(message)
  return new VideoError('VIDEO_FAILED', `Vertex AI video generation failed: ${message}`)
}

/** The job is done (and billed) by the time this runs, so every failure is marked billed. */
async function downloadGcs(uri: string, token: string, doFetch: typeof fetch, timeoutMs: number): Promise<Buffer> {
  const m = /^gs:\/\/([^/]+)\/(.+)$/.exec(uri)
  if (!m) throw markBilled(new VideoError('VIDEO_FAILED', `Vertex returned a video location I cannot read: ${uri}`))
  const url = `https://storage.googleapis.com/download/storage/v1/b/${m[1]}/o/${encodeURIComponent(m[2]!)}?alt=media`
  try {
    const res = await doFetch(url, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(timeoutMs) })
    if (!res.ok) throw httpFailure('vertex', res.status, await readErrorMessage(res), 'the output bucket object')
    return Buffer.from(await res.arrayBuffer())
  } catch (err) {
    if (err instanceof VideoError) throw markBilled(err)
    throw markBilled(new VideoError('VIDEO_FAILED', `The clip was made (and billed) but could not be downloaded from ${uri}: ${(err as Error).message}`))
  }
}

/** Returns the finished clips and any warnings. Throws VideoError (VideoSafetyFiltered when nothing came back). */
export async function generateWithVertex(
  req: VideoRequest,
  frames: { image?: VideoFrame; lastFrame?: VideoFrame },
  cfg: VertexConfig,
  deps: VertexDeps = {}
): Promise<{ videos: Buffer[]; warnings: string[] }> {
  const doFetch = deps.fetch ?? fetch
  const sleep = deps.sleep ?? ((ms: number) => new Promise<void>((r) => setTimeout(r, ms)))
  const now = deps.now ?? Date.now
  const interval = deps.pollIntervalMs ?? POLL_INTERVAL_MS
  const timeoutMs = deps.timeoutMs ?? GENERATION_TIMEOUT_MS
  const downloadTimeoutMs = deps.downloadTimeoutMs ?? DOWNLOAD_TIMEOUT_MS
  // Fetched per request (cheap while cached), so a token that expires during a 10-minute wait is replaced.
  const token = deps.getToken ?? (() => getVertexToken(now))
  const base = vertexBaseUrl(cfg.project, cfg.region, cfg.model)

  const send = async (url: string, body: unknown, bearer: string): Promise<Response> => {
    try {
      return await doFetch(url, { method: 'POST', headers: { Authorization: `Bearer ${bearer}`, 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
    } catch (err) {
      throw new VideoError('VIDEO_FAILED', `Could not reach Vertex AI: ${(err as Error).message}`)
    }
  }

  cfg.onProgress?.(`Starting ${cfg.model} on Vertex (${req.durationSeconds} s, ${req.resolution})`)
  const start = await send(`${base}:predictLongRunning`, buildVertexBody(req, frames), await token())
  if (!start.ok) {
    if (start.status === 401 || start.status === 403) resetVertexTokenCache()
    throw httpFailure('vertex', start.status, await readErrorMessage(start), cfg.model)
  }
  const { name } = (await start.json()) as { name?: string }
  if (!name) throw new VideoError('VIDEO_FAILED', 'Vertex AI did not return an operation to wait for.')

  // From here the job is running and will be billed, so a failure to follow it is marked billed.
  const lost = (why: string) =>
    markBilled(new VideoError('VIDEO_FAILED', `Lost track of the running Vertex job (${why}). It may still finish and be billed, so it was counted as spent. Operation: ${name}`))
  const began = now()
  let polls = 0
  let failures = 0
  let refreshed = false
  for (;;) {
    let op: Operation | undefined
    let problem = ''
    try {
      const bearer = await token()
      const res = await send(`${base}:fetchPredictOperation`, { operationName: name }, bearer)
      if (res.ok) {
        op = (await res.json()) as Operation
      } else if ((res.status === 401 || res.status === 403) && !refreshed) {
        // The token expired mid-wait: drop it and try once more with a fresh one.
        resetVertexTokenCache()
        refreshed = true
        problem = `status check returned ${res.status}`
      } else if (res.status === 429 || res.status >= 500) {
        problem = `status check returned ${res.status}: ${await readErrorMessage(res)}`
      } else {
        throw lost(`status check returned ${res.status}: ${await readErrorMessage(res)}`)
      }
    } catch (err) {
      if (err instanceof VideoError && err.details['billed']) throw err
      problem = (err as Error).message
    }

    if (op) {
      failures = 0
      if (op.done) {
        if (op.error) throw operationFailure(op.error, cfg.model)
        return collect(op, await token(), doFetch, downloadTimeoutMs)
      }
    } else {
      failures += 1
      if (failures >= MAX_POLL_FAILURES) throw lost(`${failures} status checks in a row failed; last: ${problem}`)
      cfg.onProgress?.(`Status check failed (${problem}); retrying`)
    }
    if (now() - began >= timeoutMs) {
      throw new VideoError('VIDEO_TIMEOUT', `Vertex AI did not finish within ${Math.round(timeoutMs / 60_000)} minutes. The job may still complete and be billed; try again later.`)
    }
    if (op) {
      polls += 1
      cfg.onProgress?.(`Still generating (${Math.round((now() - began) / 1000)} s, check ${polls})`)
    }
    await sleep(interval)
  }
}

async function collect(op: Operation, token: string, doFetch: typeof fetch, timeoutMs: number): Promise<{ videos: Buffer[]; warnings: string[] }> {
  const r = op.response ?? {}
  const warnings: string[] = []
  const videos: Buffer[] = []
  for (const v of r.videos ?? []) {
    if (v.bytesBase64Encoded) videos.push(Buffer.from(v.bytesBase64Encoded, 'base64'))
    else if (v.gcsUri) videos.push(await downloadGcs(v.gcsUri, token, doFetch, timeoutMs))
  }
  const filtered = r.raiMediaFilteredCount ?? 0
  const reasons = r.raiMediaFilteredReasons?.join('; ')
  if (videos.length === 0) throw new VideoSafetyFiltered(reasons)
  if (filtered > 0) warnings.push(`${filtered} clip(s) were blocked by the safety filter${reasons ? ` (${reasons})` : ''} but are still billed; the rest were kept.`)
  return { videos, warnings }
}
