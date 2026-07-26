'use client'

import { MessageSquare } from 'lucide-react'
import { cn } from '@/lib/utils'

/**
 * "N comments" as an icon + count — the one way this is drawn, across list rows, board cards, Work
 * Item document tabs, and the comment gutter.
 *
 * Renders nothing for a zero or absent count, so callers don't repeat the guard. Colour is inherited
 * (`currentColor`), so a caller sets tone via `className` rather than the primitive knowing about
 * active/muted states.
 */
export function CommentCount({
  count,
  className,
}: {
  count: number | null | undefined
  className?: string
}) {
  if (count == null || count <= 0) return null

  return (
    <span
      className={cn('inline-flex items-center gap-0.5 text-xs font-medium leading-none', className)}
    >
      <MessageSquare className="h-3 w-3 shrink-0" aria-hidden="true" />
      {count}
    </span>
  )
}
