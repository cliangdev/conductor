'use client'

// What the Post says and shows, in one card: the caption that goes out, then the media by destination. The caption editor and the upload flow are the existing ones, rendered without their own
// cards; a lock or a "sends it back for review" note is one quiet line, not a banner.

import { Card } from '@/components/ui/card'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { WorkItemDescriptionCard } from '@/components/workitems/WorkItemDescriptionCard'
import type { DestinationsState } from '@/components/marketing/destinations/model'
import type { WorkflowView } from '@/types/workItem'
import { PostMediaSection } from './PostMediaSection'

export interface PostContentSectionProps {
  projectId: string
  workItemId: string
  token: string
  status: string
  workflowView?: WorkflowView
  description?: string | null
  assets: MediaAsset[]
  /** The Post's destinations, so media can be shown per destination. */
  destinations?: DestinationsState
  canEdit: boolean
  onCaptionSaved: (description: string) => void
  onAssetsChanged: () => void | Promise<void>
}

export function PostContentSection({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  description,
  assets,
  destinations,
  canEdit,
  onCaptionSaved,
  onAssetsChanged,
}: PostContentSectionProps) {
  return (
    <Card data-testid="post-content">
      <WorkItemDescriptionCard
        variant="section"
        title="Content"
        projectId={projectId}
        workItemId={workItemId}
        token={token}
        description={description}
        status={status}
        workflowView={workflowView}
        isCaption
        canEdit={canEdit}
        onSaved={onCaptionSaved}
      />
      <div className="border-t border-border px-4 py-3">
        <PostMediaSection
          projectId={projectId}
          workItemId={workItemId}
          token={token}
          status={status}
          workflowView={workflowView}
          caption={description ?? null}
          assets={assets}
          canEdit={canEdit}
          destinations={destinations}
          onAssetsChanged={onAssetsChanged}
        />
      </div>
    </Card>
  )
}
