'use client'

import { useRouter } from 'next/navigation'
import { CommentCount } from '@/components/ui/comment-count'
import { Badge } from '@/components/ui/badge'
import { statusHueClasses } from '@/components/ui/status-badge'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import { UserAvatar } from '@/components/workitems/UserAvatar'
import {
  categoriesForView,
  humanizeId,
  pluralizeNoun,
  statusHue,
  workItemDetailPath,
} from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'
import type { MemberRole } from '@/types'

interface BoardIssueAssignee {
  userId: string
  name: string
  avatarUrl?: string | null
}

interface BoardIssue {
  id: string
  title: string
  type: string
  status: string
  displayId?: string
  assignee?: BoardIssueAssignee | null
  unresolvedCommentCount?: number
}

type BoardView = 'active' | 'done' | 'all'

export function WorkItemBoardView({
  projectId,
  slug,
  noun,
  workflowView,
  issues,
  userRole,
  accessToken,
  view,
  onStatusChanged,
}: {
  projectId: string
  slug: string
  noun: string
  workflowView: WorkflowView
  issues: BoardIssue[]
  userRole: MemberRole
  accessToken: string
  view: BoardView
  onStatusChanged: (issueId: string, newStatus: string) => void
}) {
  const router = useRouter()
  const categories = categoriesForView(view)

  // Columns = workflow statuses in the current tab's categories, preserving workflow order.
  const columns = workflowView.statuses.filter((s) => (categories as string[]).includes(s.category))

  // Group issues by status; pre-initialize all columns to empty arrays.
  const byStatus = new Map<string, BoardIssue[]>()
  for (const col of columns) byStatus.set(col.id, [])
  for (const issue of issues) {
    const list = byStatus.get(issue.status)
    if (list) list.push(issue)
  }

  const lowerNoun = pluralizeNoun(noun).toLowerCase()
  const area = workflowView.area ?? slug

  return (
    <div className="overflow-x-auto -mx-1 px-1 pb-2">
      <div className="flex gap-3 min-w-max">
        {columns.map((col) => {
          const colIssues = byStatus.get(col.id) ?? []
          const colLabel = col.label ?? humanizeId(col.id)
          const hueClasses = statusHueClasses(statusHue(col.id, col.category))

          return (
            <div key={col.id} className="w-72 shrink-0 flex flex-col">
              {/* Column header */}
              <div
                className={`flex items-center justify-between px-3 py-2 rounded-t-lg border border-b-0 border-border ${hueClasses.bg} ${hueClasses.text}`}
              >
                <span className="text-xs font-semibold uppercase tracking-wider truncate">
                  {colLabel}
                </span>
                <span className="text-xs font-medium opacity-70 ml-2 shrink-0">{colIssues.length}</span>
              </div>

              {/* Cards */}
              <div className="flex flex-col gap-2 border border-t-0 rounded-b-lg p-2 bg-muted/20 min-h-[100px]">
                {colIssues.length === 0 ? (
                  <div className="flex items-center justify-center h-14 text-xs text-muted-foreground border border-dashed border-border rounded-md">
                    No {lowerNoun}
                  </div>
                ) : (
                  colIssues.map((issue) => (
                    <div
                      key={issue.id}
                      onClick={() =>
                        router.push(workItemDetailPath(projectId, area, noun, issue.displayId ?? ''))
                      }
                      className="bg-card border border-border rounded-md p-3 cursor-pointer hover:border-ring hover:shadow-sm transition-all"
                    >
                      {issue.displayId && (
                        <span className="font-mono text-xs text-muted-foreground block mb-1">
                          {issue.displayId}
                        </span>
                      )}
                      <p className="text-sm font-medium text-foreground line-clamp-2 mb-2">
                        {issue.title}
                      </p>
                      <div
                        className="flex items-center justify-between gap-2"
                        onClick={(e) => e.stopPropagation()}
                      >
                        <Badge variant="outline" className="text-xs shrink-0">
                          {issue.type}
                        </Badge>
                        <div className="flex items-center gap-1.5 shrink-0">
                          {issue.assignee && (
                            <UserAvatar name={issue.assignee.name} avatarUrl={issue.assignee.avatarUrl} size={5} />
                          )}
                          <CommentCount
                            count={issue.unresolvedCommentCount}
                            className="text-muted-foreground"
                          />
                          <StatusDropdown
                            projectId={projectId}
                            issueId={issue.id}
                            currentStatus={issue.status}
                            userRole={userRole}
                            token={accessToken}
                            workflowSlug={slug}
                            onStatusChanged={(s) => onStatusChanged(issue.id, s)}
                          />
                        </div>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
