'use client'

// Checkbox + shift-range selection over a list that filters. Selection is kept as ids (not indexes)
// because indexes shift when a search or filter changes; the range anchor is an id for the same reason.
// Ids hidden by a filter stay selected (clearing the filter brings them back); callers act on
// `selectedVisible` so nothing hidden is ever acted on unseen.

import { useCallback, useMemo, useRef, useState } from 'react'

export function useRangeSelection(visibleIds: string[]) {
  const [selected, setSelected] = useState<string[]>([])
  const anchor = useRef<string | null>(null)

  /** Plain click toggles `id` and moves the anchor; with `range`, selects anchor..id among visible ids. */
  const select = useCallback(
    (id: string, range = false) => {
      const from = anchor.current == null ? -1 : visibleIds.indexOf(anchor.current)
      const to = visibleIds.indexOf(id)
      if (range && from >= 0 && to >= 0) {
        const [a, b] = [Math.min(from, to), Math.max(from, to)]
        const ids = visibleIds.slice(a, b + 1)
        setSelected((prev) => [...prev, ...ids.filter((x) => !prev.includes(x))])
      } else {
        setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]))
        anchor.current = id
      }
    },
    [visibleIds],
  )

  const clear = useCallback(() => {
    setSelected([])
    anchor.current = null
  }, [])

  /** Adds ids to the selection (e.g. "Select set"). */
  const add = useCallback((ids: string[]) => {
    setSelected((prev) => [...new Set([...prev, ...ids])])
  }, [])

  /** Drops one id (e.g. the item was deleted). */
  const remove = useCallback((id: string) => {
    setSelected((prev) => prev.filter((x) => x !== id))
    if (anchor.current === id) anchor.current = null
  }, [])

  /** Keeps only ids that still exist in `existingIds`. */
  const prune = useCallback((existingIds: Iterable<string>) => {
    const keep = new Set(existingIds)
    setSelected((prev) => {
      const next = prev.filter((x) => keep.has(x))
      return next.length === prev.length ? prev : next
    })
    if (anchor.current && !keep.has(anchor.current)) anchor.current = null
  }, [])

  const selectedSet = useMemo(() => new Set(selected), [selected])
  const visibleSet = useMemo(() => new Set(visibleIds), [visibleIds])
  const selectedVisible = useMemo(() => selected.filter((id) => visibleSet.has(id)), [selected, visibleSet])

  return {
    selected,
    selectedVisible,
    hiddenCount: selected.length - selectedVisible.length,
    isSelected: (id: string) => selectedSet.has(id),
    select,
    clear,
    add,
    remove,
    prune,
  }
}
