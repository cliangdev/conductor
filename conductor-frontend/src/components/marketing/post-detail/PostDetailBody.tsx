'use client'

// The reading column of a Post: where it goes and where each destination is (the Destinations panel,
// with the schedule in its header), then what it says and shows (the Content section). Everything
// engineering-specific stays in WorkItemDetailView; this is only ever rendered for a publishing Workflow.

import { DestinationsPanel } from '@/components/marketing/destinations/DestinationsPanel'
import type { PostDestinationsState } from '@/components/marketing/destinations/usePostDestinations'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { WorkItemScheduleField } from '@/components/workitems/WorkItemScheduleField'
import { statusMeta } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'
import { PostContentSection } from './PostContentSection'

export interface PostDetailBodyProps {
  projectId: string
  workItemId: string
  token: string
  status: string
  workflowView?: WorkflowView
  userRole: 'ADMIN' | 'CREATOR' | 'REVIEWER'
  description?: string | null
  scheduledFor?: string | null
  scheduleTimezone?: string | null
  publishOnApproval?: boolean
  assets: MediaAsset[]
  destinations: PostDestinationsState
  onCaptionSaved: (description: string) => void
  onAssetsChanged: () => void | Promise<void>
  onScheduleChanged: (scheduledFor: string | null, scheduleTimezone: string | null, publishOnApproval?: boolean) => void
}

export function PostDetailBody({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  userRole,
  description,
  scheduledFor,
  scheduleTimezone,
  publishOnApproval,
  assets,
  destinations,
  onCaptionSaved,
  onAssetsChanged,
  onScheduleChanged,
}: PostDetailBodyProps) {
  const canEdit = userRole !== 'REVIEWER'
  const meta = statusMeta(workflowView, status)
  const noun = workflowView?.noun ?? 'Post'
  // "Went out" once the Post is done or anything has published; "Goes out" while it is still ahead.
  const pastTense = meta.category === 'terminal' || destinations.summary.published > 0

  return (
    <>
      <DestinationsPanel
        state={destinations}
        assets={assets}
        caption={description ?? null}
        canEdit={canEdit}
        noun={noun}
        statusLabel={meta.label}
        headerSlot={
          <WorkItemScheduleField
            layout="inline"
            pastTense={pastTense}
            projectId={projectId}
            issueId={workItemId}
            token={token}
            scheduledFor={scheduledFor}
            scheduleTimezone={scheduleTimezone}
            publishOnApproval={publishOnApproval}
            canEdit={canEdit}
            onChanged={onScheduleChanged}
          />
        }
      />
      <PostContentSection
        projectId={projectId}
        workItemId={workItemId}
        token={token}
        status={status}
        workflowView={workflowView}
        description={description}
        assets={assets}
        canEdit={canEdit}
        onCaptionSaved={onCaptionSaved}
        onAssetsChanged={onAssetsChanged}
      />
    </>
  )
}
