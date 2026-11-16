'use client'

import { useMemo } from 'react'
import { cn } from '@/lib/utils'
import { layoutRail } from './layoutRail'

export const BUBBLE_SIZE = 24
export const BUBBLE_GAP = 4

export interface CountBubbleItem {
  id: string
  /** Top of the highlight, in the document's coordinates. */
  top: number
  /** Messages in the thread: the comment plus its replies. */
  count: number
}

interface Props {
  items: CountBubbleItem[]
  activeId: string | null
  /** Same meaning as `MarginRail`'s offset: how far the document is scrolled relative to this column. */
  offset?: number
  onSelect: (id: string) => void
}

/**
 * The tablet rail: no cards, just a small count bubble per thread in a narrow margin, lined up with its
 * highlight. Overlaps are resolved with the same `layoutRail` as the full rail, so close threads stack
 * instead of sitting on top of each other.
 */
export function CountBubbles({ items, activeId, offset = 0, onSelect }: Props) {
  const positions = useMemo(
    () =>
      layoutRail(
        items.map((i) => ({ id: i.id, top: i.top, height: BUBBLE_SIZE })),
        activeId,
        BUBBLE_GAP
      ).positions,
    [items, activeId]
  )

  return (
    <div className="relative h-full overflow-hidden">
      {items.map((item) => {
        const y = Math.round((positions[item.id] ?? item.top) - offset)
        const active = item.id === activeId
        return (
          <button
            key={item.id}
            type="button"
            data-bubble-id={item.id}
            data-active={active ? 'true' : undefined}
            aria-label={item.count === 1 ? '1 comment on this text' : `${item.count} comments on this text`}
            onClick={() => onSelect(item.id)}
            style={{ transform: `translate3d(0, ${y}px, 0)`, width: BUBBLE_SIZE, height: BUBBLE_SIZE }}
            className={cn(
              'absolute left-1 top-0 flex items-center justify-center rounded-full border text-[11px] font-semibold tabular-nums',
              'focus:outline-none focus-visible:ring-2 focus-visible:ring-ring',
              active
                ? 'border-primary bg-primary text-primary-foreground'
                : 'border-border-strong bg-card text-foreground hover:bg-muted'
            )}
          >
            {item.count}
          </button>
        )
      })}
    </div>
  )
}
