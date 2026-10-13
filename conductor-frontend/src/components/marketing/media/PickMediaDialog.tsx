'use client'

// "From media": the library picker a Post uses to reuse files that are already in Media. Loads the
// library when opened, shows images and video only (a Post can't carry audio) and hands the picked
// items back in click order. What happens next (link them now, or after a Post is created) is the
// caller's — the Post detail strip links straight away, the compose page waits for the new Post.

import { useCallback, useEffect, useState } from 'react'
import { MediaPicker } from '@/components/marketing/creatives/MediaPicker'
import { listCreativePhotos, type CreativePhoto } from '@/components/marketing/creatives/types'

const POST_KINDS: ('IMAGE' | 'VIDEO')[] = ['IMAGE', 'VIDEO']

export interface PickMediaDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  token: string
  onPicked: (items: CreativePhoto[]) => void
}

export function PickMediaDialog({ open, onOpenChange, projectId, token, onPicked }: PickMediaDialogProps) {
  const [media, setMedia] = useState<CreativePhoto[]>([])

  const load = useCallback(async () => {
    try {
      const rows = await listCreativePhotos(projectId, token, false)
      setMedia(rows.filter((m) => m.uploadStatus === 'UPLOADED' && !m.blocked))
    } catch {
      setMedia([])
    }
  }, [projectId, token])

  useEffect(() => {
    if (open) void load()
  }, [open, load])

  return (
    <MediaPicker
      projectId={projectId}
      token={token}
      open={open}
      onOpenChange={onOpenChange}
      media={media}
      kinds={POST_KINDS}
      multiple
      onSelectMany={(items) => {
        onPicked(items)
        onOpenChange(false)
      }}
      onMediaChanged={load}
    />
  )
}
