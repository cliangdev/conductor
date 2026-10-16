'use client'

// "From media": the library picker a Post uses to reuse files that are already in Media. A thin
// wrapper over MediaChooser restricted to images and video (a Post can't carry audio); the picked
// items come back in pick order. What happens next (link them now, or after a Post is created) is the
// caller's — the Post detail strip links straight away, the compose page waits for the new Post.

import { MediaChooser, type ChooserExtraTab } from './MediaChooser'
import type { CreativePhoto } from '@/components/marketing/creatives/types'

export interface PickMediaDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  projectId: string
  token: string
  onPicked: (items: CreativePhoto[]) => void
  /** Upload tab hands raw files to the caller instead of saving them to the library. */
  onFilesChosen?: (files: File[]) => void
  /** Further tabs (the compose page adds Creatives) and which one opens first. */
  extraTabs?: ChooserExtraTab[]
  defaultTab?: string
}

export function PickMediaDialog({ open, onOpenChange, projectId, token, onPicked, onFilesChosen, extraTabs, defaultTab }: PickMediaDialogProps) {
  return (
    <MediaChooser
      open={open}
      onOpenChange={onOpenChange}
      projectId={projectId}
      token={token}
      onPicked={onPicked}
      kinds={['IMAGE', 'VIDEO']}
      onFilesChosen={onFilesChosen}
      extraTabs={extraTabs}
      defaultTab={defaultTab}
    />
  )
}
