'use client'

// Thumbnail-card grid of a publishing Workflow's Work Items (Posts). Cards are links to the detail page
// with a checkbox for selection (shared with the list through useWorkItemListState). Media is not in the
// list payload yet, so the thumbnail is a placeholder until it is.

import Link from 'next/link'
import { Checkbox } from '@/components/ui/checkbox'
import { StatusBadge } from '@/components/ui/status-badge'
import { cn } from '@/lib/utils'
import { statusMeta, workItemDetailPath } from '@/lib/workflows'
import { DestinationMarks, PostThumb, scheduleLabel } from '@/components/workitems/postDisplay'
import type { IssueWithReviewers } from '@/components/workitems/listTypes'
import type { WorkflowView } from '@/types/workItem'

export function WorkItemGridView({
  issues,
  projectId,
  detailArea,
  noun,
  workflowView,
  canEdit,
  selectedIds,
  onToggleSelect,
}: {
  issues: IssueWithReviewers[]
  projectId: string
  detailArea: string
  noun: string
  workflowView: WorkflowView | undefined
  canEdit: boolean
  selectedIds: Set<string>
  onToggleSelect: (id: string) => void
}) {
  return (
    <ul data-testid="work-item-grid" className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
      {issues.map((issue) => {
        const isSelected = selectedIds.has(issue.id)
        const meta = statusMeta(workflowView, issue.status)
        const scheduled = scheduleLabel(issue)
        return (
          <li
            key={issue.id}
            data-testid={`work-item-card-${issue.id}`}
            data-selected={isSelected || undefined}
            className={cn(
              'group relative overflow-hidden rounded-lg border bg-surface transition-colors hover:border-border-strong',
              isSelected ? 'border-primary ring-2 ring-primary' : 'border-border',
            )}
          >
            <Link
              prefetch={false}
              href={workItemDetailPath(projectId, detailArea, noun, issue.displayId ?? '')}
              className="block focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            >
              <PostThumb src={issue.thumbnailUrl} className="aspect-square w-full rounded-none border-0 border-b [&_svg]:h-6 [&_svg]:w-6" />
              <div className="space-y-1.5 p-2.5">
                <p className="truncate text-[13px] font-medium text-foreground" title={issue.title}>
                  {issue.title}
                </p>
                <div className="flex items-center gap-2">
                  <StatusBadge status={issue.status} category={meta.category} label={meta.label} />
                  <DestinationMarks issue={issue} className="ml-auto" />
                </div>
                <div className="flex items-center gap-2 text-[11.5px] text-muted-foreground">
                  {issue.displayId && <span className="font-mono">{issue.displayId}</span>}
                  {issue.creative && (
                    <span className="font-mono" title={issue.creative.name ?? undefined}>
                      Creative {issue.creative.displayId}
                    </span>
                  )}
                  {scheduled && <span className="ml-auto truncate">{scheduled}</span>}
                </div>
              </div>
            </Link>
            {canEdit && (
              <Checkbox
                checked={isSelected}
                onCheckedChange={() => onToggleSelect(issue.id)}
                label={<span className="sr-only">Select {issue.title}</span>}
                className={cn(
                  'absolute left-1.5 top-1.5 transition-opacity focus-within:opacity-100 group-hover:opacity-100',
                  selectedIds.size === 0 && !isSelected && 'opacity-0',
                )}
              />
            )}
          </li>
        )
      })}
    </ul>
  )
}
