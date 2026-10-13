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

import { useEffect, useRef, useState } from 'react'
import {
  CheckIcon,
  FileAudioIcon,
  ImageOffIcon,
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
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'
import { MediaTile } from '@/components/marketing/creatives/MediaTile'
import {
  ALLOWED_CONTENT_TYPES,
  stripExtension,
  uploadMediaFile,
} from '@/components/marketing/creatives/mediaUpload'
import {
  deleteCreativePhoto,
  patchCreativePhoto,
  type CreativePhoto,
  type MediaKind,
} from '@/components/marketing/creatives/types'

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

interface MediaKindPanelProps {
  kind: MediaKind
  projectId: string
  token: string
  items: CreativePhoto[]
  onSelect: (item: CreativePhoto) => void
  onMediaChanged: () => void | Promise<void>
  /** Multi-select mode: ids in click order; a tile click toggles instead of picking. */
  selectedIds?: string[]
  /** Multi mode: where a finished upload goes (adds to the selection, never toggles it off). */
  onUploaded?: (item: CreativePhoto) => void
}

function MediaKindPanel({ kind, projectId, token, items, onSelect, onUploaded, onMediaChanged, selectedIds }: MediaKindPanelProps) {
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
    setUploading(true)
    try {
      const confirmed = await uploadMediaFile(projectId, token, kind, file, {
        label: labelOverride ?? label,
        source,
        licence,
        aiGenerated,
      })
      setWarnings(confirmed.warnings ?? [])
      await onMediaChanged()
      ;(onUploaded ?? onSelect)(confirmed)
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
              aria-pressed={selectedIds ? selectedIds.includes(item.id) : undefined}
              className={cn(
                'relative block aspect-square w-full bg-surface-3 disabled:cursor-not-allowed disabled:opacity-50',
                selectedIds?.includes(item.id) && 'ring-2 ring-inset ring-accent',
              )}
            >
              <MediaTile item={item} />
              {selectedIds?.includes(item.id) && (
                <span
                  data-testid={`selection-badge-${item.id}`}
                  className="absolute left-1 top-1 flex h-5 min-w-5 items-center justify-center rounded-full bg-accent px-1 text-[11px] font-semibold text-accent-foreground"
                >
                  {selectedIds.indexOf(item.id) + 1}
                </span>
              )}
            </button>
            {/* The label is how a library of many similar photos is told apart (and what Claude calls it). */}
            <p className="truncate border-t border-border bg-surface px-1.5 py-0.5 text-[11px] text-muted-foreground" title={item.label ?? undefined}>
              {item.label || 'Untitled'}
            </p>
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
  onSelect?: (item: CreativePhoto) => void
  /** Multi-select mode: tiles toggle, numbered in click order, and a confirm button ("Add N") hands
   *  the ordered items to `onSelectMany`. An upload inside the picker joins the selection. */
  multiple?: boolean
  onSelectMany?: (items: CreativePhoto[]) => void
  /** Called after an upload or a checked/blocked/delete change so the parent's media list refreshes. */
  onMediaChanged: () => void | Promise<void>
  /** Locks the picker to one kind and hides the tab bar — a picker opened for a Creative's photo only
   *  shows Photos; opened for a Clip's video only shows Videos. Omit to show all three tabs. */
  kind?: MediaKind
}

export function MediaPicker({
  projectId,
  token,
  open,
  onOpenChange,
  media,
  onSelect,
  multiple,
  onSelectMany,
  onMediaChanged,
  kind,
}: MediaPickerProps) {
  const [selection, setSelection] = useState<CreativePhoto[]>([])
  useEffect(() => {
    if (!open) setSelection([])
  }, [open])
  const toggle = (item: CreativePhoto) =>
    setSelection((prev) => (prev.some((p) => p.id === item.id) ? prev.filter((p) => p.id !== item.id) : [...prev, item]))
  // An upload hands back its item through the same callback — in multi mode that always adds.
  const handleSelect = multiple
    ? (item: CreativePhoto) => setSelection((prev) => (prev.some((p) => p.id === item.id) ? prev : [...prev, item]))
    : (item: CreativePhoto) => onSelect?.(item)

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
          onSelect={multiple ? toggle : handleSelect}
          onUploaded={multiple ? handleSelect : undefined}
          selectedIds={multiple ? selection.map((p) => p.id) : undefined}
          onMediaChanged={onMediaChanged}
        />
        {multiple && (
          <div className="flex justify-end border-t border-border pt-3">
            <Button
              type="button"
              size="sm"
              disabled={selection.length === 0}
              onClick={() => {
                onSelectMany?.(selection)
                setSelection([])
              }}
            >
              {selection.length > 0 ? `Add ${selection.length}` : 'Add'}
            </Button>
          </div>
        )}
      </div>
    </Modal>
  )
}

// Re-exported so existing importers (and tests) keep working after the extraction.
export { captureVideoPoster, measureAudioFile, measureVideoFile, type VideoMeta } from '@/components/marketing/creatives/mediaUpload'
