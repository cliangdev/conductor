// Shared media-upload pieces (extracted from MediaPicker so the Media library page uploads the same
// way): client-side measurement, size/type limits, and the mint -> signed PUT -> confirm flow with
// the best-effort video poster step.

import { putToSignedUrl } from '@/components/workitems/MediaUploadPanel'
import {
  confirmCreativePhoto,
  confirmCreativePhotoPoster,
  createCreativePhoto,
  createCreativePhotoPoster,
  type CreativePhoto,
  type MediaKind,
} from '@/components/marketing/creatives/types'

export const ALLOWED_CONTENT_TYPES: Record<MediaKind, string[]> = {
  IMAGE: ['image/jpeg', 'image/png', 'image/webp'],
  VIDEO: ['video/mp4', 'video/quicktime', 'video/webm'],
  AUDIO: ['audio/mpeg', 'audio/mp4', 'audio/aac', 'audio/wav'],
}

/** Matches video-contract.md's "Size caps" — VIDEO 1 GB, AUDIO 50 MB, IMAGE unchanged (no client cap;
 *  the server has never capped image size). */
export const MAX_BYTES: Partial<Record<MediaKind, number>> = {
  VIDEO: 1024 * 1024 * 1024,
  AUDIO: 50 * 1024 * 1024,
}

/** The file name without its extension, e.g. "sunset-hero.jpg" -> "sunset-hero" — the label default
 *  so a newly uploaded item never has to fall back to showing its raw UUID. */
export function stripExtension(filename: string): string {
  const idx = filename.lastIndexOf('.')
  return idx > 0 ? filename.slice(0, idx) : filename
}

export function formatMaxBytes(bytes: number): string {
  const gb = bytes / (1024 * 1024 * 1024)
  if (gb >= 1) return `${gb % 1 === 0 ? gb : gb.toFixed(1)} GB`
  return `${Math.round(bytes / (1024 * 1024))} MB`
}

/** Reads a locally-selected image's real pixel dimensions via a throwaway <img> — Conductor has no
 *  server-side image pipeline, so this is the only place a photo's width/height are ever measured. */
export function measureImageDimensions(file: File): Promise<{ width: number; height: number } | null> {
  return new Promise((resolve) => {
    const objectUrl = localObjectUrl(file)
    if (!objectUrl) {
      resolve(null)
      return
    }
    const img = new Image()
    img.onload = () => {
      resolve({ width: img.naturalWidth, height: img.naturalHeight })
      URL.revokeObjectURL(objectUrl)
    }
    img.onerror = () => {
      resolve(null)
      URL.revokeObjectURL(objectUrl)
    }
    img.src = objectUrl
  })
}

/** Best-effort audio-track detection off a loaded <video> — no standard API reports this reliably
 *  cross-browser, so this tries every vendor hint in turn and gives up to null ("could not tell")
 *  rather than guessing. See video-contract.md's "Web" section. */
function detectHasAudio(video: HTMLVideoElement): boolean | null {
  const v = video as HTMLVideoElement & {
    mozHasAudio?: boolean
    webkitAudioDecodedByteCount?: number
    audioTracks?: { length: number }
  }
  if (typeof v.mozHasAudio === 'boolean') return v.mozHasAudio
  if (typeof v.webkitAudioDecodedByteCount === 'number') return v.webkitAudioDecodedByteCount > 0
  if (v.audioTracks) return v.audioTracks.length > 0
  return null
}

export interface VideoMeta {
  width: number | null
  height: number | null
  durationSeconds: number | null
  hasAudio: boolean | null
}

/** An object URL for a local file the user picked — only ever a `blob:` URL, never markup. Anything else
 *  (which createObjectURL never returns) is refused, so a media element's src can't be fed arbitrary text. */
function localObjectUrl(file: File): string | null {
  const url = URL.createObjectURL(file)
  if (url.startsWith('blob:')) return url
  URL.revokeObjectURL(url)
  return null
}

/** Reads width/height/duration/hasAudio off a throwaway <video> pointed at the local file. */
export function measureVideoFile(file: File): Promise<VideoMeta | null> {
  return new Promise((resolve) => {
    const objectUrl = localObjectUrl(file)
    if (!objectUrl) {
      resolve(null)
      return
    }
    const video = document.createElement('video')
    video.preload = 'metadata'
    video.muted = true
    video.onloadedmetadata = () => {
      const duration = video.duration
      const result: VideoMeta = {
        width: video.videoWidth || null,
        height: video.videoHeight || null,
        durationSeconds: Number.isFinite(duration) && duration > 0 ? Math.round(duration * 100) / 100 : null,
        hasAudio: detectHasAudio(video),
      }
      URL.revokeObjectURL(objectUrl)
      resolve(result)
    }
    video.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(null)
    }
    video.src = objectUrl
  })
}

/** Reads duration off a throwaway <audio> pointed at the local file. */
export function measureAudioFile(file: File): Promise<number | null> {
  return new Promise((resolve) => {
    const objectUrl = localObjectUrl(file)
    if (!objectUrl) {
      resolve(null)
      return
    }
    const audio = document.createElement('audio')
    audio.preload = 'metadata'
    audio.onloadedmetadata = () => {
      const duration = audio.duration
      URL.revokeObjectURL(objectUrl)
      resolve(Number.isFinite(duration) && duration > 0 ? Math.round(duration * 100) / 100 : null)
    }
    audio.onerror = () => {
      URL.revokeObjectURL(objectUrl)
      resolve(null)
    }
    audio.src = objectUrl
  })
}

/** Captures a JPEG poster frame at `atSeconds` (clamped to the clip's own length) via a <canvas> draw
 *  of a seeked <video> — best-effort: a video that can't be decoded/seeked in-browser resolves null
 *  rather than blocking the upload. */
export function captureVideoPoster(file: File, atSeconds = 1): Promise<Blob | null> {
  return new Promise((resolve) => {
    const objectUrl = localObjectUrl(file)
    if (!objectUrl) {
      resolve(null)
      return
    }
    const video = document.createElement('video')
    video.preload = 'auto'
    video.muted = true
    video.playsInline = true
    let settled = false
    const finish = (result: Blob | null) => {
      if (settled) return
      settled = true
      URL.revokeObjectURL(objectUrl)
      resolve(result)
    }
    video.onloadedmetadata = () => {
      const duration = Number.isFinite(video.duration) ? video.duration : atSeconds
      video.currentTime = Math.max(0, Math.min(atSeconds, Math.max(0, duration - 0.1)))
    }
    video.onseeked = () => {
      try {
        const canvas = document.createElement('canvas')
        canvas.width = video.videoWidth
        canvas.height = video.videoHeight
        const ctx = canvas.getContext('2d')
        if (!ctx || canvas.width === 0 || canvas.height === 0) {
          finish(null)
          return
        }
        ctx.drawImage(video, 0, 0)
        canvas.toBlob((blob) => finish(blob), 'image/jpeg', 0.85)
      } catch {
        finish(null)
      }
    }
    video.onerror = () => finish(null)
    video.src = objectUrl
  })
}


export interface UploadMediaOptions {
  label?: string
  source?: string
  licence?: string
  aiGenerated?: boolean
}

/** Measures, mints, PUTs and confirms one file (plus a best-effort poster for video). Throws an Error
 *  (or the API error) on failure — callers surface it with apiErrorMessage. */
export async function uploadMediaFile(
  projectId: string,
  token: string,
  kind: MediaKind,
  file: File,
  opts: UploadMediaOptions = {},
): Promise<CreativePhoto> {
  const maxBytes = MAX_BYTES[kind]
  if (maxBytes && file.size > maxBytes) {
    throw new Error(`${kind === 'VIDEO' ? 'Videos' : 'Audio files'} must be under ${formatMaxBytes(maxBytes)}.`)
  }
  let dims: { width: number; height: number } | null = null
  let durationSeconds: number | null = null
  let hasAudio: boolean | null = null

  if (kind === 'IMAGE') {
    dims = await measureImageDimensions(file)
    if (!dims) throw new Error('Could not read this image — try a different file.')
  } else if (kind === 'VIDEO') {
    const meta = await measureVideoFile(file)
    if (!meta || !meta.durationSeconds) throw new Error('Could not read this video — try a different file.')
    dims = meta.width && meta.height ? { width: meta.width, height: meta.height } : null
    durationSeconds = meta.durationSeconds
    hasAudio = meta.hasAudio
  } else {
    durationSeconds = await measureAudioFile(file)
    if (!durationSeconds) throw new Error('Could not read this audio file — try a different file.')
  }

  const pending = await createCreativePhoto(
    projectId,
    {
      label: opts.label?.trim() || undefined,
      contentType: file.type,
      sizeBytes: file.size,
      mediaKind: kind,
      width: dims?.width,
      height: dims?.height,
      durationSeconds: durationSeconds ?? undefined,
      hasAudio: hasAudio ?? undefined,
      source: opts.source?.trim() || undefined,
      licence: opts.licence?.trim() || undefined,
      aiGenerated: opts.aiGenerated ?? false,
    },
    token,
  )
  if (!pending.uploadUrl) throw new Error('No upload URL was issued.')
  await putToSignedUrl(pending.uploadUrl, file, () => {})
  let confirmed = await confirmCreativePhoto(projectId, pending.id, file.size, token)

  if (kind === 'VIDEO') {
    // Best-effort: a poster capture/upload failure still leaves a perfectly usable video, just
    // without a thumbnail — never fail the whole upload over it.
    try {
      const posterBlob = await captureVideoPoster(file, 1)
      if (posterBlob) {
        const posterUpload = await createCreativePhotoPoster(projectId, confirmed.id, token)
        const posterFile = new File([posterBlob], 'poster.jpg', { type: 'image/jpeg' })
        await putToSignedUrl(posterUpload.uploadUrl, posterFile, () => {})
        confirmed = await confirmCreativePhotoPoster(projectId, confirmed.id, posterUpload.gcsPath, token)
      }
    } catch {
      // Poster is a nice-to-have — swallow and keep the confirmed video.
    }
  }
  return confirmed
}

/** The media kind an arbitrary File belongs to, by its content type; null when unsupported. */
export function mediaKindForFile(file: File): MediaKind | null {
  for (const kind of Object.keys(ALLOWED_CONTENT_TYPES) as MediaKind[]) {
    if (ALLOWED_CONTENT_TYPES[kind].includes(file.type)) return kind
  }
  return null
}
