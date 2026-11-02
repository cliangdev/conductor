'use client'

// The Plan tab's tree: a PRD's children, recursively, as 38px list rows. Parents collapse; a leaf expands
// to its acceptance criteria, where a `manual` one can be ticked in place and an `auto` one (verified by
// a test run) is read-only with its evidence. Everything shown is derived server-side (signals, counts).

import { useCallback, useEffect, useMemo, useState } from 'react'
import Link from 'next/link'
import { ChevronRight, CircleCheck, Circle, ListTree } from 'lucide-react'
import { apiErrorMessage } from '@/lib/api'
import { fetchCriteria, fetchTree, patchCriterion } from '@/lib/delivery'
import { statusMeta, useWorkflowViews, workItemDetailPath } from '@/lib/workflows'
import { cn } from '@/lib/utils'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { toastError } from '@/components/ui/toast'
import { StatusRing } from '@/components/workitems/StatusRing'
import { PriorityChip, SignalBadges } from '@/components/workitems/plan/chips'
import type { WorkItemCriterion, WorkItemTreeNode, WorkflowView } from '@/types/workItem'

const INDENT_PX = 20
const BASE_PAD_PX = 12

function collectWorkflows(node: WorkItemTreeNode, into: Set<string>) {
  if (node.workflow) into.add(node.workflow)
  node.children.forEach((c) => collectWorkflows(c, into))
}

/** Returns a copy of the tree with `fn` applied to the node `id`. */
function mapNode(node: WorkItemTreeNode, id: string, fn: (n: WorkItemTreeNode) => WorkItemTreeNode): WorkItemTreeNode {
  if (node.id === id) return fn(node)
  if (node.children.length === 0) return node
  return { ...node, children: node.children.map((c) => mapNode(c, id, fn)) }
}

function CriteriaBar({ met, total }: { met: number; total: number }) {
  const pct = total === 0 ? 0 : Math.round((met / total) * 100)
  return (
    <span className="hidden shrink-0 items-center gap-1.5 sm:flex" title={`${met} of ${total} criteria met`}>
      <span className="h-1 w-10 overflow-hidden rounded-full bg-muted">
        <span className="block h-full rounded-full bg-primary" style={{ width: `${pct}%` }} />
      </span>
      <span className="text-[11.5px] tabular-nums text-muted-foreground">
        {met}/{total}
      </span>
    </span>
  )
}

function CriteriaRows({
  projectId,
  nodeId,
  token,
  canEdit,
  depth,
  onMetDelta,
}: {
  projectId: string
  nodeId: string
  token: string
  canEdit: boolean
  depth: number
  onMetDelta: (nodeId: string, delta: number) => void
}) {
  const [criteria, setCriteria] = useState<WorkItemCriterion[] | null>(null)
  const [error, setError] = useState(false)

  const load = useCallback(() => {
    setError(false)
    fetchCriteria(projectId, nodeId, token)
      .then(setCriteria)
      .catch(() => setError(true))
  }, [projectId, nodeId, token])

  useEffect(() => {
    load()
  }, [load])

  async function toggle(criterion: WorkItemCriterion) {
    const next = !criterion.met
    const apply = (met: boolean) =>
      setCriteria((prev) => prev?.map((c) => (c.criterionId === criterion.criterionId ? { ...c, met } : c)) ?? prev)
    apply(next)
    onMetDelta(nodeId, next ? 1 : -1)
    try {
      await patchCriterion(projectId, nodeId, criterion.criterionId, { met: next }, token)
    } catch (err) {
      apply(!next)
      onMetDelta(nodeId, next ? -1 : 1)
      toastError(apiErrorMessage(err, 'Could not update the criterion'))
    }
  }

  const pad = { paddingLeft: BASE_PAD_PX + (depth + 1) * INDENT_PX + 18 }

  if (error) {
    return (
      <div className="flex items-center gap-2 border-b border-border py-2 pr-3 text-xs text-muted-foreground" style={pad}>
        Couldn&rsquo;t load the criteria.
        <Button variant="link" size="sm" className="h-auto p-0 text-xs" onClick={load}>
          Retry
        </Button>
      </div>
    )
  }
  if (!criteria) {
    return (
      <div className="space-y-2 border-b border-border py-2 pr-3" style={pad} aria-busy="true" aria-label="Loading criteria">
        <Skeleton className="h-4 w-2/3" />
        <Skeleton className="h-4 w-1/2" />
      </div>
    )
  }
  return (
    <ul className="border-b border-border bg-muted/40 py-1" aria-label="Acceptance criteria">
      {criteria.map((c) => (
        <li key={c.criterionId} className="py-1 pr-3" style={pad}>
          {c.kind === 'manual' ? (
            <Checkbox
              checked={c.met}
              disabled={!canEdit}
              onCheckedChange={() => void toggle(c)}
              label={<span className="text-[13px]">{c.text}</span>}
              description={c.evidence ?? undefined}
            />
          ) : (
            <div className="flex items-start gap-2" title={c.evidence ?? 'Verified by a test run'}>
              {c.met ? (
                <CircleCheck className="mt-0.5 h-4 w-4 shrink-0 text-status-done" aria-label="Met" />
              ) : (
                <Circle className="mt-0.5 h-4 w-4 shrink-0 text-foreground-subtle" aria-label="Not met" />
              )}
              <div className="min-w-0">
                <p className="text-[13px] text-foreground">{c.text}</p>
                <p className="text-[11.5px] text-muted-foreground">
                  {c.evidence ? c.evidence : 'Auto: marked met when its test passes'}
                </p>
              </div>
            </div>
          )}
        </li>
      ))}
    </ul>
  )
}

function TreeRows({
  node,
  depth,
  ctx,
}: {
  node: WorkItemTreeNode
  depth: number
  ctx: {
    projectId: string
    token: string
    canEdit: boolean
    views: Record<string, WorkflowView>
    toggled: Set<string>
    onToggle: (id: string) => void
    onMetDelta: (nodeId: string, delta: number) => void
  }
}) {
  const hasChildren = node.children.length > 0
  const hasCriteria = !hasChildren && node.criteriaTotal > 0
  const expandable = hasChildren || hasCriteria
  // Parents start open (the plan is the point), leaves start closed (criteria are detail).
  const open = hasChildren ? !ctx.toggled.has(node.id) : ctx.toggled.has(node.id)

  const view = node.workflow ? ctx.views[node.workflow] : undefined
  const category = statusMeta(view, node.status).category
  const href = view
    ? workItemDetailPath(ctx.projectId, view.area ?? node.workflow ?? '', view.noun, node.displayId)
    : `/app/projects/${ctx.projectId}/issues/${node.id}`

  return (
    <>
      <div
        data-testid={`plan-row-${node.displayId}`}
        className="flex h-[38px] min-w-0 items-center gap-2 border-b border-border pr-3 transition-colors hover:bg-muted"
        style={{ paddingLeft: BASE_PAD_PX + depth * INDENT_PX }}
      >
        {expandable ? (
          <button
            type="button"
            onClick={() => ctx.onToggle(node.id)}
            aria-expanded={open}
            aria-label={`${open ? 'Collapse' : 'Expand'} ${node.displayId}`}
            className="shrink-0 rounded-sm text-muted-foreground transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <ChevronRight className={cn('h-3.5 w-3.5 transition-transform', open && 'rotate-90')} />
          </button>
        ) : (
          <span className="w-3.5 shrink-0" aria-hidden="true" />
        )}
        <StatusRing status={node.status} category={node.terminal ? 'terminal' : category} label={node.statusLabel ?? undefined} />
        <span className="w-16 shrink-0 truncate font-mono text-xs text-muted-foreground">{node.displayId}</span>
        <Link
          prefetch={false}
          href={href}
          className="min-w-0 flex-1 truncate rounded-sm text-[13px] font-medium text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          {node.title}
        </Link>
        {node.agentActivity?.activity === 'WORKING' && (
          <span
            className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full bg-primary"
            title={`${node.agentActivity.agentLabel} is working on this`}
            role="img"
            aria-label="Agent working"
          />
        )}
        <SignalBadges signals={node.signals} />
        <PriorityChip priority={node.priority} />
        {node.criteriaTotal > 0 && <CriteriaBar met={node.criteriaMet} total={node.criteriaTotal} />}
      </div>
      {hasChildren && open && node.children.map((child) => <TreeRows key={child.id} node={child} depth={depth + 1} ctx={ctx} />)}
      {hasCriteria && open && (
        <CriteriaRows
          projectId={ctx.projectId}
          nodeId={node.id}
          token={ctx.token}
          canEdit={ctx.canEdit}
          depth={depth}
          onMetDelta={ctx.onMetDelta}
        />
      )}
    </>
  )
}

export function WorkItemTree({
  projectId,
  rootId,
  token,
  canEdit,
}: {
  projectId: string
  rootId: string
  token: string
  canEdit: boolean
}) {
  const [root, setRoot] = useState<WorkItemTreeNode | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [toggled, setToggled] = useState<Set<string>>(new Set())

  const load = useCallback(() => {
    setError(null)
    fetchTree(projectId, rootId, token)
      .then(setRoot)
      .catch((err) => setError(apiErrorMessage(err, 'Couldn’t load the plan.')))
  }, [projectId, rootId, token])

  useEffect(() => {
    load()
  }, [load])

  const slugs = useMemo(() => {
    const set = new Set<string>()
    if (root) collectWorkflows(root, set)
    return [...set]
  }, [root])
  const views = useWorkflowViews(projectId, slugs, token)

  const onToggle = useCallback((id: string) => {
    setToggled((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }, [])

  const onMetDelta = useCallback((nodeId: string, delta: number) => {
    setRoot((prev) => (prev ? mapNode(prev, nodeId, (n) => ({ ...n, criteriaMet: Math.max(0, n.criteriaMet + delta) })) : prev))
  }, [])

  if (error) {
    return (
      <Alert variant="warning">
        <div className="flex items-center justify-between gap-3">
          <span>{error}</span>
          <Button variant="link" size="sm" className="h-auto p-0" onClick={load}>
            Retry
          </Button>
        </div>
      </Alert>
    )
  }
  if (!root) {
    return (
      <div className="space-y-2" aria-busy="true" aria-label="Loading plan">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-[38px] w-full" />
        ))}
      </div>
    )
  }
  if (root.children.length === 0) {
    return (
      <EmptyState
        icon={ListTree}
        title="No plan yet — run /conductor:breakdown"
        description="Breaking the PRD down creates its tasks, dependencies and acceptance criteria."
      />
    )
  }

  const ctx = { projectId, token, canEdit, views, toggled, onToggle, onMetDelta }
  return (
    <div aria-label="Plan" className="overflow-hidden rounded-lg border border-border bg-surface">
      {root.children.map((child) => (
        <TreeRows key={child.id} node={child} depth={0} ctx={ctx} />
      ))}
    </div>
  )
}
