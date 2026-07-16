'use client'

import Link from 'next/link'
import { useRef } from 'react'
import type { KeyboardEvent, ReactNode } from 'react'
import { cn } from '@/lib/utils'

export interface TabItem {
  value: string
  label: ReactNode
  /** When set, the tab renders as a link (route-based tabs); otherwise it's a button. */
  href?: string
  /** Optional count pill rendered after the label (e.g. result counts). */
  count?: number
}

/**
 * The one underline tab-bar implementation used across the app. Two modes via the same API:
 *  - Route-based: give each item an `href`; pass `value` = the active item's value
 *    (caller derives it from the pathname). Clicking navigates.
 *  - Controlled: omit `href`; pass `value` + `onValueChange` to drive local state. Implements the
 *    WAI-ARIA tabs pattern — roving tabindex plus ArrowLeft/ArrowRight to move focus between tabs.
 *
 * Both modes render `role="tablist"`/`role="tab"` and `aria-selected`/`aria-current="page"` on the
 * active tab. Pass `getPanelId` (controlled mode only) to wire `aria-controls` to a matching tabpanel.
 */
export function Tabs({
  items,
  value,
  onValueChange,
  getPanelId,
  ariaLabel,
  className,
}: {
  items: TabItem[]
  value: string
  onValueChange?: (value: string) => void
  /** Maps a tab's value to its tabpanel id, wired as `aria-controls`. */
  getPanelId?: (value: string) => string
  ariaLabel?: string
  className?: string
}) {
  const tabRefs = useRef(new Map<string, HTMLButtonElement>())

  function handleKeyDown(e: KeyboardEvent, currentValue: string) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return
    e.preventDefault()
    const idx = items.findIndex((i) => i.value === currentValue)
    if (idx < 0) return
    const delta = e.key === 'ArrowRight' ? 1 : -1
    const next = items[(idx + delta + items.length) % items.length]
    onValueChange?.(next.value)
    tabRefs.current.get(next.value)?.focus()
  }

  return (
    <div
      role="tablist"
      aria-label={ariaLabel}
      className={cn(
        'flex items-center gap-1 border-b border-border overflow-x-auto overflow-y-hidden',
        className,
      )}
    >
      {items.map((item) => {
        const active = item.value === value
        const classes = cn(
          'relative px-3 py-2 text-sm font-medium whitespace-nowrap transition-colors',
          active ? 'text-foreground' : 'text-muted-foreground hover:text-foreground',
        )
        const content = (
          <span className="inline-flex items-center gap-1.5">
            {item.label}
            {item.count !== undefined && (
              <span
                className={cn(
                  'inline-flex items-center justify-center min-w-[1.25rem] h-5 px-1.5 rounded-full text-xs font-medium',
                  active ? 'bg-foreground/10 text-foreground' : 'bg-muted text-muted-foreground',
                )}
              >
                {item.count}
              </span>
            )}
          </span>
        )
        const underline = active && (
          <span
            aria-hidden="true"
            className="absolute left-0 right-0 -bottom-px h-0.5 bg-primary rounded-full"
          />
        )

        if (item.href) {
          return (
            <Link
              key={item.value}
              href={item.href}
              role="tab"
              aria-selected={active}
              aria-current={active ? 'page' : undefined}
              className={classes}
            >
              {content}
              {underline}
            </Link>
          )
        }
        return (
          <button
            key={item.value}
            ref={(el) => {
              if (el) tabRefs.current.set(item.value, el)
              else tabRefs.current.delete(item.value)
            }}
            role="tab"
            type="button"
            aria-selected={active}
            aria-controls={getPanelId?.(item.value)}
            tabIndex={active ? 0 : -1}
            onClick={() => onValueChange?.(item.value)}
            onKeyDown={(e) => handleKeyDown(e, item.value)}
            className={classes}
          >
            {content}
            {underline}
          </button>
        )
      })}
    </div>
  )
}
