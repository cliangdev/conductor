'use client'

import { Select } from '@/components/ui/select'
import { FilterPills } from '@/components/workitems/FilterPills'
import { SORT_OPTIONS, type ActiveFilter, type SortKey } from '@/components/workitems/listTypes'

/** Filter pills (left) + explicit sort control (right) — sits between the view tabs and the list body. */
export function ListToolbar({
  typeOptions,
  statusOptions,
  activeFilters,
  onAddFilter,
  onRemoveFilter,
  sortKey,
  onSortChange,
  showSort = true,
}: {
  typeOptions: string[]
  statusOptions: { id: string; label: string }[]
  activeFilters: ActiveFilter[]
  onAddFilter: (filter: ActiveFilter) => void
  onRemoveFilter: (kind: ActiveFilter['kind'], value: string) => void
  sortKey: SortKey
  onSortChange: (key: SortKey) => void
  /** Board mode has no row order to sort — list mode only. */
  showSort?: boolean
}) {
  return (
    <div className="flex items-center justify-between gap-3 flex-wrap mb-3">
      <FilterPills
        typeOptions={typeOptions}
        statusOptions={statusOptions}
        activeFilters={activeFilters}
        onAdd={onAddFilter}
        onRemove={onRemoveFilter}
      />
      {showSort && (
        <div className="flex items-center gap-2 shrink-0">
          <label htmlFor="wi-sort" className="text-xs text-muted-foreground">
            Sort:
          </label>
          <Select
            id="wi-sort"
            value={sortKey}
            onChange={(e) => onSortChange(e.target.value as SortKey)}
            className="h-8 w-28 text-xs"
          >
            {SORT_OPTIONS.map((o) => (
              <option key={o.key} value={o.key}>
                {o.label}
              </option>
            ))}
          </Select>
        </div>
      )}
    </div>
  )
}
