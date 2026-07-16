'use client'

// The Work Item list's client-side state: type/status filter pills, sort key (persisted per
// workflow), row selection, keyboard navigation, and command-palette integration for bulk actions.
// Grouping (by workflow status, in workflow order) and sorting-within-group are computed here too,
// since they share the same filtered/sorted data the keyboard nav walks over.

import { useEffect, useMemo, useRef, useState } from 'react'
import { registerPaletteActions } from '@/components/layout/CommandPalette'
import { usePersistedState } from '@/lib/persisted'
import { categoriesForView, humanizeId, statusMeta } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'
import type { ActiveFilter, IssueWithReviewers, ListView, SortKey, WorkItemGroupData } from '@/components/workitems/listTypes'

function sortComparator(sortKey: SortKey) {
  switch (sortKey) {
    case 'title':
      return (a: IssueWithReviewers, b: IssueWithReviewers) => a.title.localeCompare(b.title)
    case 'created':
      return (a: IssueWithReviewers, b: IssueWithReviewers) =>
        new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
    case 'updated':
    default:
      return (a: IssueWithReviewers, b: IssueWithReviewers) =>
        new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
  }
}

/** Checks a live DOM signal (not React state) so keyboard nav yields to any open menu/dialog/input. */
function isInteractiveContextOpen(): boolean {
  if (typeof document === 'undefined') return false
  const active = document.activeElement
  if (active) {
    const tag = active.tagName
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || (active as HTMLElement).isContentEditable) {
      return true
    }
  }
  return !!document.querySelector('[data-radix-popper-content-wrapper], [role="dialog"]')
}

/**
 * Radix's `DropdownMenuTrigger` opens on `pointerdown`, not `click` (see
 * `@radix-ui/react-dropdown-menu`'s trigger implementation) — a plain `el.click()`, which is what a
 * programmatic keyboard shortcut or command-palette action would normally reach for, never
 * satisfies that listener and silently no-ops. Dispatching a real `pointerdown` first opens the
 * trigger exactly as a mouse click would.
 */
function openMenuTrigger(el: HTMLButtonElement | null | undefined) {
  if (!el) return
  el.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }))
  el.click()
}

export interface UseWorkItemListStateArgs {
  storageKeyPrefix: string
  view: ListView
  issuesInView: IssueWithReviewers[]
  workflowView: WorkflowView | undefined
  /** REVIEWERs can't mutate Work Items — gates selection, the X shortcut, and the palette's Selection group. */
  canEdit: boolean
  /** Bulk-bar trigger buttons — opened by the "S"/"A" row shortcuts and the command palette's Selection actions. */
  bulkStatusTriggerRef: React.RefObject<HTMLButtonElement | null>
  bulkAssignTriggerRef: React.RefObject<HTMLButtonElement | null>
}

export function useWorkItemListState({
  storageKeyPrefix,
  view,
  issuesInView,
  workflowView,
  canEdit,
  bulkStatusTriggerRef,
  bulkAssignTriggerRef,
}: UseWorkItemListStateArgs) {
  const [activeFilters, setActiveFilters] = useState<ActiveFilter[]>([])

  const [sortKey, setSortKey] = usePersistedState<SortKey>(
    `wv_sort_${storageKeyPrefix}`,
    (v): v is SortKey => v === 'updated' || v === 'created' || v === 'title',
    'updated'
  )

  function addFilter(filter: ActiveFilter) {
    setActiveFilters((prev) => (prev.some((f) => f.kind === filter.kind && f.value === filter.value) ? prev : [...prev, filter]))
  }

  function removeFilter(kind: ActiveFilter['kind'], value: string) {
    setActiveFilters((prev) => prev.filter((f) => !(f.kind === kind && f.value === value)))
  }

  function clearFilters() {
    setActiveFilters([])
  }

  // Switching tabs (Active/Done/All) changes which statuses are even selectable — prune any status
  // pill that no longer applies (e.g. a Draft pill surviving onto the Done tab), which otherwise
  // blanks the list with no recoverable affordance. Type pills are unaffected.
  useEffect(() => {
    const validCategories = new Set(categoriesForView(view) as string[])
    const validStatusIds = new Set(
      (workflowView?.statuses ?? []).filter((s) => validCategories.has(s.category)).map((s) => s.id)
    )
    setActiveFilters((prev) => {
      const next = prev.filter((f) => f.kind !== 'status' || validStatusIds.has(f.value))
      return next.length === prev.length ? prev : next
    })
  }, [view, workflowView])

  const typeValues = activeFilters.filter((f) => f.kind === 'type').map((f) => f.value)
  const statusValues = activeFilters.filter((f) => f.kind === 'status').map((f) => f.value)
  const typeValuesKey = typeValues.join(',')
  const statusValuesKey = statusValues.join(',')

  const filteredIssues = useMemo(
    () =>
      issuesInView.filter((issue) => {
        if (typeValues.length > 0 && !typeValues.includes(issue.type)) return false
        if (statusValues.length > 0 && !statusValues.includes(issue.status)) return false
        return true
      }),
    // typeValues/statusValues are recomputed every render from activeFilters — their joined keys
    // are the stable identity to depend on instead.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [issuesInView, typeValuesKey, statusValuesKey]
  )

  // Group by workflow status, in workflow-defined order, within the current tab's categories.
  // Statuses with no matching Work Items are omitted; any Work Item whose status isn't in the
  // Workflow view (stale cache, in-flight load) falls into a trailing "Other" group rather than
  // silently vanishing.
  const groups = useMemo<WorkItemGroupData[]>(() => {
    const categories = categoriesForView(view) as string[]
    const comparator = sortComparator(sortKey)
    const columns = (workflowView?.statuses ?? []).filter((s) => categories.includes(s.category))
    const byStatus = new Map<string, IssueWithReviewers[]>()
    for (const col of columns) byStatus.set(col.id, [])
    const other: IssueWithReviewers[] = []
    for (const issue of filteredIssues) {
      const list = byStatus.get(issue.status)
      if (list) list.push(issue)
      else other.push(issue)
    }
    const result: WorkItemGroupData[] = []
    for (const col of columns) {
      const items = byStatus.get(col.id) ?? []
      if (items.length === 0) continue
      result.push({ statusId: col.id, label: col.label ?? humanizeId(col.id), category: col.category, items: [...items].sort(comparator) })
    }
    if (other.length > 0) {
      const category = statusMeta(workflowView, other[0].status).category
      result.push({ statusId: '__other__', label: 'Other', category, items: [...other].sort(comparator) })
    }
    return result
  }, [filteredIssues, workflowView, view, sortKey])

  const flatIds = useMemo(() => groups.flatMap((g) => g.items.map((i) => i.id)), [groups])

  // ── Selection + keyboard nav ────────────────────────────────────────────
  // Rows are real <a> links with roving tabIndex — J/K move actual DOM focus (el.focus()) between
  // them, Enter is native link activation (no JS involved), and X/S/A act on whichever row
  // document.activeElement reports, not a React state that could drift out of sync with real focus.
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [focusedId, setFocusedId] = useState<string | null>(null)
  const flatIdsKey = flatIds.join(',')

  // Drop any selection/focus that fell out of view (filtered away, status changed, etc).
  useEffect(() => {
    const idSet = new Set(flatIds)
    setSelected((prev) => {
      const next = new Set([...prev].filter((id) => idSet.has(id)))
      return next.size === prev.size ? prev : next
    })
    setFocusedId((prev) => (prev && idSet.has(prev) ? prev : null))
    // flatIds is a new array every render — flatIdsKey is the stable identity to depend on.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [flatIdsKey])

  useEffect(() => {
    if (!focusedId) return
    const el = document.getElementById(`wi-row-${focusedId}`)
    if (typeof el?.scrollIntoView === 'function') el.scrollIntoView({ block: 'nearest' })
  }, [focusedId])

  // The roving tab stop: the last-focused row, defaulting to the first row so the list always
  // exposes exactly one Tab stop, even before any row has received real focus.
  const tabStopId = focusedId ?? flatIds[0] ?? null

  function toggleSelect(id: string) {
    if (!canEdit) return
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  function clearSelection() {
    setSelected(new Set())
  }

  /** Replaces the selection wholesale — used to leave only the failed ids selected after a partial bulk-mutation failure. */
  function setSelection(ids: Iterable<string>) {
    setSelected(new Set(ids))
  }

  // One in-flight bulk mutation at a time — disables the bulk bar's buttons and the palette's
  // Selection actions so a second bulk change can't be submitted before the first settles.
  const [bulkInFlight, setBulkInFlight] = useState(false)

  const statusTriggerRefs = useRef(new Map<string, HTMLButtonElement>())
  const assigneeTriggerRefs = useRef(new Map<string, HTMLButtonElement>())
  const rowLinkRefs = useRef(new Map<string, HTMLAnchorElement>())

  function registerStatusTriggerRef(id: string) {
    return (el: HTMLButtonElement | null) => {
      if (el) statusTriggerRefs.current.set(id, el)
      else statusTriggerRefs.current.delete(id)
    }
  }

  function registerAssigneeTriggerRef(id: string) {
    return (el: HTMLButtonElement | null) => {
      if (el) assigneeTriggerRefs.current.set(id, el)
      else assigneeTriggerRefs.current.delete(id)
    }
  }

  function registerRowLinkRef(id: string) {
    return (el: HTMLAnchorElement | null) => {
      if (el) rowLinkRefs.current.set(id, el)
      else rowLinkRefs.current.delete(id)
    }
  }

  function onContainerKeyDown(e: React.KeyboardEvent) {
    if (e.metaKey || e.ctrlKey || e.altKey) return
    if (isInteractiveContextOpen()) return
    if (flatIds.length === 0) return

    const activeRowId = (document.activeElement as HTMLElement | null)?.getAttribute('data-row-id')
    const currentId = activeRowId ?? tabStopId
    const idx = currentId ? flatIds.indexOf(currentId) : -1

    switch (e.key) {
      case 'j':
      case 'ArrowDown': {
        e.preventDefault()
        const nextId = flatIds[Math.min(idx + 1, flatIds.length - 1)] ?? flatIds[0]
        rowLinkRefs.current.get(nextId)?.focus()
        break
      }
      case 'k':
      case 'ArrowUp': {
        e.preventDefault()
        const prevId = flatIds[Math.max(idx - 1, 0)] ?? flatIds[0]
        rowLinkRefs.current.get(prevId)?.focus()
        break
      }
      case 'x':
      case 'X': {
        if (!currentId || !canEdit) return
        e.preventDefault()
        toggleSelect(currentId)
        break
      }
      case 's':
      case 'S': {
        if (!currentId) return
        e.preventDefault()
        openMenuTrigger(statusTriggerRefs.current.get(currentId))
        break
      }
      case 'a':
      case 'A': {
        if (!currentId) return
        e.preventDefault()
        openMenuTrigger(assigneeTriggerRefs.current.get(currentId))
        break
      }
      case 'Escape': {
        if (selected.size > 0) {
          e.preventDefault()
          clearSelection()
        }
        break
      }
      default:
        break
    }
  }

  // Command palette integration: while a selection is active, register "Change status" / "Assign"
  // actions that just open the bulk bar's own trigger — avoids a second status/member list.
  // REVIEWERs never reach a nonzero selection (toggleSelect no-ops for them), so this stays absent
  // for them too.
  const selectedCount = selected.size
  useEffect(() => {
    if (selectedCount === 0 || !canEdit) return
    return registerPaletteActions({
      group: 'Selection',
      actions: [
        {
          id: 'bulk-change-status',
          label: `Change status (${selectedCount} selected)`,
          perform: () => {
            if (!bulkInFlight) openMenuTrigger(bulkStatusTriggerRef.current)
          },
        },
        {
          id: 'bulk-assign',
          label: `Assign (${selectedCount} selected)`,
          perform: () => {
            if (!bulkInFlight) openMenuTrigger(bulkAssignTriggerRef.current)
          },
        },
      ],
    })
  }, [selectedCount, canEdit, bulkInFlight, bulkStatusTriggerRef, bulkAssignTriggerRef])

  return {
    activeFilters,
    addFilter,
    removeFilter,
    clearFilters,
    sortKey,
    setSortKey,
    groups,
    filteredIssues,
    filteredCount: filteredIssues.length,
    selected,
    focusedId,
    tabStopId,
    setFocusedId,
    toggleSelect,
    clearSelection,
    setSelection,
    bulkInFlight,
    setBulkInFlight,
    registerStatusTriggerRef,
    registerAssigneeTriggerRef,
    registerRowLinkRef,
    onContainerKeyDown,
  }
}
