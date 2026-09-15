'use client'

// What the Post says and shows, in one card: the caption that goes out, then the media as a strip of
// thumbnails. The caption editor and the upload flow are the existing ones, rendered without their own
// cards; a lock or a "sends it back for review" note is one quiet line, not a banner.

import { Card } from '@/components/ui/card'
import { MediaUploadPanel, type MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { WorkItemDescriptionCard } from '@/components/workitems/WorkItemDescriptionCard'
import type { WorkflowView } from '@/types/workItem'

export interface PostContentSectionProps {
  projectId: string
  workItemId: string
  token: string
  status: string
  workflowView?: WorkflowView
  description?: string | null
  assets: MediaAsset[]
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
      {(workflowView?.assetTypes?.length ?? 0) > 0 && (
        <div className="border-t border-border px-4 py-3">
          <MediaUploadPanel
            variant="strip"
            projectId={projectId}
            workItemId={workItemId}
            token={token}
            status={status}
            workflowView={workflowView}
            assets={assets}
            onUploaded={onAssetsChanged}
          />
        </div>
      )}
    </Card>
  )
}
