'use client'

// COND-24 T2 (originally): pick a Creative photo from the project's library, or upload a new one.
// COND-24 T6 turned this into a thin wrapper over MediaPicker (Photos/Videos/Audio) locked to the
// IMAGE tab, so every existing call site — the Creative's main photo and its sequence beat photos in
// CreativeEditor — keeps working unchanged.

import { MediaPicker } from '@/components/marketing/creatives/MediaPicker'
import type { CreativePhoto } from '@/components/marketing/creatives/types'

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

export function PhotoPicker({ projectId, token, open, onOpenChange, photos, onSelect, onPhotosChanged }: PhotoPickerProps) {
  return (
    <MediaPicker
      projectId={projectId}
      token={token}
      open={open}
      onOpenChange={onOpenChange}
      media={photos}
      onSelect={onSelect}
      onMediaChanged={onPhotosChanged}
      kind="IMAGE"
    />
  )
}
