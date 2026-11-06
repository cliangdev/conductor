/**
 * Gemini Omni video through the Gemini API Interactions endpoint. One synchronous call per clip.
 * Response and request shapes below were taken from the docs on 2026-11-04 and are to be verified live;
 * the reader is deliberately tolerant. The key is sent only as `x-goog-api-key` and never logged or echoed.
 */
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { runCapture } from './image-common.js'
import { resolveFfmpegPath } from './media-probe.js'
import { readConfig } from './config.js'
import { VideoError, VideoSafetyFiltered, httpFailure, markBilled, readErrorMessage, type VideoFrame, type VideoRequest } from './video-common.js'

const API = 'https://generativelanguage.googleapis.com/v1beta'

export type GeminiKeySource = 'CONDUCTOR_GEMINI_API_KEY' | 'GEMINI_API_KEY' | 'config'

export function resolveGeminiKey(): { key: string; source: GeminiKeySource } | null {
  for (const source of ['CONDUCTOR_GEMINI_API_KEY', 'GEMINI_API_KEY'] as const) {
    const key = process.env[source]?.trim()
    if (key) return { key, source }
  }
  const key = readConfig()?.geminiApiKey?.trim()
  return key ? { key, source: 'config' } : null
}

export interface OmniDeps {
  fetch?: typeof fetch
  /** Returns the audio-free bytes, or null when ffmpeg is unavailable or fails. */
  stripAudio?: (video: Buffer) => Promise<Buffer | null>
  timeoutMs?: number
  downloadTimeoutMs?: number
}

/** Omni has no first/last-frame fields, so the frames' roles are stated in the prompt. */
export function buildOmniPrompt(req: VideoRequest, frames: { image?: boolean; lastFrame?: boolean } = {}): string {
  const parts = [req.prompt.trim(), `The clip is ${req.durationSeconds} seconds long.`]
  if (frames.image && frames.lastFrame) {
    parts.push('The first image is the opening frame: start exactly on it. The second image is the closing frame: end exactly on it.')
  } else if (frames.image) {
    parts.push('The image is the opening frame: start exactly on it.')
  }
  if (req.negativePrompt) parts.push(`Avoid: ${req.negativePrompt}`)
  return parts.join('\n')
}

export function buildOmniBody(req: VideoRequest, model: string, frames: { image?: VideoFrame; lastFrame?: VideoFrame }): Record<string, unknown> {
  const input: Array<Record<string, unknown>> = []
  for (const f of [frames.image, frames.lastFrame]) {
    if (f) input.push({ type: 'image', data: f.base64, mime_type: f.mimeType })
  }
  input.push({ type: 'text', text: buildOmniPrompt(req, { image: !!frames.image, lastFrame: !!frames.lastFrame }) })
  return { model, input, response_format: { type: 'video', aspect_ratio: req.aspect, resolution: req.resolution }, store: false }
}

interface ContentItem {
  type?: string
  data?: string
  uri?: string
}

function findVideo(body: unknown): ContentItem | undefined {
  const b = body as { steps?: Array<{ type?: string; content?: ContentItem[] }>; outputs?: ContentItem[] }
  for (const step of b.steps ?? []) {
    if (step.type !== 'model_output') continue
    const hit = (step.content ?? []).find((c) => c.type === 'video' && (c.data || c.uri))
    if (hit) return hit
  }
  return (b.outputs ?? []).find((c) => c.type === 'video' && (c.data || c.uri))
}

/** Removes the audio track, copying the picture untouched. Null when ffmpeg is missing or fails. */
export async function stripAudioWithFfmpeg(video: Buffer): Promise<Buffer | null> {
  let dir: string | undefined
  try {
    const ffmpeg = await resolveFfmpegPath()
    dir = await mkdtemp(join(tmpdir(), 'conductor-omni-'))
    const input = join(dir, 'in.mp4')
    const output = join(dir, 'out.mp4')
    await writeFile(input, video)
    const res = await runCapture(ffmpeg, ['-y', '-v', 'error', '-i', input, '-c:v', 'copy', '-an', output])
    return res.code === 0 ? await readFile(output) : null
  } catch {
    return null
  } finally {
    if (dir) await rm(dir, { recursive: true, force: true }).catch(() => {})
  }
}

/** One clip. Throws VideoError; a missing video is a safety/blocked error (Google bills it). */
export async function generateOmniClip(
  req: VideoRequest,
  frames: { image?: VideoFrame; lastFrame?: VideoFrame },
  cfg: { apiKey: string; model: string; onProgress?: (message: string) => void },
  deps: OmniDeps = {}
): Promise<{ video: Buffer; warnings: string[] }> {
  const doFetch = deps.fetch ?? fetch
  const timeoutMs = deps.timeoutMs ?? 10 * 60_000
  const headers = { 'x-goog-api-key': cfg.apiKey, 'Content-Type': 'application/json' }
  cfg.onProgress?.(`Calling ${cfg.model} (${req.durationSeconds} s, ${req.resolution}); this takes a minute or two`)

  let res: Response
  try {
    res = await doFetch(`${API}/interactions`, {
      method: 'POST',
      headers,
      body: JSON.stringify(buildOmniBody(req, cfg.model, frames)),
      signal: AbortSignal.timeout(timeoutMs),
    })
  } catch (err) {
    const name = (err as Error).name
    if (name === 'TimeoutError' || name === 'AbortError') {
      throw new VideoError('VIDEO_TIMEOUT', `Gemini Omni did not answer within ${Math.round(timeoutMs / 60_000)} minutes. The job may still be billed; try again later.`)
    }
    throw new VideoError('VIDEO_FAILED', `Could not reach the Gemini API: ${(err as Error).message}`)
  }
  if (!res.ok) throw httpFailure('gemini', res.status, await readErrorMessage(res), cfg.model)

  const found = findVideo(await res.json())
  if (!found) throw new VideoSafetyFiltered('the response held no video, which usually means it was blocked')

  let video: Buffer
  if (found.data) {
    video = Buffer.from(found.data, 'base64')
  } else {
    const uri = found.uri!.replace(/^\/+/, '')
    const url = /^https?:\/\//.test(uri) ? uri : `${API}/${uri}:download?alt=media`
    // The clip is made (and billed) by now, so a failed download is marked billed.
    try {
      const dl = await doFetch(url, { headers: { 'x-goog-api-key': cfg.apiKey }, signal: AbortSignal.timeout(deps.downloadTimeoutMs ?? 2 * 60_000) })
      if (!dl.ok) throw httpFailure('gemini', dl.status, await readErrorMessage(dl), cfg.model)
      video = Buffer.from(await dl.arrayBuffer())
    } catch (err) {
      if (err instanceof VideoError) throw markBilled(err)
      throw markBilled(new VideoError('VIDEO_FAILED', `The clip was made (and billed) but could not be downloaded: ${(err as Error).message}`))
    }
  }

  const warnings: string[] = []
  if (!req.generateAudio) {
    const stripped = await (deps.stripAudio ?? stripAudioWithFfmpeg)(video)
    if (stripped) video = stripped
    else warnings.push(`Omni always makes audio and ffmpeg is not available to remove it, so this clip keeps its sound. Install ffmpeg or mute it when editing.`)
  }
  return { video, warnings }
}
