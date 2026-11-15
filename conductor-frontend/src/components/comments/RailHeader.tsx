'use client'

import { useEffect } from 'react'
import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react'
import { cn } from '@/lib/utils'

export type RailFilter = 'open' | 'resolved' | 'mine'

const FILTERS: { id: RailFilter; label: string }[] = [
  { id: 'open', label: 'Open' },
  { id: 'resolved', label: 'Resolved' },
  { id: 'mine', label: 'Mine' },
]

interface Props {
  filter: RailFilter
  onFilterChange: (filter: RailFilter) => void
  /** 1-based place of the focused thread among the open, placed ones, or null when none is focused. */
  index: number | null
  total: number
  onPrev: () => void
  onNext: () => void
  /** Listen for `j` / `k` on the document. Turn off when the rail is not on screen. */
  keyboard?: boolean
}

function isTyping(target: EventTarget | null): boolean {
  const el = target as HTMLElement | null
  if (!el || !el.tagName) return false
  return el.tagName === 'INPUT' || el.tagName === 'TEXTAREA' || el.tagName === 'SELECT' || el.isContentEditable
}

/** Open / Resolved / Mine chips, "‹ 2 of 7 ›" navigation between open threads, and the `j` / `k` keys. */
export function RailHeader({ filter, onFilterChange, index, total, onPrev, onNext, keyboard = true }: Props) {
  useEffect(() => {
    if (!keyboard) return
    function onKey(e: KeyboardEvent) {
      if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented) return
      if (isTyping(e.target)) return
      if (e.key === 'j') {
        e.preventDefault()
        onNext()
      } else if (e.key === 'k') {
        e.preventDefault()
        onPrev()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [keyboard, onNext, onPrev])

  return (
    <div role="toolbar" aria-label="Comment filters" className="flex flex-wrap items-center gap-1.5">
      {FILTERS.map((f) => (
        <button
          key={f.id}
          type="button"
          aria-pressed={filter === f.id}
          onClick={() => onFilterChange(f.id)}
          className={cn(
            'rounded-full border px-2.5 py-0.5 text-xs transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
            filter === f.id
              ? 'border-primary bg-accent font-medium text-primary'
              : 'border-border-strong bg-card text-muted-foreground hover:bg-muted'
          )}
        >
          {f.label}
        </button>
      ))}
      <span className="ml-1 inline-flex items-center gap-0.5 text-xs tabular-nums text-muted-foreground">
        <button
          type="button"
          aria-label="Previous thread"
          disabled={total === 0}
          onClick={onPrev}
          className="rounded p-1 hover:bg-muted disabled:opacity-40 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ChevronLeftIcon className="h-3.5 w-3.5" />
        </button>
        <span data-testid="rail-position">
          {index === null ? '–' : index} of {total}
        </span>
        <button
          type="button"
          aria-label="Next thread"
          disabled={total === 0}
          onClick={onNext}
          className="rounded p-1 hover:bg-muted disabled:opacity-40 focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <ChevronRightIcon className="h-3.5 w-3.5" />
        </button>
      </span>
    </div>
  )
}
