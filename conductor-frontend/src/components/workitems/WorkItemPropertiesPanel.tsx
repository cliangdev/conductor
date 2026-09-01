'use client'

import { WorkItemScheduleField } from '@/components/workitems/WorkItemScheduleField'
import { useState } from 'react'
import { ChevronDown, ChevronRight, ExternalLink, Plus, XIcon } from 'lucide-react'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import { TaskProgressPanel } from '@/components/issues/TaskProgressPanel'
import { AssigneeCell } from '@/components/workitems/AssigneeCell'
import { EmptyAvatarSlot, UserAvatar } from '@/components/workitems/UserAvatar'
import { VerdictIcon } from '@/components/reviews/verdict'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import type { IssueAssignee, Member } from '@/components/workitems/listTypes'
import type { WorkItemAsset } from '@/types/workItem'
import type { DetailReview, DetailReviewer } from '@/components/workitems/detailTypes'
import type { MemberRole } from '@/types'

function PanelSection({
  label,
  action,
  children,
}: {
  label: string
  action?: React.ReactNode
  children: React.ReactNode
}) {
  return (
    <div className="px-4 py-3 border-b border-border last:border-b-0">
      <div className="flex items-center justify-between mb-2">
        <span className="text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground">
          {label}
        </span>
        {action}
      </div>
      {children}
    </div>
  )
}

function ReviewerRow({
  reviewer,
  body,
  canManage,
  onUnassign,
}: {
  reviewer: DetailReviewer
  body?: string
  canManage: boolean
  onUnassign: () => void
}) {
  const [expanded, setExpanded] = useState(false)
  return (
    <div className="py-1.5">
      <div className="flex items-center gap-2">
        <UserAvatar name={reviewer.name} avatarUrl={reviewer.avatarUrl} size={5} />
        <span className="text-sm text-foreground truncate flex-1 min-w-0">{reviewer.name}</span>
        <VerdictIcon verdict={reviewer.reviewVerdict} />
        {body && (
          <button
            onClick={() => setExpanded((v) => !v)}
            aria-label={expanded ? 'Collapse review' : 'Expand review'}
            className="text-muted-foreground hover:text-foreground transition-colors"
          >
            {expanded ? <ChevronDown className="h-3.5 w-3.5" /> : <ChevronRight className="h-3.5 w-3.5" />}
          </button>
        )}
        {canManage && (
          <button
            onClick={onUnassign}
            aria-label={`Unassign ${reviewer.name}`}
            title={`Unassign ${reviewer.name}`}
            className="text-muted-foreground hover:text-destructive transition-colors"
          >
            <XIcon className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
      {expanded && body && (
        <p className="mt-1 ml-7 text-xs text-muted-foreground whitespace-pre-wrap leading-relaxed">
          {body}
        </p>
      )}
    </div>
  )
}

export function WorkItemPropertiesPanel({
  projectId,
  issueId,
  status,
  userRole,
  token,
  workflowSlug,
  onStatusChanged,
  statusTriggerRef,
  assignee,
  members,
  onAssigneeChanged,
  assigneeTriggerRef,
  reviewActive,
  reviewers,
  reviews,
  canManage,
  assignableReviewers,
  onAssignReviewer,
  onUnassignReviewer,
  assets,
  scheduledFor,
  scheduleTimezone,
  onScheduleChanged,
}: {
  projectId: string
  issueId: string
  status: string
  userRole: MemberRole
  token: string
  workflowSlug: string
  onStatusChanged: (status: string) => void
  /** Forwarded to StatusDropdown's trigger — this panel is the one interactive status control on the
   * page (the page header shows a read-only StatusBadge instead), so the command palette's
   * "Change status" action opens this trigger. */
  statusTriggerRef?: React.Ref<HTMLButtonElement>
  assignee?: IssueAssignee | null
  members: Member[]
  onAssigneeChanged: (assignee: IssueAssignee | null) => void
  /** Forwarded to AssigneeCell's trigger button so the command palette's "Assign" action can open it. */
  assigneeTriggerRef?: React.Ref<HTMLButtonElement>
  reviewActive: boolean
  reviewers: DetailReviewer[]
  reviews: DetailReview[]
  canManage: boolean
  assignableReviewers: Member[]
  onAssignReviewer: (userId: string) => void
  onUnassignReviewer: (userId: string) => void
  assets: WorkItemAsset[]
  /** ISO instant this item is due, or null. A generic Work Item field — see WorkItemScheduleField. */
  scheduledFor?: string | null
  scheduleTimezone?: string | null
  onScheduleChanged: (scheduledFor: string | null, scheduleTimezone: string | null) => void
}) {
  // Counted from `reviews`, not from `reviewers`. The reviewers endpoint returns assignment only —
  // userId, email, name — and carries no verdict, so filtering it on reviewVerdict matched nothing and
  // the panel read "0 of N approved" however many people had actually approved. Latest verdict per
  // reviewer wins, which is how the server's own review gate reads it too.
  // Counted from `reviews`, not from `reviewers`: the reviewers endpoint returns assignment only —
  // userId, email, name — and carries no verdict, so filtering it on reviewVerdict matched nothing and
  // this read "0 of N approved" however many people had actually approved.
  //
  // And only reviews the server still counts. An approval is bound to the item it was given for, so
  // editing a Post's caption, schedule, targets or media withdraws it; showing "1 of 1 approved" beside
  // a gate that is refusing to open is worse than showing nothing. `current` is computed by the same
  // rule the gate applies, and an older server that omits it is treated as standing.
  const latest = new Map<string, DetailReview>()
  for (const review of reviews) latest.set(review.reviewerId, review)
  const approvedCount = reviewers.filter((r) => {
    const review = latest.get(r.userId)
    return review?.verdict === 'APPROVED' && review.current !== false
  }).length
  const links = assets.filter((a) => a.kind === 'link')

  return (
    <div data-testid="properties-panel" className="flex flex-col">
      <PanelSection label="Status">
        <StatusDropdown
          projectId={projectId}
          issueId={issueId}
          currentStatus={status}
          userRole={userRole}
          token={token}
          workflowSlug={workflowSlug}
          onStatusChanged={onStatusChanged}
          triggerRef={statusTriggerRef}
        />
      </PanelSection>

      <PanelSection label="Schedule">
        <WorkItemScheduleField
          projectId={projectId}
          issueId={issueId}
          token={token}
          scheduledFor={scheduledFor}
          scheduleTimezone={scheduleTimezone}
          canEdit={userRole !== 'REVIEWER'}
          onChanged={onScheduleChanged}
        />
      </PanelSection>

      <PanelSection label="Assignee">
        {userRole === 'REVIEWER' ? (
          assignee ? (
            <div className="flex items-center gap-2">
              <UserAvatar name={assignee.name} avatarUrl={assignee.avatarUrl} size={5} />
              <span className="text-sm text-foreground truncate">{assignee.name}</span>
            </div>
          ) : (
            <div className="flex items-center gap-2">
              <EmptyAvatarSlot size={5} />
              <span className="text-sm text-muted-foreground">Unassigned</span>
            </div>
          )
        ) : (
          <div className="flex items-center gap-2">
            <AssigneeCell
              ref={assigneeTriggerRef}
              issueId={issueId}
              projectId={projectId}
              assignee={assignee}
              members={members}
              token={token}
              onChanged={onAssigneeChanged}
            />
            <span className="text-sm text-foreground truncate">{assignee?.name ?? 'Unassigned'}</span>
          </div>
        )}
      </PanelSection>

      {reviewActive && (
        <PanelSection
          label="Reviewers"
          action={
            canManage ? (
              <DropdownMenu>
                <DropdownMenuTrigger asChild>
                  <button
                    aria-label="Add reviewer"
                    className="inline-flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground transition-colors"
                  >
                    <Plus className="h-3.5 w-3.5" />
                  </button>
                </DropdownMenuTrigger>
                <DropdownMenuContent align="end" className="w-56">
                  {assignableReviewers.length === 0 ? (
                    <div className="px-2 py-1.5 text-xs text-muted-foreground">
                      No reviewers available to assign
                    </div>
                  ) : (
                    assignableReviewers.map((m) => (
                      <DropdownMenuItem
                        key={m.userId}
                        onClick={() => onAssignReviewer(m.userId)}
                        className="cursor-pointer gap-2"
                      >
                        <UserAvatar name={m.name} size={5} />
                        <span className="truncate">{m.name}</span>
                      </DropdownMenuItem>
                    ))
                  )}
                </DropdownMenuContent>
              </DropdownMenu>
            ) : undefined
          }
        >
          {reviewers.length === 0 ? (
            <p className="text-xs text-muted-foreground">No reviewers assigned yet</p>
          ) : (
            <>
              <p className="text-xs text-muted-foreground mb-2">
                {approvedCount} of {reviewers.length} approved
              </p>
              <div className="divide-y divide-border">
                {reviewers.map((r) => (
                  <ReviewerRow
                    key={r.userId}
                    reviewer={r}
                    body={reviews.find((rv) => rv.reviewerId === r.userId)?.body}
                    canManage={canManage}
                    onUnassign={() => onUnassignReviewer(r.userId)}
                  />
                ))}
              </div>
            </>
          )}
        </PanelSection>
      )}

      {links.length > 0 && (
        <PanelSection label="Links">
          <div className="flex flex-col gap-1.5">
            {links.map((asset) => {
              const isPr = asset.type === 'github_pr'
              return (
                <a
                  key={asset.id}
                  href={asset.ref}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors"
                >
                  <ExternalLink className="h-3.5 w-3.5 shrink-0" />
                  <span className="truncate">{isPr ? 'View PR' : asset.label || asset.type}</span>
                </a>
              )
            })}
          </div>
        </PanelSection>
      )}

      <TaskProgressPanel issueId={issueId} projectId={projectId} />
    </div>
  )
}
