'use client'

import { statusHue } from '@/lib/workflows'
import { statusHueClasses } from '@/components/ui/status-badge'
import { cn } from '@/lib/utils'
import { WorkItemRow } from '@/components/workitems/WorkItemRow'
import type { IssueAssignee, IssueReviewer, Member, WorkItemGroupData } from '@/components/workitems/listTypes'
import type { MemberRole } from '@/types'

/** One status group: a sticky `bg-muted` header (dot + label + count) followed by its rows. */
export function WorkItemGroup({
  group,
  projectId,
  slug,
  detailArea,
  noun,
  userRole,
  accessToken,
  members,
  canEdit,
  selectedIds,
  tabStopId,
  roundedTop,
  roundedBottom,
  onStatusChanged,
  onAssigneeChanged,
  onReviewersChanged,
  onRowFocus,
  registerStatusTriggerRef,
  registerAssigneeTriggerRef,
  registerRowLinkRef,
}: {
  group: WorkItemGroupData
  projectId: string
  slug: string
  detailArea: string
  noun: string
  userRole: MemberRole
  accessToken: string
  members: Member[]
  canEdit: boolean
  selectedIds: Set<string>
  /** The row currently holding the roving tab stop (defaults to the first row — see useWorkItemListState). */
  tabStopId: string | null
  /** True for the first rendered group — its header gets the list container's top corner radius. */
  roundedTop?: boolean
  /** True for the last rendered group — its last row gets the list container's bottom corner radius. */
  roundedBottom?: boolean
  onStatusChanged: (id: string, status: string) => void
  onAssigneeChanged: (id: string, assignee: IssueAssignee | null) => void
  onReviewersChanged: (id: string, reviewers: IssueReviewer[]) => void
  onRowFocus: (id: string) => void
  registerStatusTriggerRef: (id: string) => (el: HTMLButtonElement | null) => void
  registerAssigneeTriggerRef: (id: string) => (el: HTMLButtonElement | null) => void
  registerRowLinkRef: (id: string) => (el: HTMLAnchorElement | null) => void
}) {
  const dotClass = statusHueClasses(statusHue(group.statusId, group.category)).dot

  return (
    <div data-testid={`group-${group.statusId}`}>
      <div
        className={cn(
          'sticky top-0 z-[1] flex items-center gap-2 bg-muted px-3 py-1.5 border-b border-border',
          roundedTop && 'rounded-t-lg'
        )}
      >
        <span className={`h-1.5 w-1.5 rounded-full shrink-0 ${dotClass}`} />
        <span className="text-[11.5px] font-semibold uppercase tracking-wide text-foreground">{group.label}</span>
        <span data-testid={`group-count-${group.statusId}`} className="text-[11.5px] text-muted-foreground">
          {group.items.length}
        </span>
      </div>
      <div>
        {group.items.map((issue, i) => (
          <WorkItemRow
            key={issue.id}
            issue={issue}
            projectId={projectId}
            slug={slug}
            detailArea={detailArea}
            noun={noun}
            userRole={userRole}
            accessToken={accessToken}
            members={members}
            canEdit={canEdit}
            selected={selectedIds.has(issue.id)}
            focused={tabStopId === issue.id}
            onStatusChanged={onStatusChanged}
            onAssigneeChanged={onAssigneeChanged}
            onReviewersChanged={onReviewersChanged}
            onRowFocus={onRowFocus}
            statusTriggerRef={registerStatusTriggerRef(issue.id)}
            assigneeTriggerRef={registerAssigneeTriggerRef(issue.id)}
            rowLinkRef={registerRowLinkRef(issue.id)}
            className={roundedBottom && i === group.items.length - 1 ? 'rounded-b-lg' : undefined}
          />
        ))}
      </div>
    </div>
  )
}
