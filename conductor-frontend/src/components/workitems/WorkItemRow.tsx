'use client'

import Link from 'next/link'
import { Badge } from '@/components/ui/badge'
import { ExternalLinkCell } from '@/components/workitems/ExternalLinkCell'
import { CommentCount } from '@/components/ui/comment-count'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import { AssigneeCell } from '@/components/workitems/AssigneeCell'
import { ReviewerCell } from '@/components/workitems/ReviewerCell'
import { EmptyAvatarSlot, UserAvatar } from '@/components/workitems/UserAvatar'
import { timeAgo } from '@/lib/format'
import { workItemDetailPath } from '@/lib/workflows'
import { cn } from '@/lib/utils'
import type { IssueAssignee, IssueReviewer, IssueWithReviewers, Member } from '@/components/workitems/listTypes'
import type { MemberRole } from '@/types'

/**
 * One 38px row: status ring · mono id · title · type tag · (comment count · reviewers · assignee ·
 * time) right-aligned. Only the id+title span is a real `<a>` (native Enter activation, ⌘/Ctrl-click,
 * middle-click "open in new tab" all work for free, no JS needed) — the mutation controls sit outside
 * it as plain siblings rather than nested inside the anchor, so there's no dead zone where clicking a
 * non-activatable child (the ring, an avatar, empty meta space) falls through to the anchor's default
 * navigation. J/K keyboard nav moves real DOM focus onto the link via roving tabIndex (see
 * useWorkItemListState) — `focused` here just mirrors that for the tab stop and the visual treatment.
 */
export function WorkItemRow({
  issue,
  projectId,
  slug,
  detailArea,
  noun,
  userRole,
  accessToken,
  members,
  canEdit,
  selected,
  focused,
  onStatusChanged,
  onAssigneeChanged,
  onReviewersChanged,
  onRowFocus,
  statusTriggerRef,
  assigneeTriggerRef,
  rowLinkRef,
  className,
}: {
  issue: IssueWithReviewers
  projectId: string
  slug: string
  detailArea: string
  noun: string
  userRole: MemberRole
  accessToken: string
  members: Member[]
  canEdit: boolean
  selected: boolean
  focused: boolean
  onStatusChanged: (id: string, status: string) => void
  onAssigneeChanged: (id: string, assignee: IssueAssignee | null) => void
  onReviewersChanged: (id: string, reviewers: IssueReviewer[]) => void
  onRowFocus: (id: string) => void
  statusTriggerRef: (el: HTMLButtonElement | null) => void
  assigneeTriggerRef: (el: HTMLButtonElement | null) => void
  rowLinkRef: (el: HTMLAnchorElement | null) => void
  /** e.g. `rounded-b-lg` on the list's last row — see WorkItemGroup's roundedBottom. */
  className?: string
}) {
  const highlighted = selected || focused

  return (
    <div
      id={`wi-row-${issue.id}`}
      data-selected={selected || undefined}
      data-focused={focused || undefined}
      className={cn(
        'flex items-center gap-3 h-[38px] px-3 border-b border-border last:border-b-0 transition-colors min-w-0',
        'hover:bg-muted',
        highlighted && 'bg-accent-soft shadow-[inset_2px_0_0_0_hsl(var(--primary))]',
        className
      )}
    >
      <span className="shrink-0 flex items-center">
        <StatusDropdown
          projectId={projectId}
          issueId={issue.id}
          currentStatus={issue.status}
          userRole={userRole}
          token={accessToken}
          workflowSlug={slug}
          trigger="ring"
          triggerRef={statusTriggerRef}
          onStatusChanged={(s) => onStatusChanged(issue.id, s)}
        />
      </span>

      <Link
        ref={rowLinkRef}
        href={workItemDetailPath(projectId, detailArea, noun, issue.displayId ?? '')}
        data-row-id={issue.id}
        tabIndex={focused ? 0 : -1}
        onFocus={() => onRowFocus(issue.id)}
        className="flex items-center gap-3 min-w-0 flex-1 rounded-sm focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
      >
        {issue.displayId && (
          <span className="font-mono text-xs text-muted-foreground w-16 shrink-0 truncate">{issue.displayId}</span>
        )}
        <span className="text-[13px] font-medium text-foreground truncate min-w-0">{issue.title}</span>
      </Link>

      <Badge variant="outline" className="text-xs shrink-0 hidden sm:inline-flex">
        {issue.type}
      </Badge>

      {/* Tags read at a glance next to the type, which is the other thing that says what this is. */}
      {(issue.tags?.length ?? 0) > 0 && (
        <span className="hidden shrink-0 items-center gap-1 md:inline-flex">
          {issue.tags!.slice(0, 3).map((tag) => (
            <span key={tag} className="rounded-full bg-muted px-1.5 py-0.5 text-[11px] text-muted-foreground">
              {tag}
            </span>
          ))}
          {issue.tags!.length > 3 && (
            <span className="text-[11px] text-foreground-subtle" title={issue.tags!.join(', ')}>
              +{issue.tags!.length - 3}
            </span>
          )}
        </span>
      )}

      <div className="flex items-center gap-3 ml-auto shrink-0">
        {/* Where it ended up outside Conductor. Sits outside the row anchor so a click opens the live
            post rather than the Work Item — the whole reason for putting it here is to save that trip. */}
        <ExternalLinkCell links={issue.externalLinks} />

        <CommentCount count={issue.unresolvedCommentCount} className="text-muted-foreground" />

        {accessToken && (
          <ReviewerCell
            issueId={issue.id}
            projectId={projectId}
            reviewers={issue.reviewers ?? []}
            members={members}
            token={accessToken}
            onChanged={(r) => onReviewersChanged(issue.id, r)}
          />
        )}

        {canEdit && accessToken ? (
          <AssigneeCell
            ref={assigneeTriggerRef}
            issueId={issue.id}
            projectId={projectId}
            assignee={issue.assignee}
            members={members}
            token={accessToken}
            onChanged={(a) => onAssigneeChanged(issue.id, a)}
          />
        ) : issue.assignee ? (
          <UserAvatar name={issue.assignee.name} avatarUrl={issue.assignee.avatarUrl} size={5} />
        ) : (
          <EmptyAvatarSlot size={5} />
        )}

        <span className="text-foreground-subtle text-[11.5px] w-14 text-right shrink-0">{timeAgo(issue.updatedAt)}</span>
      </div>
    </div>
  )
}
