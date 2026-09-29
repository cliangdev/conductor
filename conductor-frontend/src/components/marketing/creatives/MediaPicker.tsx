'use client'

// COND-24 T6: the Creative media library — photos, videos and audio in one picker. Evolves
// PhotoPicker (COND-24 T2) into a Photos/Videos/Audio tabbed picker; PhotoPicker.tsx now wraps this
// component locked to the IMAGE tab so its existing call sites (the main photo and sequence beat
// pickers in CreativeEditor) keep working unchanged.
//
// Upload follows the same three-call shape everywhere in this file (mint -> PUT the bytes -> confirm)
// — see MediaUploadPanel.tsx's header for why metadata is always measured client-side rather than on
// the server. A video upload has a fourth step after confirm: capture a poster JPEG at 1s via a
// <canvas> and upload it through the poster mint/confirm endpoints, best-effort (a video with no
// poster still uploads and still works — it just shows a plain icon instead of a thumbnail).

import { useRef, useState } from 'react'
import {
  CheckIcon,
  FileAudioIcon,
  ImageOffIcon,
  PlayIcon,
  Trash2Icon,
  UploadCloudIcon,
  VideoOffIcon,
} from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Tabs } from '@/components/ui/tabs'
import { putToSignedUrl } from '@/components/workitems/MediaUploadPanel'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { formatDuration } from '@/lib/format'
import { cn } from '@/lib/utils'
import {
  confirmCreativePhoto,
  confirmCreativePhotoPoster,
  createCreativePhoto,
  createCreativePhotoPoster,
  deleteCreativePhoto,
  patchCreativePhoto,
  type CreativePhoto,
  type MediaKind,
} from '@/components/marketing/creatives/types'

const ALLOWED_CONTENT_TYPES: Record<MediaKind, string[]> = {
  IMAGE: ['image/jpeg', 'image/png', 'image/webp'],
  VIDEO: ['video/mp4', 'video/quicktime', 'video/webm'],
  AUDIO: ['audio/mpeg', 'audio/mp4', 'audio/aac', 'audio/wav'],
}

/** Matches video-contract.md's "Size caps" — VIDEO 1 GB, AUDIO 50 MB, IMAGE unchanged (no client cap;
 *  the server has never capped image size). */
const MAX_BYTES: Partial<Record<MediaKind, number>> = {
  VIDEO: 1024 * 1024 * 1024,
  AUDIO: 50 * 1024 * 1024,
}

/** Kind-specific copy and DOM hooks. The IMAGE entry intentionally matches PhotoPicker's original
 *  strings/testids exactly, so PhotoPicker.test.tsx keeps passing unmodified as a thin wrapper. */
const KIND_META: Record<
  MediaKind,
  {
    noun: string
    title: string
    fileLabel: string
    uploadLabel: string
    uploadingLabel: string
    gridTestId: string
    tabLabel: string
    emptyIcon: typeof ImageOffIcon
  }
> = {
  IMAGE: {
    noun: 'photo',
    title: 'Choose a photo',
    fileLabel: 'Photo file',
    uploadLabel: 'Upload a photo',
    uploadingLabel: 'Uploading…',
    gridTestId: 'photo-picker-grid',
    tabLabel: 'Photos',
    emptyIcon: ImageOffIcon,
  },
  VIDEO: {
    noun: 'video',
    title: 'Choose a clip',
    fileLabel: 'Video file',
    uploadLabel: 'Upload a video',
    uploadingLabel: 'Uploading…',
    gridTestId: 'video-picker-grid',
    tabLabel: 'Videos',
    emptyIcon: VideoOffIcon,
  },
  AUDIO: {
    noun: 'audio track',
    title: 'Choose audio',
    fileLabel: 'Audio file',
    uploadLabel: 'Upload audio',
    uploadingLabel: 'Uploading…',
    gridTestId: 'audio-picker-grid',
    tabLabel: 'Audio',
    emptyIcon: FileAudioIcon,
  },
}

/** The file name without its extension, e.g. "sunset-hero.jpg" -> "sunset-hero" — the label default
 *  so a newly uploaded item never has to fall back to showing its raw UUID. */
function stripExtension(filename: string): string {
  const idx = filename.lastIndexOf('.')
  return idx > 0 ? filename.slice(0, idx) : filename
}

function formatMaxBytes(bytes: number): string {
  const gb = bytes / (1024 * 1024 * 1024)
  if (gb >= 1) return `${gb % 1 === 0 ? gb : gb.toFixed(1)} GB`
  return `${Math.round(bytes / (1024 * 1024))} MB`
}

/** Reads a locally-selected image's real pixel dimensions via a throwaway <img> — Conductor has no
 *  server-side image pipeline, so this is the only place a photo's width/height are ever measured. */
function measureImageDimensions(file: File): Promise<{ width: number; height: number } | null> {
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

function MediaTile({ item }: { item: CreativePhoto }) {
  const kind = item.mediaKind ?? 'IMAGE'
  if (kind === 'VIDEO') {
    return (
      <>
        {item.posterUrl ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={item.posterUrl} alt={item.label || 'Clip'} className="h-full w-full object-cover" />
        ) : (
          <span className="flex h-full items-center justify-center text-muted-foreground">
            <VideoOffIcon className="h-4 w-4" aria-hidden />
          </span>
        )}
        <span className="pointer-events-none absolute inset-0 flex items-center justify-center">
          <PlayIcon className="h-5 w-5 fill-background text-background drop-shadow" aria-hidden />
        </span>
        {item.durationSeconds != null && (
          <span className="pointer-events-none absolute bottom-1 right-1 rounded bg-foreground/70 px-1 py-0.5 text-[10px] text-background">
            {formatDuration(item.durationSeconds)}
          </span>
        )}
      </>
    )
  }
  if (kind === 'AUDIO') {
    return (
      <span className="flex h-full flex-col items-center justify-center gap-1 text-muted-foreground">
        <FileAudioIcon className="h-5 w-5" aria-hidden />
        {item.durationSeconds != null && <span className="text-[10px]">{formatDuration(item.durationSeconds)}</span>}
      </span>
    )
  }
  return item.url ? (
    // eslint-disable-next-line @next/next/no-img-element
    <img src={item.url} alt={item.label || 'Creative photo'} className="h-full w-full object-cover" />
  ) : (
    <span className="flex h-full items-center justify-center text-muted-foreground">
      <ImageOffIcon className="h-4 w-4" aria-hidden />
    </span>
  )
}

interface MediaKindPanelProps {
  kind: MediaKind
  projectId: string
  token: string
  items: CreativePhoto[]
  onSelect: (item: CreativePhoto) => void
  onMediaChanged: () => void | Promise<void>
}

function MediaKindPanel({ kind, projectId, token, items, onSelect, onMediaChanged }: MediaKindPanelProps) {
  const meta = KIND_META[kind]
  const canManage = useCan('creative.manage')
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const [label, setLabel] = useState('')
  const [source, setSource] = useState('')
  const [licence, setLicence] = useState('')
  const [aiGenerated, setAiGenerated] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<CreativePhoto | null>(null)
  const [deleting, setDeleting] = useState(false)

  async function handleFile(file: File | undefined, labelOverride?: string) {
    if (!file) return
    setUploadError(null)
    setWarnings([])
    const maxBytes = MAX_BYTES[kind]
    if (maxBytes && file.size > maxBytes) {
      setUploadError(`${kind === 'VIDEO' ? 'Videos' : 'Audio files'} must be under ${formatMaxBytes(maxBytes)}.`)
      return
    }
    setUploading(true)
    try {
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
          label: (labelOverride ?? label).trim() || undefined,
          contentType: file.type,
          sizeBytes: file.size,
          mediaKind: kind,
          width: dims?.width,
          height: dims?.height,
          durationSeconds: durationSeconds ?? undefined,
          hasAudio: hasAudio ?? undefined,
          source: source.trim() || undefined,
          licence: licence.trim() || undefined,
          aiGenerated,
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

      setWarnings(confirmed.warnings ?? [])
      await onMediaChanged()
      onSelect(confirmed)
      setLabel('')
      setSource('')
      setLicence('')
      setAiGenerated(false)
    } catch (err) {
      setUploadError(apiErrorMessage(err, err instanceof Error ? err.message : 'Upload failed'))
    } finally {
      setUploading(false)
    }
  }

  async function toggleFlag(item: CreativePhoto, field: 'checked' | 'blocked') {
    setBusyId(item.id)
    try {
      await patchCreativePhoto(projectId, item.id, { [field]: !item[field] }, token)
      await onMediaChanged()
    } catch (err) {
      setUploadError(apiErrorMessage(err, `Could not update the ${meta.noun}`))
    } finally {
      setBusyId(null)
    }
  }

  async function handleDelete() {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await deleteCreativePhoto(projectId, deleteTarget.id, token)
      setDeleteTarget(null)
      await onMediaChanged()
    } catch (err) {
      setUploadError(apiErrorMessage(err, `Could not delete this ${meta.noun}`))
      setDeleteTarget(null)
    } finally {
      setDeleting(false)
    }
  }

  return (
    <div className="space-y-4">
      {canManage && (
        <div className="space-y-2 rounded-md border border-border p-3">
          <div>
            <Label htmlFor={`media-label-${kind}`} className="text-xs">
              Label
            </Label>
            <Input
              id={`media-label-${kind}`}
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="Defaults to the file name"
            />
          </div>
          <div className="grid grid-cols-2 gap-2">
            <div>
              <Label htmlFor={`media-source-${kind}`} className="text-xs">
                Source
              </Label>
              <Input id={`media-source-${kind}`} value={source} onChange={(e) => setSource(e.target.value)} placeholder="Unsplash" />
            </div>
            <div>
              <Label htmlFor={`media-licence-${kind}`} className="text-xs">
                Licence
              </Label>
              <Input id={`media-licence-${kind}`} value={licence} onChange={(e) => setLicence(e.target.value)} placeholder="CC0" />
            </div>
          </div>
          <Checkbox
            id={`media-ai-generated-${kind}`}
            checked={aiGenerated}
            onCheckedChange={setAiGenerated}
            label={`This ${meta.noun} is AI-generated`}
          />
          <input
            ref={fileInputRef}
            type="file"
            accept={ALLOWED_CONTENT_TYPES[kind].join(',')}
            className="sr-only"
            aria-label={meta.fileLabel}
            onChange={(e) => {
              const file = e.target.files?.[0]
              if (file) {
                const defaultLabel = label.trim() || stripExtension(file.name)
                setLabel(defaultLabel)
                void handleFile(file, defaultLabel)
              }
              e.target.value = ''
            }}
          />
          <Button type="button" variant="outline" size="sm" disabled={uploading} onClick={() => fileInputRef.current?.click()}>
            <UploadCloudIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
            {uploading ? meta.uploadingLabel : meta.uploadLabel}
          </Button>
          {uploadError && <Alert variant="destructive">{uploadError}</Alert>}
          {warnings.map((w) => (
            <Alert key={w} variant="warning">
              {w}
            </Alert>
          ))}
        </div>
      )}

      <div data-testid={meta.gridTestId} className="grid grid-cols-3 gap-2">
        {items.map((item) => (
          <div key={item.id} className="group relative overflow-hidden rounded-md border border-border">
            <button
              type="button"
              onClick={() => onSelect(item)}
              disabled={item.blocked}
              className="relative block aspect-square w-full bg-surface-3 disabled:cursor-not-allowed disabled:opacity-50"
            >
              <MediaTile item={item} />
            </button>
            {canManage && (
              <div className="flex items-center justify-between gap-1 border-t border-border bg-surface px-1.5 py-1 text-[11px]">
                <button
                  type="button"
                  disabled={busyId === item.id}
                  onClick={() => void toggleFlag(item, 'checked')}
                  className={cn(
                    'inline-flex items-center gap-1 rounded px-1',
                    item.checked ? 'text-status-approved' : 'text-muted-foreground',
                  )}
                  title={item.checked ? 'Checked' : 'Mark checked'}
                >
                  <CheckIcon className="h-3 w-3" aria-hidden />
                  {item.checked ? 'Checked' : 'Check'}
                </button>
                <button
                  type="button"
                  disabled={busyId === item.id}
                  onClick={() => void toggleFlag(item, 'blocked')}
                  className={cn('rounded px-1', item.blocked ? 'text-status-failed' : 'text-muted-foreground')}
                  title={item.blocked ? 'Blocked — unblock' : 'Block this ' + meta.noun}
                >
                  {item.blocked ? 'Blocked' : 'Block'}
                </button>
                <button
                  type="button"
                  disabled={busyId === item.id}
                  onClick={() => setDeleteTarget(item)}
                  className="rounded px-1 text-muted-foreground hover:text-destructive"
                  title={`Delete this ${meta.noun}`}
                  aria-label={`Delete ${meta.noun}${item.label ? ` ${item.label}` : ''}`}
                >
                  <Trash2Icon className="h-3 w-3" aria-hidden />
                </button>
              </div>
            )}
          </div>
        ))}
      </div>

      <ConfirmModal
        open={deleteTarget != null}
        title={`Delete this ${meta.noun}`}
        confirmLabel="Delete"
        busyLabel="Deleting…"
        busy={deleting}
        onConfirm={handleDelete}
        onCancel={() => setDeleteTarget(null)}
      >
        <p className="text-sm text-foreground">
          Permanently delete <strong>{deleteTarget?.label || `this ${meta.noun}`}</strong>? This cannot be undone.
        </p>
      </ConfirmModal>
    </div>
  )
}

export interface MediaPickerProps {
  projectId: string
  token: string
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The project's whole media library (every kind) — filtered locally per active tab, so switching
   *  tabs never costs a network round trip. */
  media: CreativePhoto[]
  onSelect: (item: CreativePhoto) => void
  /** Called after an upload or a checked/blocked/delete change so the parent's media list refreshes. */
  onMediaChanged: () => void | Promise<void>
  /** Locks the picker to one kind and hides the tab bar — a picker opened for a Creative's photo only
   *  shows Photos; opened for a Clip's video only shows Videos. Omit to show all three tabs. */
  kind?: MediaKind
}

export function MediaPicker({ projectId, token, open, onOpenChange, media, onSelect, onMediaChanged, kind }: MediaPickerProps) {
  const [activeKind, setActiveKind] = useState<MediaKind>(kind ?? 'IMAGE')
  const effectiveKind = kind ?? activeKind
  const meta = KIND_META[effectiveKind]
  const items = media.filter((m) => (m.mediaKind ?? 'IMAGE') === effectiveKind)

  return (
    <Modal open={open} onOpenChange={onOpenChange} title={meta.title} description="Pick from the library or upload a new one.">
      <div className="space-y-4">
        {!kind && (
          <Tabs
            ariaLabel="Media type"
            value={activeKind}
            onValueChange={(v) => setActiveKind(v as MediaKind)}
            items={(Object.keys(KIND_META) as MediaKind[]).map((k) => ({ value: k, label: KIND_META[k].tabLabel }))}
          />
        )}
        <MediaKindPanel
          key={effectiveKind}
          kind={effectiveKind}
          projectId={projectId}
          token={token}
          items={items}
          onSelect={onSelect}
          onMediaChanged={onMediaChanged}
        />
      </div>
    </Modal>
  )
}
