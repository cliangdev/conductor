'use client'

import { useCallback, useEffect, useLayoutEffect, useRef, type ReactNode } from 'react'
import { cn } from '@/lib/utils'
import { layoutRail, RAIL_GAP } from './layoutRail'

export interface MarginRailItem {
  id: string
  /** Where the card wants to sit: the top of its highlight, in the document's coordinates. */
  top: number
  node: ReactNode
}

interface Props {
  items: MarginRailItem[]
  activeId: string | null
  /**
   * How far the document is scrolled (plus any gap between the top of the rail and the top of the
   * document). Cards are laid out in document coordinates, so scrolling only changes this offset and the
   * cards never re-order or jitter.
   */
  offset?: number
  gap?: number
  className?: string
}

/**
 * The aligned comment rail: every card is lined up with its highlight, the active one exactly at its
 * anchor and the rest pushed apart (`layoutRail`). Card heights are measured with a ResizeObserver, and
 * cards are moved with `transform` — at most one update per animation frame, however often the props
 * change or the cards resize — so scrolling stays smooth. Viewer-agnostic: callers supply document-space
 * tops from whatever they highlight in.
 */
export function MarginRail({ items, activeId, offset = 0, gap = RAIL_GAP, className }: Props) {
  const elements = useRef(new Map<string, HTMLDivElement>())
  const heights = useRef(new Map<string, number>())
  const latest = useRef({ items, activeId, offset, gap })
  const frame = useRef<number | null>(null)
  const observer = useRef<ResizeObserver | null>(null)

  const apply = useCallback(() => {
    frame.current = null
    const { items, activeId, offset, gap } = latest.current
    const { positions } = layoutRail(
      items.map((i) => ({ id: i.id, top: i.top, height: heights.current.get(i.id) ?? 0 })),
      activeId,
      gap
    )
    for (const [id, el] of elements.current) {
      const y = positions[id]
      if (y === undefined) continue
      el.style.transform = `translate3d(0, ${Math.round(y - offset)}px, 0)`
      el.style.visibility = 'visible'
    }
  }, [])

  const schedule = useCallback(() => {
    if (frame.current !== null) return
    frame.current = requestAnimationFrame(apply)
  }, [apply])

  useEffect(() => {
    if (typeof ResizeObserver === 'undefined') return
    const ro = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const id = (entry.target as HTMLElement).dataset.railId
        if (id) heights.current.set(id, Math.round(entry.target.getBoundingClientRect().height))
      }
      schedule()
    })
    observer.current = ro
    elements.current.forEach((el) => ro.observe(el))
    return () => {
      ro.disconnect()
      observer.current = null
    }
  }, [schedule])

  // New props arrive at most once per frame (the viewer reports its scroll once per frame), so they are
  // applied in the same commit: cards that were just added are measured first and never flash at the
  // top. Resizes of a card (reply typed, "Show more") come through the ResizeObserver, one per frame.
  useLayoutEffect(() => {
    latest.current = { items, activeId, offset, gap }
    for (const i of items) {
      if (!heights.current.has(i.id)) {
        const el = elements.current.get(i.id)
        if (el) heights.current.set(i.id, Math.round(el.getBoundingClientRect().height))
      }
    }
    if (frame.current !== null) {
      cancelAnimationFrame(frame.current)
      frame.current = null
    }
    apply()
  }, [items, activeId, offset, gap, apply])

  useEffect(
    () => () => {
      if (frame.current !== null) cancelAnimationFrame(frame.current)
    },
    []
  )

  return (
    <div className={cn('relative h-full overflow-hidden', className)}>
      {items.map((item) => (
        <div
          key={item.id}
          data-rail-id={item.id}
          data-active={item.id === activeId ? 'true' : undefined}
          ref={(el) => {
            if (el) {
              elements.current.set(item.id, el)
              observer.current?.observe(el)
            } else {
              const prev = elements.current.get(item.id)
              if (prev) observer.current?.unobserve(prev)
              elements.current.delete(item.id)
            }
          }}
          // Hidden until placed, so a new card never flashes at the top of the rail.
          style={{ visibility: 'hidden' }}
          className="absolute inset-x-0 top-0 will-change-transform"
        >
          {item.node}
        </div>
      ))}
    </div>
  )
}
