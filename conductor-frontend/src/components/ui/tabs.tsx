'use client'

import Link from 'next/link'
import type { ReactNode } from 'react'
import { cn } from '@/lib/utils'

export interface TabItem {
  value: string
  label: ReactNode
  /** When set, the tab renders as a link (route-based tabs); otherwise it's a button. */
  href?: string
}

/**
 * Underline tab bar. Two modes via the same API:
 *  - Route-based: give each item an `href`; pass `value` = the active item's value
 *    (caller derives it from the pathname). Clicking navigates.
 *  - Controlled: omit `href`; pass `value` + `onValueChange` to drive local state.
 */
export function Tabs({
  items,
  value,
  onValueChange,
  className,
}: {
  items: TabItem[]
  value: string
  onValueChange?: (value: string) => void
  className?: string
}) {
  return (
    <div className={cn('flex gap-1 border-b border-border', className)}>
      {items.map((item) => {
        const active = item.value === value
        const classes = cn(
          'px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors',
          active
            ? 'border-primary text-foreground'
            : 'border-transparent text-muted-foreground hover:text-foreground',
        )
        if (item.href) {
          return (
            <Link
              key={item.value}
              href={item.href}
              className={classes}
              aria-current={active ? 'page' : undefined}
            >
              {item.label}
            </Link>
          )
        }
        return (
          <button
            key={item.value}
            type="button"
            onClick={() => onValueChange?.(item.value)}
            className={classes}
            aria-current={active ? 'page' : undefined}
          >
            {item.label}
          </button>
        )
      })}
    </div>
  )
}
