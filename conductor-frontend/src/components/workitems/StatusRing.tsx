'use client'

import { Check } from 'lucide-react'
import { cn } from '@/lib/utils'
import { statusHue } from '@/lib/workflows'
import { statusHueClasses } from '@/components/ui/status-badge'

export interface StatusRingProps {
  status: string
  category?: string
  className?: string
  /**
   * Text alternative for standalone/read-only uses (e.g. StatusDropdown's no-transitions branch)
   * that aren't already wrapped by a labelled control — renders `role="img"` + `aria-label`/`title`
   * instead of `aria-hidden` so screen readers get the status, not silence.
   */
  label?: string
}

/**
 * The 14px status indicator for list rows and mobile cards: an outlined ring in the status's hue,
 * half-filled for in_progress-category statuses, solid + check for terminal ones. Purely visual —
 * `StatusDropdown` wraps it (via `trigger="ring"`) for the interactive desktop row; mobile renders it
 * standalone next to a separate `StatusDropdown` chip. Reuses the one hue→color source (lib/workflows'
 * `statusHue` + status-badge's `HUE_CLASSES`) rather than adding a second status color map.
 */
export function StatusRing({ status, category, className, label }: StatusRingProps) {
  const hue = statusHue(status, category)
  const { dot, text, borderStrong } = statusHueClasses(hue)
  const isTerminal = category === 'terminal'
  const isInProgress = category === 'in_progress'

  return (
    <span
      {...(label ? { role: 'img', 'aria-label': label, title: label } : { 'aria-hidden': 'true' as const })}
      className={cn(
        'relative inline-flex items-center justify-center w-3.5 h-3.5 shrink-0 rounded-full border-2 overflow-hidden',
        borderStrong,
        isTerminal ? dot : 'bg-transparent',
        text,
        className
      )}
    >
      {isTerminal && <Check className="w-2 h-2 text-surface" strokeWidth={3} />}
      {isInProgress && (
        <span
          className="absolute inset-0 rounded-full"
          style={{ background: 'conic-gradient(currentColor 50%, transparent 50%)' }}
        />
      )}
    </span>
  )
}
