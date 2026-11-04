'use client'

import { useEffect, useRef } from 'react'
import { Maximize2, Minimize2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

interface Props {
  /** The document is in full view, so the button closes it. */
  open: boolean
  onToggle: () => void
  /**
   * Take focus when it mounts, if nothing else has it. The button unmounts on every switch (the page
   * copy and the full view copy are different elements), so this keeps keyboard focus on the control
   * the person just used. Focus that's elsewhere, such as on a tab, is never taken.
   */
  claimFocus?: boolean
  className?: string
}

/**
 * The expand / collapse control in a document's top-right corner: Full view on the page, Exit full
 * view inside it, in the same spot (like a video player). Icon-only, so the label and shortcut are in
 * its accessible name and tooltip. Not shown on a phone, where the document already fills the screen.
 */
export function FullViewToggle({ open, onToggle, claimFocus = false, className }: Props) {
  const ref = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!claimFocus) return
    const focused = document.activeElement
    if (!focused || focused === document.body) ref.current?.focus()
    // Mount only: a re-render must not pull focus back.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const label = open ? 'Exit full view' : 'Full view'
  const Icon = open ? Minimize2 : Maximize2
  return (
    <Button
      ref={ref}
      variant="outline"
      size="sm"
      className={cn('h-8 w-8 p-0 bg-background text-muted-foreground hover:text-foreground', !open && 'hidden sm:inline-flex', className)}
      onClick={onToggle}
      aria-label={label}
      aria-keyshortcuts={open ? 'Escape' : 'F'}
      title={`${label} (${open ? 'Esc' : 'F'})`}
    >
      <Icon className="h-4 w-4" />
    </Button>
  )
}

/** True when a plain F press should open full view: no modifier, and not typed into a field or a menu. */
export function isFullViewShortcut(e: KeyboardEvent): boolean {
  if (e.defaultPrevented || e.repeat || e.metaKey || e.ctrlKey || e.altKey) return false
  if (e.key !== 'f' && e.key !== 'F') return false
  const target = e.target as HTMLElement | null
  return !target?.closest?.(
    'input,textarea,select,[contenteditable="true"],[role="dialog"],[role="menu"],[role="listbox"],[role="combobox"]',
  )
}
