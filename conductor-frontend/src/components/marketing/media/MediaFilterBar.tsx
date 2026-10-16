'use client'

// The search box, filter chips and kind tabs above a media grid. Driven by useMediaFilters so the
// Media library page and the media chooser filter identically.

import type { ReactNode } from 'react'
import { Input } from '@/components/ui/input'
import { Tabs } from '@/components/ui/tabs'
import { cn } from '@/lib/utils'
import type { KindTab, MediaChip, MediaFilters } from './useMediaFilters'

const KIND_LABEL: Record<KindTab, string> = { ALL: 'All', IMAGE: 'Images', VIDEO: 'Video', AUDIO: 'Audio' }
const CHIP_LABEL: Record<MediaChip, string> = {
  unchecked: 'Unchecked',
  blocked: 'Blocked',
  ai: 'AI-generated',
  onPosts: 'On posts',
  unused: 'Unused',
}

export function FilterChip({ pressed, onClick, children }: { pressed: boolean; onClick: () => void; children: ReactNode }) {
  return (
    <button
      type="button"
      aria-pressed={pressed}
      onClick={onClick}
      className={cn(
        'rounded-full border px-3 py-1 text-[13px] transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring',
        pressed ? 'border-accent bg-accent-soft text-foreground' : 'border-border text-muted-foreground hover:bg-surface-3',
      )}
    >
      {children}
    </button>
  )
}

export interface MediaFilterBarProps {
  filters: MediaFilters
  chips: MediaChip[]
  kinds: KindTab[]
  /** Show the Newest | Name toggle. */
  sortable?: boolean
  /** Right-aligned content on the search row (e.g. the library's Upload button). */
  trailing?: ReactNode
}

export function MediaFilterBar({ filters, chips, kinds, sortable, trailing }: MediaFilterBarProps) {
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <Input
          type="search"
          aria-label="Search by label"
          placeholder="Search labels…"
          value={filters.query}
          onChange={(e) => filters.setQuery(e.target.value)}
          className="h-9 max-w-xs"
        />
        {chips.map((c) => (
          <FilterChip key={c} pressed={filters.chips[c]} onClick={() => filters.toggleChip(c)}>
            {CHIP_LABEL[c]}
          </FilterChip>
        ))}
        {sortable && (
          <div role="group" aria-label="Sort" className="ml-auto flex items-center gap-1 text-[13px]">
            <span className="text-muted-foreground">Sort</span>
            <FilterChip pressed={filters.sort === 'newest'} onClick={() => filters.setSort('newest')}>
              Newest
            </FilterChip>
            <FilterChip pressed={filters.sort === 'name'} onClick={() => filters.setSort('name')}>
              Name
            </FilterChip>
          </div>
        )}
        {trailing && <div className="ml-auto">{trailing}</div>}
      </div>
      {kinds.length > 1 && (
        <Tabs
          ariaLabel="Media type"
          value={filters.kind}
          onValueChange={(v) => filters.setKind(v as KindTab)}
          items={kinds.map((k) => ({ value: k, label: KIND_LABEL[k] }))}
        />
      )}
    </div>
  )
}
