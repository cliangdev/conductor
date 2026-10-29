'use client'

import type { ReactNode } from 'react'
import { XIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'

/**
 * The one selection action bar: pinned to the bottom of the viewport, "N selected" on the left, the
 * page's actions in the middle (children) and Clear on the right. Used by Media and Posts.
 */
export function FloatingBulkBar({
  count,
  hiddenCount = 0,
  onClear,
  children,
}: {
  count: number
  /** Selected but hidden by a search/filter; shown as a quiet note. */
  hiddenCount?: number
  onClear?: () => void
  children?: ReactNode
}) {
  return (
    <div
      role="toolbar"
      aria-label="Selection actions"
      className="fixed inset-x-4 bottom-4 z-30 mx-auto flex max-w-4xl flex-wrap items-center gap-2 rounded-lg border border-border bg-popover px-4 py-2.5 shadow-lg"
    >
      <span className="text-sm font-medium text-foreground">{count} selected</span>
      {hiddenCount > 0 && <span className="text-xs text-muted-foreground">({hiddenCount} hidden by filters)</span>}
      {children}
      {onClear && (
        <Button type="button" variant="ghost" size="sm" className="ml-auto" onClick={onClear}>
          <XIcon className="mr-1 h-3.5 w-3.5" aria-hidden />
          Clear
        </Button>
      )}
    </div>
  )
}
