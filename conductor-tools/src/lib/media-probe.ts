/**
 * Inspects and extracts frames from video/audio Creative media with ffprobe/ffmpeg: a system binary
 * on PATH first (respects whatever the user already has — no bundled Chromium-sized re-download),
 * else the ones bundled by `ffprobe-static`/`ffmpeg-static` (lazy-imported so requiring them doesn't
 * add to MCP server startup cost for sessions that never touch a video/audio Creative). Mirrors the
 * browser discovery in creative-browser.ts.
 *
 * `upload_creative_media` uses `probeMedia` for VIDEO/AUDIO (images keep the existing magic-byte
 * header parse in image-dimensions.ts — no decode needed there); it and `render_creative`/CLIP use
 * `extractPoster` for a VIDEO's poster JPEG.
 */
import { spawn } from 'node:child_process'
import { existsSync } from 'node:fs'
import { extname } from 'node:path'
import { createRequire } from 'node:module'

// ffprobe-static/ffmpeg-static are plain CJS with no published types; `require` (rather than a typed
// `import`) keeps them lazy (only resolved the first time a binary is actually needed) without fighting
// TypeScript over declarations for an untyped module.
const require = createRequire(import.meta.url)

export type MediaKind = 'IMAGE' | 'VIDEO' | 'AUDIO'

export interface MediaProbeResult {
  kind: MediaKind
  contentType: string
  width?: number
  height?: number
  durationSeconds?: number
  hasAudio?: boolean
  codec?: string
}

interface FfprobeStream {
  codec_type?: string
  codec_name?: string
  width?: number
  height?: number
  duration?: string
  nb_frames?: string
  disposition?: { attached_pic?: number }
}

interface FfprobeFormat {
  duration?: string
  format_name?: string
}

export interface FfprobeJson {
  streams?: FfprobeStream[]
  format?: FfprobeFormat
}

export const NO_FFPROBE_HINT =
  'No ffprobe could be found to inspect this media. Install ffmpeg (it includes ffprobe) system-wide, ' +
  'or reinstall @cliangdev/conductor so its bundled ffprobe-static dependency is present.'
export const NO_FFMPEG_HINT =
  'No ffmpeg could be found to process this media. Install ffmpeg system-wide, or reinstall ' +
  '@cliangdev/conductor so its bundled ffmpeg-static dependency is present.'

const EXTENSION_CONTENT_TYPES: Record<string, string> = {
  '.mp4': 'video/mp4',
  '.m4v': 'video/mp4',
  '.mov': 'video/quicktime',
  '.webm': 'video/webm',
  '.mp3': 'audio/mpeg',
  '.m4a': 'audio/mp4',
  '.aac': 'audio/aac',
  '.wav': 'audio/wav',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
}

function contentTypeFor(kind: MediaKind, filePath: string, codecName: string | undefined): string {
  const ext = extname(filePath).toLowerCase()
  const byExtension = EXTENSION_CONTENT_TYPES[ext]
  if (byExtension) return byExtension
  if (kind === 'VIDEO') return 'video/mp4'
  if (kind === 'AUDIO') return codecName === 'mp3' ? 'audio/mpeg' : 'audio/mp4'
  return codecName === 'png' ? 'image/png' : 'image/jpeg'
}

/**
 * Pure parsing of an already-fetched `ffprobe -show_streams -show_format` JSON document — split out
 * from `probeMedia` so tests can exercise it against fixture JSON without spawning a real process.
 *
 * A video stream marked `disposition.attached_pic` is cover art embedded in an audio file (common in
 * mp3/m4a), not a picture track — it's excluded from the video-stream check so a song with cover art
 * doesn't get classified as VIDEO. A video stream with no duration and at most one frame is a still
 * image ffprobe happened to be pointed at (format `image2`/`png_pipe`/etc.), not a video.
 */
export function parseProbeJson(json: FfprobeJson, filePath: string): MediaProbeResult {
  const streams = json.streams ?? []
  const format = json.format ?? {}
  const videoStreams = streams.filter((s) => s.codec_type === 'video' && s.disposition?.attached_pic !== 1)
  const audioStreams = streams.filter((s) => s.codec_type === 'audio')

  if (videoStreams.length > 0) {
    const v = videoStreams[0]!
    const durationRaw = format.duration ?? v.duration
    const duration = durationRaw ? parseFloat(durationRaw) : undefined
    const nbFrames = v.nb_frames ? parseInt(v.nb_frames, 10) : undefined
    const looksLikeStillImage = !duration && (nbFrames === undefined || nbFrames <= 1)

    if (looksLikeStillImage && audioStreams.length === 0) {
      return { kind: 'IMAGE', contentType: contentTypeFor('IMAGE', filePath, v.codec_name), width: v.width, height: v.height, codec: v.codec_name }
    }
    return {
      kind: 'VIDEO',
      contentType: contentTypeFor('VIDEO', filePath, v.codec_name),
      width: v.width,
      height: v.height,
      durationSeconds: duration,
      hasAudio: audioStreams.length > 0,
      codec: v.codec_name,
    }
  }

  if (audioStreams.length > 0) {
    const a = audioStreams[0]!
    const durationRaw = format.duration ?? a.duration
    const duration = durationRaw ? parseFloat(durationRaw) : undefined
    return { kind: 'AUDIO', contentType: contentTypeFor('AUDIO', filePath, a.codec_name), durationSeconds: duration, codec: a.codec_name }
  }

  throw new Error(`ffprobe found no usable video or audio stream in "${filePath}"`)
}

let cachedFfprobePath: string | undefined
let cachedFfmpegPath: string | undefined

/** True once the named command launches at all (any exit code) rather than failing to spawn
 * (ENOENT) — that's enough to know it's a real binary on PATH, without caring what `-version`
 * prints. Bounded by a timeout so a binary that launches but hangs doesn't wedge discovery forever. */
function systemBinaryAvailable(command: string): Promise<boolean> {
  return new Promise((resolve) => {
    let settled = false
    const finish = (ok: boolean) => {
      if (settled) return
      settled = true
      resolve(ok)
    }
    let child
    try {
      child = spawn(command, ['-version'], { stdio: 'ignore' })
    } catch {
      finish(false)
      return
    }
    child.on('error', () => finish(false))
    child.on('exit', (code) => finish(code === 0))
    const timer = setTimeout(() => finish(false), 5000)
    timer.unref?.()
  })
}

export async function resolveFfprobePath(): Promise<string> {
  if (cachedFfprobePath) return cachedFfprobePath
  if (await systemBinaryAvailable('ffprobe')) {
    cachedFfprobePath = 'ffprobe'
    return cachedFfprobePath
  }
  try {
    const mod = require('ffprobe-static') as { path?: string }
    const bundledPath = mod.path
    if (bundledPath && existsSync(bundledPath)) {
      cachedFfprobePath = bundledPath
      return bundledPath
    }
  } catch {
    // fall through to the error below
  }
  throw new Error(NO_FFPROBE_HINT)
}

export async function resolveFfmpegPath(): Promise<string> {
  if (cachedFfmpegPath) return cachedFfmpegPath
  if (await systemBinaryAvailable('ffmpeg')) {
    cachedFfmpegPath = 'ffmpeg'
    return cachedFfmpegPath
  }
  try {
    const bundledPath = require('ffmpeg-static') as string | null
    if (bundledPath && existsSync(bundledPath)) {
      cachedFfmpegPath = bundledPath
      return bundledPath
    }
  } catch {
    // fall through to the error below
  }
  throw new Error(NO_FFMPEG_HINT)
}

/** Test-only: binary discovery caches its result for the process lifetime (real launches are slow
 * enough to matter within one CLI/MCP run), so tests that vary system-binary availability need to
 * reset it between cases. */
export function resetMediaBinaryCache(): void {
  cachedFfprobePath = undefined
  cachedFfmpegPath = undefined
}

function runFfprobe(ffprobePath: string, filePath: string): Promise<FfprobeJson> {
  return new Promise((resolve, reject) => {
    const child = spawn(ffprobePath, ['-v', 'error', '-print_format', 'json', '-show_streams', '-show_format', filePath])
    let stdout = ''
    let stderr = ''
    child.stdout.on('data', (d) => (stdout += d))
    child.stderr.on('data', (d) => (stderr += d))
    child.on('error', reject)
    child.on('exit', (code) => {
      if (code !== 0) {
        reject(new Error(`ffprobe failed (exit ${code}) for "${filePath}": ${stderr.trim() || 'no error output'}`))
        return
      }
      try {
        resolve(JSON.parse(stdout) as FfprobeJson)
      } catch (err) {
        reject(new Error(`ffprobe produced unparsable JSON for "${filePath}": ${err instanceof Error ? err.message : String(err)}`))
      }
    })
  })
}

/** Probes a local media file with ffprobe and classifies it as IMAGE, VIDEO or AUDIO. Throws
 * `NO_FFPROBE_HINT` when no usable ffprobe binary can be found at all. */
export async function probeMedia(filePath: string): Promise<MediaProbeResult> {
  const ffprobePath = await resolveFfprobePath()
  const json = await runFfprobe(ffprobePath, filePath)
  return parseProbeJson(json, filePath)
}

function runFfmpegPoster(ffmpegPath: string, videoPath: string, outJpg: string, atSeconds: number): Promise<void> {
  return new Promise((resolve, reject) => {
    const args = ['-y', '-ss', String(atSeconds), '-i', videoPath, '-frames:v', '1', '-q:v', '2', outJpg]
    const child = spawn(ffmpegPath, args, { stdio: ['ignore', 'ignore', 'pipe'] })
    let stderr = ''
    child.stderr.on('data', (d) => (stderr += d))
    child.on('error', reject)
    child.on('exit', (code) => {
      if (code === 0 && existsSync(outJpg)) resolve()
      else reject(new Error(`ffmpeg poster extraction failed (exit ${code}) for "${videoPath}": ${stderr.trim().slice(-2000) || 'no error output'}`))
    })
  })
}

/** Extracts a single JPEG poster frame from a video at `atSeconds` (default 1s). Falls back to 0s
 * when the clip is shorter than that (or the seek otherwise fails) rather than giving up. */
export async function extractPoster(videoPath: string, outJpg: string, atSeconds = 1): Promise<void> {
  const ffmpegPath = await resolveFfmpegPath()
  try {
    await runFfmpegPoster(ffmpegPath, videoPath, outJpg, atSeconds)
  } catch (err) {
    if (atSeconds === 0) throw err
    await runFfmpegPoster(ffmpegPath, videoPath, outJpg, 0)
  }
}
