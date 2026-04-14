'use client'

import { useEffect, useRef, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import Link from 'next/link'
import { useAuth } from '@/contexts/AuthContext'
import { useProject } from '@/contexts/ProjectContext'
import { apiGet, apiPost, apiPatch, apiDelete } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import type { MemberRole } from '@/types'

export const dynamic = 'force-dynamic'

interface IssueAssignee {
  userId: string
  name: string
  avatarUrl?: string | null
}

interface Issue {
  id: string
  title: string
  type: string
  status: string
  updatedAt: string
  unresolvedCommentCount?: number
  displayId?: string
  sequenceNumber?: number
  assignee?: IssueAssignee | null
}

interface IssueReviewer {
  userId: string
  name: string
  avatarUrl?: string
  reviewVerdict?: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
}

interface IssueWithReviewers extends Issue {
  reviewers?: IssueReviewer[]
}

interface Member {
  userId: string
  name: string
  email: string
  avatarUrl?: string | null
  role: MemberRole
}

type StatusVariant =
  | 'status-draft'
  | 'status-review'
  | 'status-approved'
  | 'status-progress'
  | 'status-code-review'
  | 'status-done'
  | 'status-closed'

const STATUS_VARIANTS: Record<string, StatusVariant> = {
  DRAFT: 'status-draft',
  IN_REVIEW: 'status-review',
  READY_FOR_DEVELOPMENT: 'status-approved',
  IN_PROGRESS: 'status-progress',
  CODE_REVIEW: 'status-code-review',
  DONE: 'status-done',
  CLOSED: 'status-closed',
}

const STATUS_LABELS: Record<string, string> = {
  DRAFT: 'Draft',
  IN_REVIEW: 'In Review',
  READY_FOR_DEVELOPMENT: 'Ready for Development',
  IN_PROGRESS: 'In Progress',
  CODE_REVIEW: 'Code Review',
  DONE: 'Done',
  CLOSED: 'Closed',
}

const TYPE_OPTIONS = ['All', 'PRD', 'RFC', 'BUG', 'TASK'] as const
const STATUS_OPTIONS = [
  'All',
  'DRAFT',
  'IN_REVIEW',
  'READY_FOR_DEVELOPMENT',
  'IN_PROGRESS',
  'CODE_REVIEW',
  'DONE',
  'CLOSED',
] as const

const VERDICT_ICONS: Record<string, string> = {
  APPROVED: '✅',
  CHANGES_REQUESTED: '🔄',
  COMMENTED: '💬',
}

function verdictIcon(verdict?: string): string {
  if (!verdict) return '⏳'
  return VERDICT_ICONS[verdict] ?? '⏳'
}

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

function UserAvatar({ name, avatarUrl, size = 6 }: { name: string; avatarUrl?: string | null; size?: number }) {
  const cls = `w-${size} h-${size} rounded-full`
  if (avatarUrl) {
    // eslint-disable-next-line @next/next/no-img-element
    return <img src={avatarUrl} alt={name} className={`${cls} border border-border object-cover`} title={name} />
  }
  return (
    <div className={`${cls} bg-muted flex items-center justify-center text-xs font-medium text-muted-foreground border border-border`} title={name}>
      {name.charAt(0).toUpperCase()}
    </div>
  )
}

// Dropdown for assigning a member to an issue
function AssigneeCell({
  issueId,
  projectId,
  assignee,
  members,
  token,
  onChanged,
}: {
  issueId: string
  projectId: string
  assignee?: IssueAssignee | null
  members: Member[]
  token: string
  onChanged: (assignee: IssueAssignee | null) => void
}) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [open])

  async function handleSelect(member: Member | null) {
    setSaving(true)
    try {
      await apiPatch(
        `/api/v1/projects/${projectId}/issues/${issueId}`,
        { assigneeId: member ? member.userId : '' },
        token
      )
      onChanged(member ? { userId: member.userId, name: member.name, avatarUrl: member.avatarUrl } : null)
    } catch {
      // silently ignore
    } finally {
      setSaving(false)
      setOpen(false)
    }
  }

  return (
    <div ref={ref} className="relative inline-block">
      <button
        disabled={saving}
        onClick={(e) => { e.stopPropagation(); setOpen((o) => !o) }}
        className="flex items-center gap-1.5 hover:opacity-80 transition-opacity focus:outline-none disabled:opacity-50"
      >
        {assignee ? (
          <>
            <UserAvatar name={assignee.name} avatarUrl={assignee.avatarUrl} size={5} />
            <span className="text-xs text-foreground max-w-[80px] truncate hidden lg:block">{assignee.name}</span>
          </>
        ) : (
          <span className="text-xs text-muted-foreground">—</span>
        )}
      </button>
      {open && (
        <div className="absolute z-50 top-full left-0 mt-1 w-48 bg-popover border border-border rounded-md shadow-lg py-1">
          {assignee && (
            <button
              onClick={(e) => { e.stopPropagation(); handleSelect(null) }}
              className="w-full text-left px-3 py-1.5 text-xs text-destructive hover:bg-muted transition-colors"
            >
              Unassign
            </button>
          )}
          {members.map((m) => (
            <button
              key={m.userId}
              onClick={(e) => { e.stopPropagation(); handleSelect(m) }}
              className={`w-full text-left px-3 py-1.5 text-xs flex items-center gap-2 hover:bg-muted transition-colors ${assignee?.userId === m.userId ? 'font-semibold text-primary' : 'text-foreground'}`}
            >
              <UserAvatar name={m.name} avatarUrl={m.avatarUrl} size={4} />
              <span className="truncate">{m.name}</span>
              {m.role === 'REVIEWER' && <span className="text-muted-foreground ml-auto shrink-0">reviewer</span>}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

// Popover for managing issue reviewers inline
function ReviewerCell({
  issueId,
  projectId,
  reviewers,
  members,
  token,
  onChanged,
}: {
  issueId: string
  projectId: string
  reviewers: IssueReviewer[]
  members: Member[]
  token: string
  onChanged: (reviewers: IssueReviewer[]) => void
}) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState<string | null>(null)
  const ref = useRef<HTMLDivElement>(null)

  const reviewerMembers = members.filter((m) => m.role === 'REVIEWER')
  const assignedIds = new Set(reviewers.map((r) => r.userId))

  useEffect(() => {
    if (!open) return
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', handleClick)
    return () => document.removeEventListener('mousedown', handleClick)
  }, [open])

  async function toggleReviewer(member: Member) {
    setSaving(member.userId)
    try {
      if (assignedIds.has(member.userId)) {
        await apiDelete(
          `/api/v1/projects/${projectId}/issues/${issueId}/reviewers/${member.userId}`,
          token
        )
        onChanged(reviewers.filter((r) => r.userId !== member.userId))
      } else {
        await apiPost(
          `/api/v1/projects/${projectId}/issues/${issueId}/reviewers`,
          { userId: member.userId },
          token
        )
        onChanged([...reviewers, { userId: member.userId, name: member.name, avatarUrl: member.avatarUrl ?? undefined }])
      }
    } catch {
      // silently ignore
    } finally {
      setSaving(null)
    }
  }

  return (
    <div ref={ref} className="relative inline-block">
      <button
        onClick={(e) => { e.stopPropagation(); setOpen((o) => !o) }}
        className="flex items-center gap-1 hover:opacity-80 transition-opacity focus:outline-none"
      >
        {reviewers.length === 0 ? (
          <span className="text-xs text-muted-foreground">—</span>
        ) : (
          <div className="flex items-center gap-0.5">
            {reviewers.map((r) => (
              <div key={r.userId} className="flex items-center gap-0.5">
                <UserAvatar name={r.name} avatarUrl={r.avatarUrl} size={5} />
                <span className="text-xs">{verdictIcon(r.reviewVerdict)}</span>
              </div>
            ))}
          </div>
        )}
      </button>
      {open && (
        <div className="absolute z-50 top-full left-0 mt-1 w-52 bg-popover border border-border rounded-md shadow-lg py-1">
          <p className="px-3 py-1 text-xs text-muted-foreground font-medium border-b border-border mb-1">Reviewers</p>
          {reviewerMembers.length === 0 ? (
            <p className="px-3 py-2 text-xs text-muted-foreground">No reviewer-role members</p>
          ) : (
            reviewerMembers.map((m) => {
              const assigned = assignedIds.has(m.userId)
              const isSaving = saving === m.userId
              return (
                <button
                  key={m.userId}
                  disabled={isSaving}
                  onClick={(e) => { e.stopPropagation(); toggleReviewer(m) }}
                  className="w-full text-left px-3 py-1.5 text-xs flex items-center gap-2 hover:bg-muted transition-colors disabled:opacity-50"
                >
                  <span className="text-base leading-none">{assigned ? '☑' : '☐'}</span>
                  <UserAvatar name={m.name} avatarUrl={m.avatarUrl} size={4} />
                  <span className="truncate text-foreground">{m.name}</span>
                </button>
              )
            })
          )}
        </div>
      )}
    </div>
  )
}

export default function IssuesListPage() {
  const params = useParams<{ projectId: string }>()
  const { projectId } = params
  const { accessToken, user } = useAuth()
  const { projects, setActiveProject } = useProject()
  const router = useRouter()

  useEffect(() => {
    const project = projects.find((p) => p.id === projectId)
    if (project) setActiveProject(project)
  }, [projectId, projects])

  const [issues, setIssues] = useState<IssueWithReviewers[]>([])
  const [members, setMembers] = useState<Member[]>([])
  const [userRole, setUserRole] = useState<MemberRole>('REVIEWER')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<string>('All')
  const [statusFilter, setStatusFilter] = useState<string>('All')

  useEffect(() => {
    if (!accessToken) return

    async function fetchAll() {
      try {
        const [issueData, memberData] = await Promise.all([
          apiGet<IssueWithReviewers[]>(`/api/v1/projects/${projectId}/issues`, accessToken!),
          apiGet<Member[]>(`/api/v1/projects/${projectId}/members`, accessToken!),
        ])
        setIssues(issueData)
        setMembers(memberData)
        const currentMember = memberData.find((m) => m.userId === user?.id)
        if (currentMember) setUserRole(currentMember.role)

        // Fetch reviewers for all issues in parallel
        const reviewerResults = await Promise.allSettled(
          issueData.map((issue) =>
            apiGet<IssueReviewer[]>(
              `/api/v1/projects/${projectId}/issues/${issue.id}/reviewers`,
              accessToken!
            )
          )
        )
        setIssues(issueData.map((issue, i) => ({
          ...issue,
          reviewers: reviewerResults[i].status === 'fulfilled' ? reviewerResults[i].value : [],
        })))
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Failed to load issues')
      } finally {
        setLoading(false)
      }
    }

    fetchAll()
  }, [accessToken, projectId, user?.id])

  function updateIssueStatus(issueId: string, newStatus: string) {
    setIssues((prev) => prev.map((i) => i.id === issueId ? { ...i, status: newStatus } : i))
  }

  function updateIssueAssignee(issueId: string, assignee: IssueAssignee | null) {
    setIssues((prev) => prev.map((i) => i.id === issueId ? { ...i, assignee } : i))
  }

  function updateIssueReviewers(issueId: string, reviewers: IssueReviewer[]) {
    setIssues((prev) => prev.map((i) => i.id === issueId ? { ...i, reviewers } : i))
  }

  const filteredIssues = issues.filter((issue) => {
    if (typeFilter !== 'All' && issue.type !== typeFilter) return false
    if (statusFilter !== 'All' && issue.status !== statusFilter) return false
    return true
  })

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64 text-muted-foreground">
        Loading issues...
      </div>
    )
  }

  if (error) {
    return (
      <div className="flex items-center justify-center h-64 text-destructive">Error: {error}</div>
    )
  }

  const canEdit = userRole !== 'REVIEWER'

  return (
    <div className="p-4 sm:p-6">
      <h1 className="text-xl font-semibold text-foreground mb-4">Issues</h1>

      {/* Filters */}
      <div className="flex flex-wrap gap-3 mb-4">
        <div className="flex items-center gap-2">
          <label htmlFor="type-filter" className="text-sm text-muted-foreground">Type:</label>
          <select
            id="type-filter"
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            className="border border-border bg-background text-foreground rounded-md px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {TYPE_OPTIONS.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>

        <div className="flex items-center gap-2">
          <label htmlFor="status-filter" className="text-sm text-muted-foreground">Status:</label>
          <select
            id="status-filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="border border-border bg-background text-foreground rounded-md px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            {STATUS_OPTIONS.map((s) => (
              <option key={s} value={s}>{s === 'All' ? 'All' : (STATUS_LABELS[s] ?? s.replace(/_/g, ' '))}</option>
            ))}
          </select>
        </div>
      </div>

      {filteredIssues.length === 0 ? (
        <div className="flex items-center justify-center h-48 text-muted-foreground border border-dashed border-border rounded-lg">
          No issues yet
        </div>
      ) : (
        <>
          {/* Mobile card list */}
          <div className="md:hidden space-y-2">
            {filteredIssues.map((issue) => (
              <Link
                key={issue.id}
                href={`/app/projects/${projectId}/issues/${issue.id}`}
                className="block bg-card border border-border rounded-lg p-4 hover:border-border-strong transition-colors"
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <div className="flex flex-col gap-0.5">
                    {issue.displayId && (
                      <span className="font-mono text-xs text-muted-foreground">{issue.displayId}</span>
                    )}
                    <span className="font-medium text-foreground text-sm leading-snug">{issue.title}</span>
                  </div>
                  <Badge variant={STATUS_VARIANTS[issue.status] ?? 'status-draft'} className="shrink-0">
                    {STATUS_LABELS[issue.status] ?? issue.status.replace(/_/g, ' ')}
                  </Badge>
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge variant="outline" className="text-xs">{issue.type}</Badge>
                  {issue.assignee && (
                    <div className="flex items-center gap-1">
                      <UserAvatar name={issue.assignee.name} avatarUrl={issue.assignee.avatarUrl} size={4} />
                      <span className="text-xs text-muted-foreground">{issue.assignee.name}</span>
                    </div>
                  )}
                  <div className="flex items-center gap-1 ml-auto">
                    {(issue.reviewers ?? []).map((r) => (
                      <div key={r.userId} className="flex items-center gap-0.5">
                        <UserAvatar name={r.name} avatarUrl={r.avatarUrl} size={4} />
                        <span className="text-xs">{verdictIcon(r.reviewVerdict)}</span>
                      </div>
                    ))}
                    {issue.unresolvedCommentCount != null && issue.unresolvedCommentCount > 0 && (
                      <span className="inline-flex items-center gap-0.5 text-xs text-muted-foreground">
                        💬 {issue.unresolvedCommentCount}
                      </span>
                    )}
                  </div>
                  <span className="text-foreground-subtle text-xs w-full">{formatDate(issue.updatedAt)}</span>
                </div>
              </Link>
            ))}
          </div>

          {/* Desktop table */}
          <div className="hidden md:block border border-border rounded-lg overflow-hidden">
            <table className="w-full text-sm">
              <thead className="bg-muted border-b border-border">
                <tr>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">ID</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">Title</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">Type</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">Status</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">Assignee</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">Reviewers</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">Comments</th>
                  <th className="text-left px-4 py-3 text-xs font-semibold text-muted-foreground uppercase tracking-wide">Last Updated</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {filteredIssues.map((issue) => (
                  <tr
                    key={issue.id}
                    onClick={() => router.push(`/app/projects/${projectId}/issues/${issue.id}`)}
                    className="hover:bg-muted/50 transition-colors cursor-pointer"
                  >
                    <td className="px-4 py-3 whitespace-nowrap">
                      {issue.displayId && (
                        <span className="font-mono text-xs text-muted-foreground">{issue.displayId}</span>
                      )}
                    </td>
                    <td className="px-4 py-3 font-medium text-foreground max-w-xs">
                      <span className="line-clamp-2">{issue.title}</span>
                    </td>
                    <td className="px-4 py-3">
                      <Badge variant="outline">{issue.type}</Badge>
                    </td>
                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                      {accessToken && (
                        <StatusDropdown
                          projectId={projectId}
                          issueId={issue.id}
                          currentStatus={issue.status}
                          userRole={userRole}
                          token={accessToken}
                          onStatusChanged={(s) => updateIssueStatus(issue.id, s)}
                        />
                      )}
                    </td>
                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                      {canEdit && accessToken ? (
                        <AssigneeCell
                          issueId={issue.id}
                          projectId={projectId}
                          assignee={issue.assignee}
                          members={members}
                          token={accessToken}
                          onChanged={(a) => updateIssueAssignee(issue.id, a)}
                        />
                      ) : issue.assignee ? (
                        <div className="flex items-center gap-1.5">
                          <UserAvatar name={issue.assignee.name} avatarUrl={issue.assignee.avatarUrl} size={5} />
                          <span className="text-xs text-foreground hidden lg:block">{issue.assignee.name}</span>
                        </div>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                      {accessToken && (
                        <ReviewerCell
                          issueId={issue.id}
                          projectId={projectId}
                          reviewers={issue.reviewers ?? []}
                          members={members}
                          token={accessToken}
                          onChanged={(r) => updateIssueReviewers(issue.id, r)}
                        />
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {issue.unresolvedCommentCount != null && issue.unresolvedCommentCount > 0 ? (
                        <span className="inline-flex items-center gap-0.5 text-xs text-muted-foreground">
                          💬 {issue.unresolvedCommentCount}
                        </span>
                      ) : (
                        <span className="text-xs text-muted-foreground">—</span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-muted-foreground whitespace-nowrap">{formatDate(issue.updatedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </div>
  )
}
