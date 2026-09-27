'use client'

// COND-24 T2: pick a Creative photo from the project's library, or upload a new one. Upload follows
// the same three-call shape as MediaUploadPanel (mint -> PUT the bytes -> confirm), reusing its
// putToSignedUrl. Unlike a Work Item asset, a photo's width/height are *required* on mint (the
// backend has no image pipeline — see docs/publishing.md's "no server-side cropping" note — so
// dimensions are always client-declared), which is why this component measures the file itself
// before minting rather than leaving the fields optional.

import { useRef, useState } from 'react'
import { CheckIcon, ImageOffIcon, UploadCloudIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { putToSignedUrl } from '@/components/workitems/MediaUploadPanel'
import { useCan } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { cn } from '@/lib/utils'
import {
  confirmCreativePhoto,
  createCreativePhoto,
  patchCreativePhoto,
  type CreativePhoto,
} from '@/components/marketing/creatives/types'

const ALLOWED_PHOTO_TYPES = ['image/jpeg', 'image/png', 'image/webp']

/** The file name without its extension, e.g. "sunset-hero.jpg" -> "sunset-hero" — the label default
 *  so a newly uploaded photo never has to fall back to showing its raw UUID (see PhotoPicker.tsx's
 *  upload form and CreativeEditor's photo button). */
function stripExtension(filename: string): string {
  const idx = filename.lastIndexOf('.')
  return idx > 0 ? filename.slice(0, idx) : filename
}

/** Reads a locally-selected image's real pixel dimensions via a throwaway <img> — Conductor has no
 *  server-side image pipeline (WebP especially cannot be probed server-side), so this is the only
 *  place a photo's width/height are ever measured. */
function measureImageDimensions(file: File): Promise<{ width: number; height: number } | null> {
  return new Promise((resolve) => {
    const objectUrl = URL.createObjectURL(file)
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

export interface PhotoPickerProps {
  projectId: string
  token: string
  open: boolean
  onOpenChange: (open: boolean) => void
  photos: CreativePhoto[]
  onSelect: (photo: CreativePhoto) => void
  /** Called after an upload or a checked/blocked change so the parent's photo list refreshes. */
  onPhotosChanged: () => void | Promise<void>
}

export function PhotoPicker({
  projectId,
  token,
  open,
  onOpenChange,
  photos,
  onSelect,
  onPhotosChanged,
}: PhotoPickerProps) {
  const canManage = useCan('creative.manage')
  const fileInputRef = useRef<HTMLInputElement>(null)
  const [uploading, setUploading] = useState(false)
  const [uploadError, setUploadError] = useState<string | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const [label, setLabel] = useState('')
  const [source, setSource] = useState('')
  const [licence, setLicence] = useState('')
  const [aiGenerated, setAiGenerated] = useState(false)
  const [busyPhotoId, setBusyPhotoId] = useState<string | null>(null)

  // `labelOverride` carries the file-name default computed at selection time — reading the `label`
  // state here instead would race the setLabel call above it (state updates are async), so the file
  // input's onChange passes the resolved value straight through rather than relying on a re-render.
  async function handleFile(file: File | undefined, labelOverride?: string) {
    if (!file) return
    setUploadError(null)
    setWarnings([])
    setUploading(true)
    try {
      const dims = await measureImageDimensions(file)
      if (!dims) throw new Error('Could not read this image — try a different file.')
      const pending = await createCreativePhoto(
        projectId,
        {
          label: (labelOverride ?? label).trim() || undefined,
          contentType: file.type as 'image/jpeg' | 'image/png' | 'image/webp',
          sizeBytes: file.size,
          width: dims.width,
          height: dims.height,
          source: source.trim() || undefined,
          licence: licence.trim() || undefined,
          aiGenerated,
        },
        token,
      )
      if (!pending.uploadUrl) throw new Error('No upload URL was issued.')
      await putToSignedUrl(pending.uploadUrl, file, () => {})
      const confirmed = await confirmCreativePhoto(projectId, pending.id, file.size, token)
      setWarnings(confirmed.warnings ?? [])
      await onPhotosChanged()
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

  async function toggleFlag(photo: CreativePhoto, field: 'checked' | 'blocked') {
    setBusyPhotoId(photo.id)
    try {
      await patchCreativePhoto(projectId, photo.id, { [field]: !photo[field] }, token)
      await onPhotosChanged()
    } catch (err) {
      setUploadError(apiErrorMessage(err, 'Could not update the photo'))
    } finally {
      setBusyPhotoId(null)
    }
  }

  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Choose a photo" description="Pick from the library or upload a new one.">
      <div className="space-y-4">
        {canManage && (
          <div className="space-y-2 rounded-md border border-border p-3">
            <div>
              <Label htmlFor="photo-label" className="text-xs">Label</Label>
              <Input
                id="photo-label"
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                placeholder="Defaults to the file name"
              />
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <Label htmlFor="photo-source" className="text-xs">Source</Label>
                <Input id="photo-source" value={source} onChange={(e) => setSource(e.target.value)} placeholder="Unsplash" />
              </div>
              <div>
                <Label htmlFor="photo-licence" className="text-xs">Licence</Label>
                <Input id="photo-licence" value={licence} onChange={(e) => setLicence(e.target.value)} placeholder="CC0" />
              </div>
            </div>
            <Checkbox
              id="photo-ai-generated"
              checked={aiGenerated}
              onCheckedChange={setAiGenerated}
              label="This image is AI-generated"
            />
            <input
              ref={fileInputRef}
              type="file"
              accept={ALLOWED_PHOTO_TYPES.join(',')}
              className="sr-only"
              aria-label="Photo file"
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
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={uploading}
              onClick={() => fileInputRef.current?.click()}
            >
              <UploadCloudIcon className="mr-1.5 h-3.5 w-3.5" aria-hidden />
              {uploading ? 'Uploading…' : 'Upload a photo'}
            </Button>
            {uploadError && <Alert variant="destructive">{uploadError}</Alert>}
            {warnings.map((w) => (
              <Alert key={w} variant="warning">{w}</Alert>
            ))}
          </div>
        )}

        <div data-testid="photo-picker-grid" className="grid grid-cols-3 gap-2">
          {photos.map((photo) => (
            <div key={photo.id} className="group relative overflow-hidden rounded-md border border-border">
              <button
                type="button"
                onClick={() => onSelect(photo)}
                disabled={photo.blocked}
                className="block aspect-square w-full bg-surface-3 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {photo.url ? (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img src={photo.url} alt={photo.label || 'Creative photo'} className="h-full w-full object-cover" />
                ) : (
                  <span className="flex h-full items-center justify-center text-muted-foreground">
                    <ImageOffIcon className="h-4 w-4" aria-hidden />
                  </span>
                )}
              </button>
              {canManage && (
                <div className="flex items-center justify-between gap-1 border-t border-border bg-surface px-1.5 py-1 text-[11px]">
                  <button
                    type="button"
                    disabled={busyPhotoId === photo.id}
                    onClick={() => void toggleFlag(photo, 'checked')}
                    className={cn(
                      'inline-flex items-center gap-1 rounded px-1',
                      photo.checked ? 'text-status-approved' : 'text-muted-foreground',
                    )}
                    title={photo.checked ? 'Checked' : 'Mark checked'}
                  >
                    <CheckIcon className="h-3 w-3" aria-hidden />
                    {photo.checked ? 'Checked' : 'Check'}
                  </button>
                  <button
                    type="button"
                    disabled={busyPhotoId === photo.id}
                    onClick={() => void toggleFlag(photo, 'blocked')}
                    className={cn('rounded px-1', photo.blocked ? 'text-status-failed' : 'text-muted-foreground')}
                    title={photo.blocked ? 'Blocked — unblock' : 'Block this photo'}
                  >
                    {photo.blocked ? 'Blocked' : 'Block'}
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      </div>
    </Modal>
  )
}
