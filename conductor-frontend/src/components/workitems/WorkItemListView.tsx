'use client'

// COND-22: the generic Work Item list view, scoped to one Workflow slug. This is the migrated Issues
// list — title, status filter, and type filter all derive from the bound Workflow's view metadata, and
// the Work Items are fetched scoped to the slug (GET /issues?workflow=<slug>). It is mounted at the
// canonical /{projectId}/{area}/{noun} route (e.g. /engineering/issues).
//
// Redesigned (design-system.md) as a Linear-style grouped row list — see WorkItemRow/WorkItemGroup for
// row anatomy, useWorkItemListState for filter/sort/selection/keyboard state.

import { useCallback, useEffect, useRef, useState } from 'react'
import { usePathname, useRouter, useSearchParams } from 'next/navigation'
import Link from 'next/link'
import { CheckCircle2, Inbox, LayoutDashboardIcon, ListIcon, MessageSquare, SearchX } from 'lucide-react'
import { useAuth } from '@/contexts/AuthContext'
import { apiGet, apiPatch, apiErrorMessage } from '@/lib/api'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { toastError } from '@/components/ui/toast'
import { StatusDropdown } from '@/components/issues/StatusDropdown'
import { PageContainer } from '@/components/layout/PageContainer'
import { PageHeader, type Crumb } from '@/components/layout/PageHeader'
import { VerdictIcon } from '@/components/reviews/verdict'
import { timeAgo } from '@/lib/format'
import { readPersisted, readPersistedFlag, writePersisted } from '@/lib/persisted'
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
import { WorkItemListSkeleton } from '@/components/workitems/WorkItemListSkeleton'
import { WorkItemGroup } from '@/components/workitems/WorkItemGroup'
import { ListToolbar } from '@/components/workitems/ListToolbar'
import { BulkActionBar } from '@/components/workitems/BulkActionBar'
import { UserAvatar } from '@/components/workitems/UserAvatar'
import { useWorkItemListState } from '@/components/workitems/useWorkItemListState'
import type {
  IssueAssignee,
  IssueReviewer,
  IssueWithReviewers,
  ListView,
  Member,
} from '@/components/workitems/listTypes'
import type { MemberRole } from '@/types'

type DisplayMode = 'list' | 'board'

/**
 * The list (grouped rows / board) view of a Workflow's Work Items, scoped to one slug. Title, status
 * options, and type options all derive from the Workflow view; the fetch is always workflow-scoped.
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
  const view: ListView = viewParam === 'done' || viewParam === 'all' ? viewParam : 'active'

  // Display mode: list (default) or board. Priority: URL param > explicit localStorage > workflow defaultView.
  const modeKey = `wv_mode_${projectId}_${slug}`
  const modeExplicitKey = `wv_mode_explicit_${projectId}_${slug}`
  const [mode, setMode] = useState<DisplayMode>(() => {
    const p = searchParams.get('mode')
    if (p === 'board' || p === 'list') return p
    return readPersisted<DisplayMode>(modeKey, (v): v is DisplayMode => v === 'board' || v === 'list', 'list')
  })

  function setDisplayMode(next: DisplayMode) {
    setMode(next)
    writePersisted(modeKey, next)
    writePersisted(modeExplicitKey, '1')
  }

  const [issues, setIssues] = useState<IssueWithReviewers[]>([])

  // Seed members synchronously from the shared two-tier cache (module → localStorage) so that
  // the assignee dropdown and role-gated UI don't flash empty on revisit or cross-component mount.
  const [members, setMembers] = useState<Member[]>(() => getMembersCacheEntry(projectId) ?? [])
  const [userRole, setUserRole] = useState<MemberRole>(() => {
    const cached = getMembersCacheEntry(projectId)
    return cached?.find((m) => m.userId === user?.id)?.role ?? 'REVIEWER'
  })
  // REVIEWERs can't mutate Work Items — gates the assignee control, bulk selection, and the palette's
  // Selection group. Computed here (not after the loading/error early returns) since it feeds the
  // useWorkItemListState hook call below, and hooks can't follow a conditional return.
  const canEdit = userRole !== 'REVIEWER'
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // The bound Workflow's display metadata — the single source of status labels/colors/categories and
  // the allowed status/type option lists.
  const workflowView = useWorkflowView(projectId, slug, accessToken)

  // Apply workflow's defaultView once it loads — only if no URL param or explicit user preference.
  useEffect(() => {
    const wfDefault = workflowView?.defaultView as DisplayMode | undefined
    if (!wfDefault || (wfDefault !== 'list' && wfDefault !== 'board')) return
    const p = searchParams.get('mode')
    if (p === 'board' || p === 'list') return
    if (readPersistedFlag(modeExplicitKey)) return
    setMode(wfDefault)
  }, [workflowView?.defaultView, modeExplicitKey, searchParams])

  const title = pluralizeNoun(noun)
  const lowerNoun = title.toLowerCase()

  function setView(next: ListView) {
    if (next === view) return
    const sp = new URLSearchParams(searchParams.toString())
    if (next === 'active') sp.delete('view')
    else sp.set('view', next)
    const qs = sp.toString()
    router.replace(qs ? `${pathname}?${qs}` : pathname)
  }

  const loadIssues = useCallback(async () => {
    if (!accessToken) return
    try {
      const [issueData, memberData] = await Promise.all([
        // Always workflow-scoped — this page renders exactly one Workflow's Work Items.
        apiGet<IssueWithReviewers[]>(`/api/v2/projects/${projectId}/work-items?workflow=${slug}`, accessToken),
        // Shared cache: deduplicates the concurrent fetch from PermissionsContext.
        fetchMembersCached(projectId, accessToken),
      ])
      setIssues(issueData)
      setMembers(memberData)
      const currentMember = memberData.find((m) => m.userId === user?.id)
      if (currentMember) setUserRole(currentMember.role)
      // Show the list immediately — reviewer cells fill in after the second round.
      setLoading(false)

      // Fetch reviewers for all issues in parallel (second round, non-blocking)
      const reviewerResults = await Promise.allSettled(
        issueData.map((issue) =>
          apiGet<IssueReviewer[]>(`/api/v2/projects/${projectId}/work-items/${issue.id}/reviewers`, accessToken)
        )
      )
      setIssues(
        issueData.map((issue, i) => ({
          ...issue,
          reviewers: reviewerResults[i].status === 'fulfilled' ? reviewerResults[i].value : [],
        }))
      )
    } catch (err) {
      setError(apiErrorMessage(err, `Failed to load ${title.toLowerCase()}`))
      setLoading(false)
    }
  }, [accessToken, projectId, slug, user?.id, title])

  useEffect(() => {
    void loadIssues()
  }, [loadIssues])

  function updateIssueStatus(issueId: string, newStatus: string) {
    setIssues((prev) => prev.map((i) => (i.id === issueId ? { ...i, status: newStatus } : i)))
  }

  function updateIssueAssignee(issueId: string, assignee: IssueAssignee | null) {
    setIssues((prev) => prev.map((i) => (i.id === issueId ? { ...i, assignee } : i)))
  }

  function updateIssueReviewers(issueId: string, reviewers: IssueReviewer[]) {
    setIssues((prev) => prev.map((i) => (i.id === issueId ? { ...i, reviewers } : i)))
  }

  // The status's lane category (open | in_progress | terminal), resolved via the bound Workflow.
  function categoryOf(issue: IssueWithReviewers): string {
    return statusMeta(workflowView, issue.status).category
  }

  function isInView(issue: IssueWithReviewers, target: ListView): boolean {
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

  // Type filter options: the Workflow's allowed types (falling back to the types that actually appear
  // on Work Items until the view loads).
  const typeSet = new Set<string>()
  for (const t of workflowView?.types ?? []) typeSet.add(t)
  if (typeSet.size === 0) for (const i of issues) typeSet.add(i.type)
  const typeOptions = [...typeSet]

  // Status filter options: the Workflow's statuses whose category belongs to the active tab, labelled
  // via the Workflow view.
  const statusCats = categoriesForView(view) as string[]
  const statusOptions: { id: string; label: string }[] = []
  const seenStatus = new Set<string>()
  for (const s of workflowView?.statuses ?? []) {
    if (statusCats.includes(s.category) && !seenStatus.has(s.id)) {
      seenStatus.add(s.id)
      statusOptions.push({ id: s.id, label: s.label ?? humanizeId(s.id) })
    }
  }

  const detailArea = workflowView?.area ?? slug

  const bulkStatusTriggerRef = useRef<HTMLButtonElement>(null)
  const bulkAssignTriggerRef = useRef<HTMLButtonElement>(null)

  const listState = useWorkItemListState({
    storageKeyPrefix: `${projectId}_${slug}`,
    view,
    issuesInView,
    workflowView,
    canEdit,
    bulkStatusTriggerRef,
    bulkAssignTriggerRef,
  })

  // One shared bulk helper (bulkChangeStatus/bulkAssign were near-identical clones): runs `mutate`
  // over `ids` concurrently (Promise.allSettled, not sequential awaits — one slow/failing item can't
  // block the rest), skips items already at the target value, keeps only the failed ids selected on
  // partial failure (so the user can retry just those), and refetches once at the end either way.
  async function runBulkMutation(ids: string[], mutate: (id: string) => Promise<unknown>, failureNoun: string) {
    if (ids.length === 0) {
      listState.clearSelection()
      return
    }
    listState.setBulkInFlight(true)
    try {
      const results = await Promise.allSettled(ids.map((id) => mutate(id)))
      const failedIds = ids.filter((_, i) => results[i].status === 'rejected')
      if (failedIds.length > 0) {
        toastError(`${failedIds.length} of ${ids.length} Work Items failed to update ${failureNoun}`)
        listState.setSelection(failedIds)
      } else {
        listState.clearSelection()
      }
      await loadIssues()
    } finally {
      listState.setBulkInFlight(false)
    }
  }

  async function bulkChangeStatus(newStatus: string) {
    const ids = [...listState.selected].filter((id) => issues.find((i) => i.id === id)?.status !== newStatus)
    await runBulkMutation(
      ids,
      (id) => apiPatch(`/api/v2/projects/${projectId}/work-items/${id}`, { status: newStatus }, accessToken!),
      'status'
    )
  }

  async function bulkAssign(member: Member | null) {
    const targetId = member?.userId ?? null
    const ids = [...listState.selected].filter((id) => (issues.find((i) => i.id === id)?.assignee?.userId ?? null) !== targetId)
    await runBulkMutation(
      ids,
      (id) => apiPatch(`/api/v2/projects/${projectId}/work-items/${id}`, { assigneeId: member ? member.userId : '' }, accessToken!),
      'assignee'
    )
  }

  if (loading) {
    return (
      <PageContainer>
        <PageHeader title={title} />
        <WorkItemListSkeleton />
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

  const tabItems: TabItem[] = [
    { value: 'active', label: 'Active', count: counts.active },
    { value: 'done', label: 'Done', count: counts.done },
    { value: 'all', label: 'All', count: counts.all },
  ]

  // Breadcrumb trail: area (non-link) › this Workflow's pluralized noun (current page). The area is
  // sourced from the bound Workflow's view; it is omitted until the view loads or when unset.
  const crumbs: Crumb[] = []
  if (workflowView?.area) crumbs.push({ label: humanizeId(workflowView.area) })
  crumbs.push({ label: title })

  return (
    <PageContainer>
      <PageHeader title={title} breadcrumbs={crumbs} />

      {/* View tabs + display mode toggle */}
      <div className="flex items-center border-b border-border mb-4 -mx-1 px-1">
        <Tabs
          items={tabItems}
          value={view}
          onValueChange={(v) => setView(v as ListView)}
          ariaLabel={`${title} view`}
          className="flex-1 min-w-0 border-b-0"
        />

        {/* List/Board toggle pushed to the right */}
        <div className="ml-auto flex items-center gap-0.5 pb-px shrink-0">
          <button
            type="button"
            title="List view"
            onClick={() => setDisplayMode('list')}
            className={`p-1.5 rounded transition-colors ${
              mode === 'list' ? 'text-foreground bg-foreground/8' : 'text-muted-foreground hover:text-foreground hover:bg-muted'
            }`}
          >
            <ListIcon className="h-4 w-4" />
          </button>
          <button
            type="button"
            title="Board view"
            onClick={() => setDisplayMode('board')}
            className={`p-1.5 rounded transition-colors ${
              mode === 'board' ? 'text-foreground bg-foreground/8' : 'text-muted-foreground hover:text-foreground hover:bg-muted'
            }`}
          >
            <LayoutDashboardIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

      <ListToolbar
        typeOptions={typeOptions}
        statusOptions={statusOptions}
        activeFilters={listState.activeFilters}
        onAddFilter={listState.addFilter}
        onRemoveFilter={listState.removeFilter}
        sortKey={listState.sortKey}
        onSortChange={listState.setSortKey}
        showSort={mode === 'list'}
      />

      {mode === 'board' && workflowView ? (
        <WorkItemBoardView
          projectId={projectId}
          slug={slug}
          noun={noun}
          workflowView={workflowView}
          issues={listState.filteredIssues}
          userRole={userRole}
          accessToken={accessToken!}
          view={view}
          onStatusChanged={updateIssueStatus}
        />
      ) : issuesInView.length === 0 ? (
        <EmptyState
          icon={view === 'done' ? CheckCircle2 : Inbox}
          title={
            view === 'done'
              ? `No completed ${lowerNoun} yet`
              : view === 'active'
                ? `No active ${lowerNoun} — nice work!`
                : `No ${lowerNoun} yet`
          }
          description={
            view === 'all'
              ? `${title} are created by agents via MCP — they'll show up here once created.`
              : undefined
          }
        />
      ) : listState.groups.length === 0 ? (
        <EmptyState
          icon={SearchX}
          title={`No ${lowerNoun} match the current filters`}
          action={
            <Button variant="link" size="sm" onClick={listState.clearFilters}>
              Clear filters
            </Button>
          }
        />
      ) : (
        <>
          {canEdit && listState.selected.size > 0 && (
            <BulkActionBar
              count={listState.selected.size}
              statusOptions={(workflowView?.statuses ?? []).map((s) => ({ id: s.id, label: s.label ?? humanizeId(s.id), category: s.category }))}
              members={members}
              onChangeStatus={bulkChangeStatus}
              onAssign={bulkAssign}
              disabled={listState.bulkInFlight}
              statusTriggerRef={bulkStatusTriggerRef}
              assignTriggerRef={bulkAssignTriggerRef}
            />
          )}

          {/* Mobile card list */}
          <div className="md:hidden space-y-2">
            {listState.groups.flatMap((g) => g.items).map((issue) => (
              <Link
                key={issue.id}
                href={workItemDetailPath(projectId, detailArea, noun, issue.displayId ?? '')}
                className="block bg-card border border-border rounded-lg p-4 hover:border-border-strong transition-colors"
              >
                <div className="flex items-start justify-between gap-2 mb-2">
                  <div className="flex items-center gap-2 min-w-0">
                    <div className="flex flex-col gap-0.5 min-w-0">
                      {issue.displayId && <span className="font-mono text-xs text-muted-foreground">{issue.displayId}</span>}
                      <span className="font-medium text-foreground text-sm leading-snug truncate">{issue.title}</span>
                    </div>
                  </div>
                  <span
                    onClick={(e) => {
                      // The status control is the one interactive child inside this card-wide
                      // anchor — stopPropagation alone doesn't stop the anchor's own default
                      // navigation, so preventDefault too (same dead-zone bug as the desktop row).
                      e.stopPropagation()
                      e.preventDefault()
                    }}
                    className="shrink-0"
                  >
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
                  </span>
                </div>
                <div className="flex items-center gap-2 flex-wrap">
                  <Badge variant="outline" className="text-xs">{issue.type}</Badge>
                  {issue.assignee && (
                    <div className="flex items-center gap-1">
                      <UserAvatar name={issue.assignee.name} avatarUrl={issue.assignee.avatarUrl} size={4} />
                      <span className="text-xs text-muted-foreground">{issue.assignee.name}</span>
                    </div>
                  )}
                  <div className="flex items-center gap-1.5 ml-auto">
                    {(issue.reviewers ?? []).map((r) => (
                      <span key={r.userId} className="inline-flex items-center gap-0.5">
                        <UserAvatar name={r.name} avatarUrl={r.avatarUrl} size={4} />
                        <VerdictIcon verdict={r.reviewVerdict} className="h-3 w-3" />
                      </span>
                    ))}
                    {issue.unresolvedCommentCount != null && issue.unresolvedCommentCount > 0 && (
                      <span className="inline-flex items-center gap-0.5 text-xs text-muted-foreground">
                        <MessageSquare className="h-3 w-3" />
                        {issue.unresolvedCommentCount}
                      </span>
                    )}
                  </div>
                  <span className="text-foreground-subtle text-xs shrink-0">{timeAgo(issue.updatedAt)}</span>
                </div>
              </Link>
            ))}
          </div>

          {/* Desktop grouped row list */}
          <div
            data-testid="work-item-list"
            className="hidden md:block border border-border rounded-lg"
            onKeyDown={listState.onContainerKeyDown}
          >
            {listState.groups.map((group, i) => (
              <WorkItemGroup
                key={group.statusId}
                group={group}
                projectId={projectId}
                slug={slug}
                detailArea={detailArea}
                noun={noun}
                userRole={userRole}
                accessToken={accessToken!}
                members={members}
                canEdit={canEdit}
                selectedIds={listState.selected}
                tabStopId={listState.tabStopId}
                roundedTop={i === 0}
                roundedBottom={i === listState.groups.length - 1}
                onStatusChanged={updateIssueStatus}
                onAssigneeChanged={updateIssueAssignee}
                onReviewersChanged={updateIssueReviewers}
                onRowFocus={listState.setFocusedId}
                registerStatusTriggerRef={listState.registerStatusTriggerRef}
                registerAssigneeTriggerRef={listState.registerAssigneeTriggerRef}
                registerRowLinkRef={listState.registerRowLinkRef}
              />
            ))}
          </div>
        </>
      )}
    </PageContainer>
  )
}
