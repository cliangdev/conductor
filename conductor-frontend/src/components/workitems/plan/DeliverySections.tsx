'use client'

// The delivery-tracking sections of the properties panel: Priority, Milestone, Appetite, Parent and
// Blocked by. Every save goes through patchDelivery (or the dependency calls); a refused save toasts the
// server's reason and the control falls back to what the server still holds.

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import { XIcon } from 'lucide-react'
import { apiErrorMessage, apiGet } from '@/lib/api'
import {
  PRIORITIES,
  addDependency,
  fetchDependencies,
  fetchMilestones,
  patchDelivery,
  priorityLabel,
  removeDependency,
} from '@/lib/delivery'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { toastError } from '@/components/ui/toast'
import { PanelSection } from '@/components/workitems/PanelSection'
import { NO_REF, type WorkItemRefState } from '@/components/workitems/plan/useWorkItemRef'
import type {
  Milestone,
  WorkItemDeliveryFields,
  WorkItemDependencyEntry,
  WorkItemPriority,
} from '@/types/workItem'

export type DeliveryPatchResult = Partial<WorkItemDeliveryFields>

export function DeliverySections({
  projectId,
  issueId,
  token,
  canEdit,
  delivery,
  parent = NO_REF,
  onChanged,
}: {
  projectId: string
  issueId: string
  token: string
  canEdit: boolean
  delivery: WorkItemDeliveryFields
  /** The parent, resolved once by the page (its breadcrumb needs it too). */
  parent?: WorkItemRefState
  /** The fields that changed, as the server now holds them, for the page to merge into its copy. */
  onChanged: (patch: DeliveryPatchResult) => void
}) {
  return (
    <>
      <PrioritySection projectId={projectId} issueId={issueId} token={token} canEdit={canEdit} priority={delivery.priority} onChanged={onChanged} />
      <MilestoneSection
        projectId={projectId}
        issueId={issueId}
        token={token}
        canEdit={canEdit}
        milestoneId={delivery.milestoneId}
        milestoneName={delivery.milestoneName}
        onChanged={onChanged}
      />
      <AppetiteSection projectId={projectId} issueId={issueId} token={token} canEdit={canEdit} appetite={delivery.appetite} onChanged={onChanged} />
      <ParentSection parent={parent} parentWorkItemId={delivery.parentWorkItemId} childCount={delivery.childCount} />
      <BlockedBySection projectId={projectId} issueId={issueId} token={token} canEdit={canEdit} />
    </>
  )
}

function PrioritySection({
  projectId,
  issueId,
  token,
  canEdit,
  priority,
  onChanged,
}: {
  projectId: string
  issueId: string
  token: string
  canEdit: boolean
  priority?: WorkItemPriority | null
  onChanged: (patch: DeliveryPatchResult) => void
}) {
  const [saving, setSaving] = useState(false)

  async function change(value: string) {
    const next = (value || null) as WorkItemPriority | null
    setSaving(true)
    try {
      await patchDelivery(projectId, issueId, { priority: next }, token)
      onChanged({ priority: next })
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not change the priority'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <PanelSection label="Priority">
      {canEdit ? (
        <Select aria-label="Priority" value={priority ?? ''} disabled={saving} onChange={(e) => void change(e.target.value)}>
          <option value="">No priority</option>
          {PRIORITIES.map((p) => (
            <option key={p} value={p}>
              {priorityLabel(p)}
            </option>
          ))}
        </Select>
      ) : (
        <span className="text-sm text-foreground">{priorityLabel(priority)}</span>
      )}
    </PanelSection>
  )
}

function MilestoneSection({
  projectId,
  issueId,
  token,
  canEdit,
  milestoneId,
  milestoneName,
  onChanged,
}: {
  projectId: string
  issueId: string
  token: string
  canEdit: boolean
  milestoneId?: string | null
  milestoneName?: string | null
  onChanged: (patch: DeliveryPatchResult) => void
}) {
  const [milestones, setMilestones] = useState<Milestone[] | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (!canEdit || !token) return
    let cancelled = false
    fetchMilestones(projectId, token)
      .then((list) => {
        if (!cancelled) setMilestones(list)
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token, canEdit])

  async function change(value: string) {
    const next = value || null
    setSaving(true)
    try {
      await patchDelivery(projectId, issueId, { milestoneId: next }, token)
      onChanged({ milestoneId: next, milestoneName: milestones?.find((m) => m.id === next)?.name ?? null })
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not change the milestone'))
    } finally {
      setSaving(false)
    }
  }

  // Closed milestones are not offered, but the one this item is already in stays selectable.
  const options = (milestones ?? []).filter((m) => m.state === 'OPEN' || m.id === milestoneId)

  return (
    <PanelSection label="Milestone">
      {!canEdit ? (
        <span className="text-sm text-foreground">{milestoneName ?? 'None'}</span>
      ) : loadFailed ? (
        <p className="text-xs text-muted-foreground">Couldn&rsquo;t load milestones{milestoneName ? ` (in ${milestoneName})` : ''}.</p>
      ) : !milestones ? (
        <Skeleton className="h-8 w-full" aria-label="Loading milestones" />
      ) : (
        <Select aria-label="Milestone" value={milestoneId ?? ''} disabled={saving} onChange={(e) => void change(e.target.value)}>
          <option value="">None</option>
          {options.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
        </Select>
      )}
    </PanelSection>
  )
}

function AppetiteSection({
  projectId,
  issueId,
  token,
  canEdit,
  appetite,
  onChanged,
}: {
  projectId: string
  issueId: string
  token: string
  canEdit: boolean
  appetite?: string | null
  onChanged: (patch: DeliveryPatchResult) => void
}) {
  const [draft, setDraft] = useState(appetite ?? '')
  useEffect(() => setDraft(appetite ?? ''), [appetite])

  async function save() {
    const trimmed = draft.trim()
    if (trimmed === (appetite ?? '')) {
      setDraft(trimmed)
      return
    }
    try {
      const saved = await patchDelivery<WorkItemDeliveryFields>(projectId, issueId, { appetite: trimmed || null }, token)
      // The server normalises ("2w" -> "2 weeks"); show what it kept.
      const label = trimmed ? (saved?.appetite ?? trimmed) : null
      setDraft(label ?? '')
      onChanged({ appetite: label, appetiteDays: saved?.appetiteDays ?? null })
    } catch (err) {
      setDraft(appetite ?? '')
      toastError(apiErrorMessage(err, 'Could not save the appetite'))
    }
  }

  return (
    <PanelSection label="Appetite">
      {canEdit ? (
        <Input
          aria-label="Appetite"
          placeholder="e.g. 2 weeks"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={() => void save()}
          onKeyDown={(e) => {
            if (e.key === 'Enter') e.currentTarget.blur()
          }}
        />
      ) : (
        <span className="text-sm text-foreground">{appetite ?? 'None'}</span>
      )}
    </PanelSection>
  )
}

function ParentSection({
  parent,
  parentWorkItemId,
  childCount,
}: {
  parent: WorkItemRefState
  parentWorkItemId?: string | null
  childCount?: number
}) {
  const subItems = childCount ?? 0
  if (!parentWorkItemId && subItems === 0) return null
  return (
    <PanelSection label="Parent">
      <div className="space-y-1 text-sm">
        {parentWorkItemId &&
          (parent.status === 'error' ? (
            <p className="text-sm text-muted-foreground">Could not load the parent.</p>
          ) : parent.ref ? (
            <Link prefetch={false} href={parent.ref.href} className="flex min-w-0 items-baseline gap-2 text-foreground hover:underline">
              <span className="shrink-0 font-mono text-xs text-muted-foreground">{parent.ref.displayId}</span>
              <span className="truncate">{parent.ref.title}</span>
            </Link>
          ) : (
            <Skeleton className="h-4 w-2/3" aria-label="Loading parent" />
          ))}
        {subItems > 0 && (
          <p className="text-xs text-muted-foreground">
            {subItems} sub-item{subItems === 1 ? '' : 's'}
          </p>
        )}
      </div>
    </PanelSection>
  )
}

function BlockedBySection({
  projectId,
  issueId,
  token,
  canEdit,
}: {
  projectId: string
  issueId: string
  token: string
  canEdit: boolean
}) {
  const [blockers, setBlockers] = useState<WorkItemDependencyEntry[] | null>(null)
  const [loadFailed, setLoadFailed] = useState(false)
  const [displayId, setDisplayId] = useState('')
  const [adding, setAdding] = useState(false)

  const load = useCallback(() => {
    fetchDependencies(projectId, issueId, token)
      .then((d) => {
        setBlockers(d.blockedBy)
        setLoadFailed(false)
      })
      .catch(() => setLoadFailed(true))
  }, [projectId, issueId, token])

  useEffect(() => {
    if (token) load()
  }, [load, token])

  async function add() {
    const id = displayId.trim().toUpperCase()
    if (!id) return
    setAdding(true)
    try {
      let blocker: { id: string }
      try {
        blocker = await apiGet<{ id: string }>(`/api/v2/projects/${projectId}/work-items/by-display/${encodeURIComponent(id)}`, token)
      } catch (err) {
        toastError((err as { status?: number })?.status === 404 ? `No work item ${id} in this workspace` : apiErrorMessage(err, 'Could not look up that ID'))
        return
      }
      // The blocker BLOCKS this item, so the call is made on the blocker's side.
      await addDependency(projectId, blocker.id, 'BLOCKS', issueId, token)
      setDisplayId('')
      load()
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not add the blocker'))
    } finally {
      setAdding(false)
    }
  }

  async function remove(entry: WorkItemDependencyEntry) {
    try {
      await removeDependency(projectId, issueId, entry.dependencyId, token)
      setBlockers((prev) => prev?.filter((b) => b.dependencyId !== entry.dependencyId) ?? prev)
    } catch (err) {
      toastError(apiErrorMessage(err, `Could not remove ${entry.displayId}`))
    }
  }

  return (
    <PanelSection label="Blocked by">
      {loadFailed ? (
        <p className="text-xs text-muted-foreground">Couldn&rsquo;t load dependencies.</p>
      ) : !blockers ? (
        <Skeleton className="h-4 w-2/3" aria-label="Loading dependencies" />
      ) : (
        <>
          {blockers.length === 0 ? (
            <p className="text-xs text-muted-foreground">Nothing blocks this</p>
          ) : (
            <ul className="divide-y divide-border">
              {blockers.map((b) => (
                <li key={b.dependencyId} className="flex items-center gap-2 py-1.5">
                  <span className="shrink-0 font-mono text-xs text-muted-foreground">{b.displayId}</span>
                  <span className="min-w-0 flex-1 truncate text-sm text-foreground" title={b.title}>
                    {b.title}
                  </span>
                  {canEdit && (
                    <button
                      type="button"
                      onClick={() => void remove(b)}
                      aria-label={`Remove blocker ${b.displayId}`}
                      title={`Remove ${b.displayId}`}
                      className="text-muted-foreground transition-colors hover:text-destructive"
                    >
                      <XIcon className="h-3.5 w-3.5" />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
          {canEdit && (
            <form
              className="mt-2 flex items-center gap-2"
              onSubmit={(e) => {
                e.preventDefault()
                void add()
              }}
            >
              <Input
                aria-label="Add blocker by ID"
                placeholder="COND-12"
                value={displayId}
                onChange={(e) => setDisplayId(e.target.value)}
                className="font-mono text-xs"
              />
              <Button type="submit" size="sm" variant="outline" disabled={adding || !displayId.trim()}>
                Add
              </Button>
            </form>
          )}
        </>
      )}
    </PanelSection>
  )
}
