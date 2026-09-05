'use client'

// COND-23 T2.4: the human-facing half of the file-Asset upload lifecycle.
//
// Three calls, in this order, and the middle one never touches the Conductor API:
//   1. POST .../assets/uploads  — mints a PENDING Asset row and a short-lived upload URL
//   2. PUT  <that URL>          — the bytes go straight to storage (signed GCS PUT in the cloud, the
//                                 internal passthrough locally). Routing megabytes of video through
//                                 the API would be the whole point of the signed URL thrown away.
//   3. POST .../assets/{id}/confirm — flips the row to UPLOADED
//
// Video metadata is measured here rather than on the server on purpose. The backend reads an image's
// real pixel dimensions out of the uploaded bytes at confirm, but no container parser ships with the
// JDK, so a video's width/height/duration have exactly one source: an HTMLVideoElement that has
// loaded the file's metadata. MediaTargetValidator treats a null dimension as "not measured" and
// blocks approval on any rule it cannot evaluate, so a video that skipped this step could never
// reach Approved.

import { useCallback, useMemo, useRef, useState } from 'react'
import { Film, ImageIcon, UploadCloud } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Select } from '@/components/ui/select'
import { apiDelete, apiErrorMessage, apiPost } from '@/lib/api'
import { cn } from '@/lib/utils'
import type { WorkflowView, WorkItemAsset } from '@/types/workItem'

/** Mirrors the backend allowlist (AssetUploadPolicy.ALLOWED_CONTENT_TYPES) — it stays authoritative. */
export const ALLOWED_MEDIA_CONTENT_TYPES = [
  'image/png',
  'image/jpeg',
  'image/gif',
  'image/webp',
  'video/mp4',
  'video/quicktime',
] as const

/**
 * The upload fields v2's AssetResponse adds for a kind=file Asset. Declared here rather than widened
 * onto the shared {@link WorkItemAsset} because they are meaningless for the link assets the
 * properties panel renders.
 */
export interface MediaAsset extends WorkItemAsset {
  uploadStatus?: 'PENDING' | 'UPLOADED' | null
  contentType?: string | null
  sizeBytes?: number | null
  /** Short-lived signed read URL, minted per response — present only once the upload is confirmed. */
  previewUrl?: string | null
}

export interface VideoMetadata {
  width: number | null
  height: number | null
  durationSeconds: number | null
}

interface CreateAssetUploadResponse {
  assetId: string
  uploadUrl: string
  gcsPath: string
  expiresAt: string
}

/** How long to wait for a browser to report video metadata before uploading it unmeasured. */
const METADATA_TIMEOUT_MS = 15_000

export function isVideoContentType(contentType: string | null | undefined): boolean {
  return !!contentType && contentType.startsWith('video/')
}

/**
 * Reads width/height/duration off a throwaway HTMLVideoElement pointed at the local file. Resolves
 * null when the browser cannot decode the container — an unmeasured upload still beats a blocked
 * one, and the approval gate surfaces the missing shape to a human either way.
 */
export function measureVideoMetadata(file: File): Promise<VideoMetadata | null> {
  return new Promise((resolve) => {
    const objectUrl = URL.createObjectURL(file)
    const probe = document.createElement('video')
    let settled = false

    const finish = (result: VideoMetadata | null) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      probe.removeAttribute('src')
      URL.revokeObjectURL(objectUrl)
      resolve(result)
    }

    const timer = setTimeout(() => finish(null), METADATA_TIMEOUT_MS)

    probe.preload = 'metadata'
    probe.onloadedmetadata = () => {
      const duration = probe.duration
      finish({
        width: probe.videoWidth || null,
        height: probe.videoHeight || null,
        // Round to centiseconds: the raw float carries meaningless precision into a DECIMAL column.
        durationSeconds: Number.isFinite(duration) && duration > 0
          ? Math.round(duration * 100) / 100
          : null,
      })
    }
    probe.onerror = () => finish(null)
    probe.src = objectUrl
  })
}

/**
 * Client-side mirror of AssetUploadPolicy.isApprovedOrLater: a status sits at or beyond the review
 * gate when it is reachable from the gate's target without re-entering the review status. Derived
 * from the workflow definition, so no status id is hardcoded — and excluding the review status is
 * what keeps a "send back" edge (MARKETING's APPROVED -> IN_REVIEW) from dragging DRAFT into the
 * locked set. The backend rejects the mutation regardless; this only decides what the UI offers.
 */
export function isApprovedOrLater(view: WorkflowView | undefined, statusId: string | undefined): boolean {
  if (!view || !statusId) return false
  const gate = view.transitions.find((t) => t.requiresReview)
  if (!gate || statusId === gate.from) return false
  return reachableFromApproved(view, gate, statusId)
}

/**
 * Client-side mirror of AssetUploadPolicy.isUnderReviewOrLater: the content freeze, which starts one
 * status earlier than the revert boundary above — at the review status itself.
 *
 * A Work Item under review is being read by somebody. Letting its author rewrite the caption, swap the
 * media or move the schedule mid-read hands that reviewer an approval for something they never saw, so
 * the reviewer decides when the pen comes back, by sending it back. DRAFT and CHANGES_REQUESTED stay
 * editable — that is where an author is meant to work.
 *
 * The backend refuses the mutation regardless; this only decides what the UI offers.
 */
export function isUnderReviewOrLater(
  view: WorkflowView | undefined,
  statusId: string | undefined
): boolean {
  if (!view || !statusId) return false
  const gate = view.transitions.find((t) => t.requiresReview)
  if (!gate) return false
  if (statusId === gate.from) return true
  return reachableFromApproved(view, gate, statusId)
}

function reachableFromApproved(
  view: WorkflowView,
  gate: { from: string; to: string },
  statusId: string
): boolean {

  const seen = new Set([gate.to])
  const frontier = [gate.to]
  while (frontier.length > 0) {
    const current = frontier.shift()!
    if (current === statusId) return true
    for (const next of view.transitions.filter((t) => t.from === current).map((t) => t.to)) {
      if (next === gate.from || seen.has(next)) continue
      seen.add(next)
      frontier.push(next)
    }
  }
  return false
}

function statusDisplayLabel(view: WorkflowView | undefined, statusId: string | undefined): string {
  if (!statusId) return 'its current status'
  return view?.statuses.find((s) => s.id === statusId)?.label ?? statusId
}


function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null) return ''
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`
}

/**
 * PUTs the raw file at the minted URL with progress. XHR rather than fetch because only XHR reports
 * upload progress. No Authorization header: the URL carries its own signature, and adding one would
 * make GCS reject the PUT.
 */
/** Exported for the compose form, which uploads the same way before the Post page ever opens. */
export function putToSignedUrl(
  uploadUrl: string,
  file: File,
  onProgress: (percent: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest()
    xhr.open('PUT', uploadUrl)
    xhr.setRequestHeader('Content-Type', file.type)
    xhr.upload.onprogress = (event: ProgressEvent) => {
      if (event.lengthComputable && event.total > 0) {
        onProgress(Math.round((event.loaded / event.total) * 100))
      }
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve()
      else reject(new Error(`Upload failed — storage rejected the file (${xhr.status})`))
    }
    xhr.onerror = () => reject(new Error('Upload failed — could not reach storage'))
    xhr.send(file)
  })
}

export interface MediaUploadPanelProps {
  projectId: string
  workItemId: string
  token: string
  /** Current Work Item status — decides whether the upload affordance is offered at all. */
  status: string
  /** Workflow display metadata; supplies the asset types and the review gate. */
  workflowView?: WorkflowView
  assets: MediaAsset[]
  /** Refetch the Work Item's assets so the new preview appears. */
  onUploaded: () => void | Promise<void>
  className?: string
}

export function MediaUploadPanel({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  assets,
  onUploaded,
  className,
}: MediaUploadPanelProps) {
  const assetTypes = useMemo(() => workflowView?.assetTypes ?? [], [workflowView])
  /**
   * The asset type the upload is filed under.
   *
   * Not a choice any more, and deliberately so. It used to be a four-way picker labelled "Channel",
   * which read as "which platform is this for" and decided nothing: every publish action selects media
   * by content type, and the whole bundle goes out to every selected account regardless. Nothing filters
   * on it either — the asset library filters by media type, workflow and status. So the control asked a
   * question whose answer changed nothing visible, and made correct behaviour look broken (a PNG filed
   * as a Facebook post is still refused by Instagram's JPEG rule at the approval gate).
   *
   * The field itself stays because the API requires one and it still carries meaning for *link* assets,
   * where the type really is the kind of thing being recorded (a github_pr, a published instagram_post).
   * If per-asset targeting ever arrives — this image to that account and not this one — that is a real
   * feature with a real control, not this one renamed back.
   */
  const assetType = assetTypes[0] ?? ''
  const [uploading, setUploading] = useState(false)
  const [progress, setProgress] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [dragging, setDragging] = useState(false)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const locked = isUnderReviewOrLater(workflowView, status)
  const selectedType = assetType
  const mediaAssets = assets.filter((a) => a.kind === 'file')

  const upload = useCallback(
    async (file: File) => {
      setError(null)
      setUploading(true)
      setProgress(0)
      try {
        // Images are measured server-side from the bytes at confirm, so only video pays for this.
        const measured = isVideoContentType(file.type) ? await measureVideoMetadata(file) : null
        const ticket = await apiPost<CreateAssetUploadResponse>(
          `/api/v2/projects/${projectId}/work-items/${workItemId}/assets/uploads`,
          {
            type: selectedType,
            label: file.name,
            filename: file.name,
            contentType: file.type,
            sizeBytes: file.size,
            ...(measured?.width ? { width: measured.width } : {}),
            ...(measured?.height ? { height: measured.height } : {}),
            ...(measured?.durationSeconds ? { durationSeconds: measured.durationSeconds } : {}),
          },
          token,
        )
        await putToSignedUrl(ticket.uploadUrl, file, setProgress)
        // Answers 204 — apiPost resolves on an empty body rather than trying to parse one.
        await apiPost<void>(
          `/api/v2/projects/${projectId}/work-items/${workItemId}/assets/${ticket.assetId}/confirm`,
          { sizeBytes: file.size },
          token,
        )
        await onUploaded()
      } catch (err) {
        setError(apiErrorMessage(err, err instanceof Error ? err.message : 'Upload failed'))
      } finally {
        setUploading(false)
      }
    },
    [projectId, workItemId, selectedType, token, onUploaded],
  )

  function handleFiles(files: FileList | null) {
    const file = files?.[0]
    if (!file || uploading) return
    upload(file)
  }

  const [removing, setRemoving] = useState<string | null>(null)

  /**
   * Removing a file is only offered before review — the server refuses it afterwards, and a control that
   * always fails is worse than none. Deleting is how a wrong upload is corrected, so it never asks twice.
   */
  async function remove(assetId: string) {
    setError(null)
    setRemoving(assetId)
    try {
      await apiDelete(`/api/v2/projects/${projectId}/work-items/${workItemId}/assets/${assetId}`, token)
      await onUploaded()
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not remove the file'))
    } finally {
      setRemoving(null)
    }
  }

  return (
    <Card className={className}>
      <CardHeader>
        <h2 className="text-sm font-semibold text-foreground">Media</h2>
        {mediaAssets.length > 0 && (
          <span className="text-xs text-muted-foreground">
            {mediaAssets.length} file{mediaAssets.length !== 1 ? 's' : ''}
          </span>
        )}
      </CardHeader>

      <div className="space-y-4 p-4">
        {mediaAssets.length > 0 && (
          <ul className="space-y-3">
            {mediaAssets.map((asset) => (
              <li key={asset.id} className="overflow-hidden rounded-md border border-border">
                {asset.previewUrl ? (
                  isVideoContentType(asset.contentType) ? (
                    <video
                      controls
                      src={asset.previewUrl}
                      className="block max-h-80 w-full bg-surface-3"
                      aria-label={asset.label || asset.type}
                    />
                  ) : (
                    // eslint-disable-next-line @next/next/no-img-element
                    <img
                      src={asset.previewUrl}
                      alt={asset.label || asset.type}
                      className="block max-h-80 w-full bg-surface-3 object-contain"
                    />
                  )
                ) : (
                  <div className="flex items-center gap-2 px-3 py-4 text-sm text-muted-foreground">
                    <UploadCloud className="h-4 w-4 shrink-0" aria-hidden />
                    Upload not finished — this file has no preview yet.
                  </div>
                )}
                <div className="flex items-center justify-between gap-3 border-t border-border px-3 py-2 text-xs text-muted-foreground">
                  <span className="flex min-w-0 items-center gap-1.5">
                    {isVideoContentType(asset.contentType) ? (
                      <Film className="h-3.5 w-3.5 shrink-0" />
                    ) : (
                      <ImageIcon className="h-3.5 w-3.5 shrink-0" />
                    )}
                    <span className="truncate">{asset.label || asset.type}</span>
                  </span>
                  <span className="flex shrink-0 items-center gap-2">
                    {formatBytes(asset.sizeBytes)}
                    {!locked && (
                      <Button
                        type="button"
                        variant="ghost"
                        size="sm"
                        className="h-6 px-1.5 text-xs"
                        disabled={removing === asset.id || uploading}
                        onClick={() => void remove(asset.id)}
                        aria-label={`Remove ${asset.label || asset.type}`}
                      >
                        {removing === asset.id ? 'Removing…' : 'Remove'}
                      </Button>
                    )}
                  </span>
                </div>
              </li>
            ))}
          </ul>
        )}

        {locked ? (
          <Alert variant="info">
            Media is locked while this {workflowView?.noun?.toLowerCase() ?? 'item'} is{' '}
            {statusDisplayLabel(workflowView, status)}. It has to be sent back for changes before a file
            can be added or replaced.
          </Alert>
        ) : (
          <>


            <div
              onDragOver={(e) => {
                e.preventDefault()
                setDragging(true)
              }}
              onDragLeave={() => setDragging(false)}
              onDrop={(e) => {
                e.preventDefault()
                setDragging(false)
                handleFiles(e.dataTransfer?.files ?? null)
              }}
              className={cn(
                'flex flex-col items-center gap-2 rounded-md border border-dashed px-4 py-6 text-center transition-colors',
                dragging ? 'border-primary bg-accent-soft' : 'border-border-strong bg-surface',
              )}
            >
              <UploadCloud className="h-5 w-5 text-foreground-subtle" aria-hidden />
              <p className="text-sm text-muted-foreground">
                Drop an image or video here, or choose a file. PNG, JPEG, GIF, WebP, MP4 and MOV.
              </p>
              <input
                ref={fileInputRef}
                type="file"
                className="sr-only"
                accept={ALLOWED_MEDIA_CONTENT_TYPES.join(',')}
                aria-label="Media file"
                onChange={(e) => {
                  handleFiles(e.target.files)
                  e.target.value = ''
                }}
              />
              <Button
                type="button"
                variant="outline"
                size="sm"
                disabled={uploading || !selectedType}
                onClick={() => fileInputRef.current?.click()}
              >
                {uploading ? 'Uploading…' : 'Choose file'}
              </Button>
            </div>

            {uploading && (
              <div>
                <div
                  role="progressbar"
                  aria-label="Upload progress"
                  aria-valuenow={progress}
                  aria-valuemin={0}
                  aria-valuemax={100}
                  className="h-1.5 w-full overflow-hidden rounded-full bg-surface-3"
                >
                  <div className="h-full bg-primary transition-all" style={{ width: `${progress}%` }} />
                </div>
                <p className="mt-1 text-xs text-muted-foreground">{progress}% uploaded</p>
              </div>
            )}
          </>
        )}

        {error && <Alert variant="destructive">{error}</Alert>}
      </div>
    </Card>
  )
}
