'use client'

import { SearchIcon } from 'lucide-react'
import { Input } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { FilterPills } from '@/components/workitems/FilterPills'
import { SORT_OPTIONS, type ActiveFilter, type SortKey } from '@/components/workitems/listTypes'

/** Filter pills (left) + explicit sort control (right) — sits between the view tabs and the list body. */
export function ListToolbar({
  typeOptions,
  statusOptions,
  tagOptions,
  activeFilters,
  onAddFilter,
  onRemoveFilter,
  sortKey,
  onSortChange,
  showSort = true,
  query,
  onQueryChange,
}: {
  typeOptions: string[]
  statusOptions: { id: string; label: string }[]
  tagOptions?: string[]
  activeFilters: ActiveFilter[]
  onAddFilter: (filter: ActiveFilter) => void
  onRemoveFilter: (kind: ActiveFilter['kind'], value: string) => void
  sortKey: SortKey
  onSortChange: (key: SortKey) => void
  /** Board mode has no row order to sort — list mode only. */
  showSort?: boolean
  /** Text search over title, tags and display id. Omit `onQueryChange` to hide the box. */
  query?: string
  onQueryChange?: (query: string) => void
}) {
  return (
    <div className="flex items-center justify-between gap-3 flex-wrap mb-3">
      {onQueryChange && (
        <div className="relative w-56 shrink-0">
          <SearchIcon className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-subtle" aria-hidden />
          <Input
            type="search"
            aria-label="Search"
            placeholder="Search title, tag or ID"
            value={query ?? ''}
            onChange={(e) => onQueryChange(e.target.value)}
            className="h-8 pl-8 text-xs"
          />
        </div>
      )}
      <FilterPills
        typeOptions={typeOptions}
        statusOptions={statusOptions}
        tagOptions={tagOptions}
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
