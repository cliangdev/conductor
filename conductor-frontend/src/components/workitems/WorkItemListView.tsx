'use client'

// COND-22: the generic Work Item list view, scoped to one Workflow slug. This is the migrated Issues
// list — title, status filter, and type filter all derive from the bound Workflow's view metadata, and
// the Work Items are fetched scoped to the slug (GET /issues?workflow=<slug>). It is mounted at the
// canonical /{projectId}/{area}/{noun} route (e.g. /engineering/issues).

import { useEffect, useState } from 'react'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { ChevronDown, Check, LayoutDashboardIcon, ListIcon, MessageSquare } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPost, apiPatch, apiDelete, apiErrorMessage } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { StatusBadge } from '@/components/ui/status-badge'
import { Skeleton } from '@/components/ui/skeleton'
import { toastError } from '@/components/ui/toast'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  DropdownMenuLabel,
} from '@/components/ui/dropdown-menu'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import { VerdictIcon } from '@/components/reviews/verdict'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, type Crumb } from '@/components/layout/PageHeader'
import {
  categoriesForView,
  fetchMembersCached,
  getMembersCacheEntry,
  humanizeId,
  pluralizeNoun,
  statusMeta,
  useWorkflowView,
  workItemDetailPath,
} from '@/lib/workflows'
import { WorkItemBoardView } from '@/components/workitems/WorkItemBoardView'
import type { MemberRole } from '@/types'

type DisplayMode = 'list' | 'board'

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
  workflow?: string
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

type View = 'active' | 'done' | 'all'

function formatDate(dateStr: string): string {
  return new Date(dateStr).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  })
}

type AvatarSize = 4 | 5 | 6

// Static lookup, not an interpolated `w-${size}` string — Tailwind can't see a template class at
// build time and would drop it.
const AVATAR_SIZE_CLASSES: Record<AvatarSize, string> = {
  4: 'w-4 h-4',
  5: 'w-5 h-5',
  6: 'w-6 h-6',
}

function UserAvatar({ name, avatarUrl, size = 6 }: { name: string; avatarUrl?: string | null; size?: AvatarSize }) {
  const cls = `${AVATAR_SIZE_CLASSES[size]} rounded-full`
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
  const [saving, setSaving] = useState(false)

  async function handleSelect(member: Member | null) {
    setSaving(true)
    try {
      await apiPatch(
        `/api/v2/projects/${projectId}/work-items/${issueId}`,
        { assigneeId: member ? member.userId : '' },
        token
      )
      onChanged(member ? { userId: member.userId, name: member.name, avatarUrl: member.avatarUrl } : null)
    } catch (err) {
      toastError(apiErrorMessage(err, 'Failed to update assignee'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button disabled={saving} className="focus:outline-none">
          <Badge variant="outline" className="cursor-pointer hover:opacity-80 transition-opacity gap-1.5 font-normal">
            {assignee ? (
              <>
                <UserAvatar name={assignee.name} avatarUrl={assignee.avatarUrl} size={4} />
                <span className="max-w-[72px] truncate">{assignee.name}</span>
              </>
            ) : (
              <span className="text-muted-foreground">Unassigned</span>
            )}
            <ChevronDown className="h-3 w-3 opacity-60" />
          </Badge>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-52">
        {assignee && (
          <>
            <DropdownMenuItem
              onClick={() => handleSelect(null)}
              className="cursor-pointer text-destructive focus:text-destructive"
            >
              Unassign
            </DropdownMenuItem>
            <DropdownMenuSeparator />
          </>
        )}
        {members.map((m) => (
          <DropdownMenuItem
            key={m.userId}
            onClick={() => handleSelect(m)}
            className="cursor-pointer gap-2"
          >
            <UserAvatar name={m.name} avatarUrl={m.avatarUrl} size={5} />
            <span className="truncate flex-1">{m.name}</span>
            {assignee?.userId === m.userId && <Check className="h-3.5 w-3.5 text-primary" />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

// Dropdown for managing issue reviewers inline
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
  const [saving, setSaving] = useState<string | null>(null)

  const reviewerMembers = members.filter((m) => m.role === 'REVIEWER')
  const assignedIds = new Set(reviewers.map((r) => r.userId))

  async function toggleReviewer(member: Member) {
    setSaving(member.userId)
    try {
      if (assignedIds.has(member.userId)) {
        await apiDelete(
          `/api/v2/projects/${projectId}/work-items/${issueId}/reviewers/${member.userId}`,
          token
        )
        onChanged(reviewers.filter((r) => r.userId !== member.userId))
      } else {
        await apiPost(
          `/api/v2/projects/${projectId}/work-items/${issueId}/reviewers`,
          { userId: member.userId },
          token
        )
        onChanged([...reviewers, { userId: member.userId, name: member.name, avatarUrl: member.avatarUrl ?? undefined }])
      }
    } catch (err) {
      toastError(
        apiErrorMessage(err, assignedIds.has(member.userId) ? 'Failed to remove reviewer' : 'Failed to add reviewer')
      )
    } finally {
      setSaving(null)
    }
  }

  const triggerLabel = reviewers.length === 0 ? (
    <span className="text-muted-foreground">No reviewers</span>
  ) : (
    <span className="flex items-center gap-0.5">
      {reviewers.map((r) => (
        <span key={r.userId} className="flex items-center gap-0.5">
          <UserAvatar name={r.name} avatarUrl={r.avatarUrl} size={4} />
          <VerdictIcon verdict={r.reviewVerdict} />
        </span>
      ))}
    </span>
  )

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button className="focus:outline-none">
          <Badge variant="outline" className="cursor-pointer hover:opacity-80 transition-opacity gap-1 font-normal">
            {triggerLabel}
            <ChevronDown className="h-3 w-3 opacity-60" />
          </Badge>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="w-52">
        <DropdownMenuLabel className="text-xs text-muted-foreground font-medium">Reviewers</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {reviewerMembers.length === 0 ? (
          <DropdownMenuItem disabled>No reviewer-role members</DropdownMenuItem>
        ) : (
          reviewerMembers.map((m) => {
            const assigned = assignedIds.has(m.userId)
            const isSaving = saving === m.userId
            return (
              <DropdownMenuItem
                key={m.userId}
                disabled={isSaving}
                onClick={() => toggleReviewer(m)}
                className="cursor-pointer gap-2"
              >
                <span className="w-4 shrink-0 flex items-center justify-center">
                  {assigned && <Check className="h-3.5 w-3.5" />}
                </span>
                <UserAvatar name={m.name} avatarUrl={m.avatarUrl} size={5} />
                <span className="truncate flex-1">{m.name}</span>
              </DropdownMenuItem>
            )
          })
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  )
}

/**
 * The list (table/card) view of a Workflow's Work Items, scoped to one slug. Title, status options, and
 * type options all derive from the Workflow view; the fetch is always workflow-scoped.
 */
export function WorkItemListView({
  projectId,
  slug,
  noun,
}: {
  projectId: string
  slug: string
  noun: string
}) {
  const { accessToken, user } = useAuth()
  const router = useRouter()
  const pathname = usePathname()
  const searchParams = useSearchParams()

  const viewParam = searchParams.get('view')
  const view: View = viewParam === 'done' || viewParam === 'all' ? viewParam : 'active'

  // Display mode: list (default) or board. Priority: URL param > explicit localStorage > workflow defaultView.
  const modeKey = `wv_mode_${projectId}_${slug}`
  const modeExplicitKey = `wv_mode_explicit_${projectId}_${slug}`
  const [mode, setMode] = useState<DisplayMode>(() => {
    const p = searchParams.get('mode')
    if (p === 'board' || p === 'list') return p
    try {
      const stored = localStorage.getItem(modeKey)
      if (stored === 'board' || stored === 'list') return stored
    } catch { /* */ }
    return 'list'
  })

  function setDisplayMode(next: DisplayMode) {
    setMode(next)
    try {
      localStorage.setItem(modeKey, next)
      localStorage.setItem(modeExplicitKey, '1')
    } catch { /* */ }
  }

  const [issues, setIssues] = useState<IssueWithReviewers[]>([])

  // Seed members synchronously from the shared two-tier cache (module → localStorage) so that
  // the assignee dropdown and role-gated UI don't flash empty on revisit or cross-component mount.
  const [members, setMembers] = useState<Member[]>(() => getMembersCacheEntry(projectId) ?? [])
  const [userRole, setUserRole] = useState<MemberRole>(() => {
    const cached = getMembersCacheEntry(projectId)
    return cached?.find((m) => m.userId === user?.id)?.role ?? 'REVIEWER'
  })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<string>('All')
  const [statusFilter, setStatusFilter] = useState<string>('All')

  // The bound Workflow's display metadata — the single source of status labels/categories and the
  // allowed status/type option lists.
  const workflowView = useWorkflowView(projectId, slug, accessToken)

  // Apply workflow's defaultView once it loads — only if no URL param or explicit user preference.
  useEffect(() => {
    const wfDefault = workflowView?.defaultView as DisplayMode | undefined
    if (!wfDefault || (wfDefault !== 'list' && wfDefault !== 'board')) return
    const p = searchParams.get('mode')
    if (p === 'board' || p === 'list') return
    try {
      const explicit = localStorage.getItem(modeExplicitKey)
      if (explicit) return
    } catch { /* */ }
    setMode(wfDefault)
  }, [workflowView?.defaultView, modeExplicitKey, searchParams])

  const title = pluralizeNoun(noun)

  function setView(next: View) {
    if (next === view) return
    setStatusFilter('All')
    const sp = new URLSearchParams(searchParams.toString())
    if (next === 'active') sp.delete('view')
    else sp.set('view', next)
    const qs = sp.toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname)
  }

  useEffect(() => {
    if (!accessToken) return

    async function fetchAll() {
      try {
        const [issueData, memberData] = await Promise.all([
          // Always workflow-scoped — this page renders exactly one Workflow's Work Items.
          apiGet<IssueWithReviewers[]>(`/api/v2/projects/${projectId}/work-items?workflow=${slug}`, accessToken!),
          // Shared cache: deduplicates the concurrent fetch from PermissionsContext.
          fetchMembersCached(projectId, accessToken!),
        ])
        setIssues(issueData)
        setMembers(memberData)
        const currentMember = memberData.find((m) => m.userId === user?.id)
        if (currentMember) setUserRole(currentMember.role)
        // Show the table immediately — reviewer cells fill in after the second round.
        setLoading(false)

        // Fetch reviewers for all issues in parallel (second round, non-blocking)
        const reviewerResults = await Promise.allSettled(
          issueData.map((issue) =>
            apiGet<IssueReviewer[]>(
              `/api/v2/projects/${projectId}/work-items/${issue.id}/reviewers`,
              accessToken!
            )
          )
        )
        setIssues(issueData.map((issue, i) => ({
          ...issue,
          reviewers: reviewerResults[i].status === 'fulfilled' ? reviewerResults[i].value : [],
        })))
      } catch (err) {
        setError(apiErrorMessage(err, `Failed to load ${title.toLowerCase()}`))
        setLoading(false)
      }
    }

    fetchAll()
  }, [accessToken, projectId, slug, user?.id, title])

  function updateIssueStatus(issueId: string, newStatus: string) {
    setIssues((prev) => prev.map((i) => i.id === issueId ? { ...i, status: newStatus } : i))
  }

  function updateIssueAssignee(issueId: string, assignee: IssueAssignee | null) {
    setIssues((prev) => prev.map((i) => i.id === issueId ? { ...i, assignee } : i))
  }

  function updateIssueReviewers(issueId: string, reviewers: IssueReviewer[]) {
    setIssues((prev) => prev.map((i) => i.id === issueId ? { ...i, reviewers } : i))
  }

  // The status's lane category (open | in_progress | terminal), resolved via the bound Workflow.
  function categoryOf(issue: Issue): string {
    return statusMeta(workflowView, issue.status).category
  }

  function isInView(issue: Issue, target: View): boolean {
    if (target === 'all') return true
    return (categoriesForView(target) as string[]).includes(categoryOf(issue))
  }

  let activeCount = 0
  let doneCount = 0
  for (const issue of issues) {
    if (isInView(issue, 'active')) activeCount++
    else if (isInView(issue, 'done')) doneCount++
  }
  const counts = { active: activeCount, done: doneCount, all: issues.length }

  const issuesInView = issues.filter((issue) => isInView(issue, view))

  const filteredIssues = issuesInView.filter((issue) => {
    if (typeFilter !== 'All' && issue.type !== typeFilter) return false
    if (statusFilter !== 'All' && issue.status !== statusFilter) return false
    return true
  })

  // Type filter: the Workflow's allowed types (falling back to the types that actually appear on Work
  // Items until the view loads).
  const typeSet = new Set<string>()
  for (const t of workflowView?.types ?? []) typeSet.add(t)
  if (typeSet.size === 0) for (const i of issues) typeSet.add(i.type)
  const typeOptions = ['All', ...typeSet]

  // Status filter: the Workflow's statuses whose category belongs to the active tab, labelled via the
  // Workflow view. Value is the status id; the visible text is its label.
  const statusCats = categoriesForView(view) as string[]
  const statusOptions: { id: string; label: string }[] = []
  const seenStatus = new Set<string>()
  for (const s of workflowView?.statuses ?? []) {
    if (statusCats.includes(s.category) && !seenStatus.has(s.id)) {
      seenStatus.add(s.id)
      statusOptions.push({ id: s.id, label: s.label ?? humanizeId(s.id) })
    }
  }

  const statusFilterValue =
    statusFilter === 'All' || statusOptions.some((o) => o.id === statusFilter) ? statusFilter : 'All'

  if (loading) {
    return (
      <PageContainer>
        <PageHeader title={title} />
        <div className="space-y-2 mt-2">
          {[1, 2, 3, 4, 5].map((i) => (
            <Skeleton key={i} className="h-10" style={{ opacity: 1 - i * 0.1 }} />
          ))}
        </div>
      </PageContainer>
    )
  }

  if (error) {
    return (
      <PageContainer>
        <PageHeader title={title} />
        <div className="flex items-center justify-center h-64 text-destructive">Error: {error}</div>
      </PageContainer>
    )
  }

  const canEdit = userRole !== 'REVIEWER'

  const tabs: { id: View; label: string; count: number }[] = [
    { id: 'active', label: 'Active', count: counts.active },
    { id: 'done', label: 'Done', count: counts.done },
    { id: 'all', label: 'All', count: counts.all },
  ]

  const allStatusLabel = view === 'done' ? 'All done/closed' : view === 'active' ? 'All active' : 'All'

  const filtersAreActive = typeFilter !== 'All' || statusFilterValue !== 'All'

  const lowerNoun = noun.toLowerCase()

  // The Workflow's area drives the workflow-scoped detail URL. The slug (lowercased by the builder) is a
  // safe fallback until the view loads, since area defaults to the slug for single-Workflow areas.
  const detailArea = workflowView?.area ?? slug

  // Breadcrumb trail: area (non-link) › this Workflow's pluralized noun (current page). The area is
  // sourced from the bound Workflow's view; it is omitted until the view loads or when unset.
  const crumbs: Crumb[] = []
  if (workflowView?.area) crumbs.push({ label: humanizeId(workflowView.area) })
  crumbs.push({ label: title })

  return (
    <PageContainer>
      <PageHeader title={title} breadcrumbs={crumbs} />

      {/* View tabs + display mode toggle */}
      <div
        role="tablist"
        aria-label={`${title} view`}
        className="flex items-center gap-1 border-b border-border mb-4 -mx-1 px-1 overflow-x-auto overflow-y-hidden"
      >
        {tabs.map((t) => {
          const selected = t.id === view
          return (
            <button
              key={t.id}
              role="tab"
              type="button"
              aria-selected={selected}
              onClick={() => setView(t.id)}
              className={
                'relative px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors ' +
                (selected
                  ? 'text-foreground'
                  : 'text-muted-foreground hover:text-foreground')
              }
            >
              <span className="inline-flex items-center gap-1.5">
                {t.label}
                <span
                  className={
                    'inline-flex items-center justify-center min-w-[1.25rem] h-5 px-1.5 rounded-full text-xs font-medium ' +
                    (selected
                      ? 'bg-foreground/10 text-foreground'
                      : 'bg-muted text-muted-foreground')
                  }
                >
                  {t.count}
                </span>
              </span>
              {selected && (
                <span
                  aria-hidden="true"
                  className="absolute left-0 right-0 -bottom-px h-0.5 bg-primary rounded-full"
                />
              )}
            </button>
          )
        })}

        {/* Spacer + List/Board toggle pushed to the right */}
        <div className="ml-auto flex items-center gap-0.5 pb-px shrink-0">
          <button
            type="button"
            title="List view"
            onClick={() => setDisplayMode('list')}
            className={`p-1.5 rounded transition-colors ${
              mode === 'list'
                ? 'text-foreground bg-foreground/8'
                : 'text-muted-foreground hover:text-foreground hover:bg-muted'
            }`}
          >
            <ListIcon className="h-4 w-4" />
          </button>
          <button
            type="button"
            title="Board view"
            onClick={() => setDisplayMode('board')}
            className={`p-1.5 rounded transition-colors ${
              mode === 'board'
                ? 'text-foreground bg-foreground/8'
                : 'text-muted-foreground hover:text-foreground hover:bg-muted'
            }`}
          >
            <LayoutDashboardIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

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
            {typeOptions.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
        </div>

        <div className="flex items-center gap-2">
          <label htmlFor="status-filter" className="text-sm text-muted-foreground">Status:</label>
          <select
            id="status-filter"
            value={statusFilterValue}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="border border-border bg-background text-foreground rounded-md px-2 py-1 text-sm focus:outline-none focus:ring-2 focus:ring-ring"
          >
            <option value="All">{allStatusLabel}</option>
            {statusOptions.map((s) => (
              <option key={s.id} value={s.id}>{s.label}</option>
            ))}
          </select>
        </div>
      </div>

      {mode === 'board' && workflowView ? (
        <WorkItemBoardView
          projectId={projectId}
          slug={slug}
          noun={noun}
          workflowView={workflowView}
          issues={filteredIssues}
          userRole={userRole}
          accessToken={accessToken!}
          view={view}
          onStatusChanged={updateIssueStatus}
        />
      ) : filteredIssues.length === 0 ? (
        <div className="flex flex-col items-center justify-center h-48 gap-2 text-muted-foreground border border-dashed border-border rounded-lg">
          {issuesInView.length === 0 ? (
            <span>
              {view === 'done'
                ? `No completed ${lowerNoun} items yet`
                : view === 'active'
                ? `No active ${lowerNoun} items — nice work!`
                : `No ${lowerNoun} items yet`}
            </span>
          ) : (
            <>
              <span>No {lowerNoun} items match the current filters</span>
              {filtersAreActive && (
                <button
                  type="button"
                  onClick={() => { setTypeFilter('All'); setStatusFilter('All') }}
                  className="text-xs text-primary hover:underline"
                >
                  Clear filters
                </button>
              )}
            </>
          )}
        </div>
      ) : (
        <>
          {/* Mobile card list */}
          <div className="md:hidden space-y-2">
            {filteredIssues.map((issue) => (
              <Link
                key={issue.id}
                href={workItemDetailPath(projectId, detailArea, noun, issue.displayId ?? '')}
                className="block bg-card border border-border rounded-lg p-4 hover:border-border-strong transition-colors"
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <div className="flex flex-col gap-0.5">
                    {issue.displayId && (
                      <span className="font-mono text-xs text-muted-foreground">{issue.displayId}</span>
                    )}
                    <span className="font-medium text-foreground text-sm leading-snug">{issue.title}</span>
                  </div>
                  {(() => {
                    const meta = statusMeta(workflowView, issue.status)
                    return (
                      <StatusBadge
                        status={issue.status}
                        category={meta.category}
                        label={meta.label}
                        className="shrink-0"
                      />
                    )
                  })()}
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
                        <VerdictIcon verdict={r.reviewVerdict} />
                      </div>
                    ))}
                    {issue.unresolvedCommentCount != null && issue.unresolvedCommentCount > 0 && (
                      <span className="inline-flex items-center gap-0.5 text-xs text-muted-foreground">
                        <MessageSquare className="h-3 w-3" />
                        {issue.unresolvedCommentCount}
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
                    onClick={() => router.push(workItemDetailPath(projectId, detailArea, noun, issue.displayId ?? ''))}
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
                          workflowSlug={slug}
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
                          <MessageSquare className="h-3 w-3" />
                          {issue.unresolvedCommentCount}
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
    </PageContainer>
  )
}

