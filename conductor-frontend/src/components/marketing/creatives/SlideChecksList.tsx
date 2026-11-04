'use client'

// Slide checks by rule: errors first, then warnings. `withLocation` adds "Slide 3 · 9:16" for checks that
// come from the render job, where one list can cover several slides and ratios.

import { cn } from '@/lib/utils'
import { ruleLabel, type SlideshowCheck } from './slideText'

const RATIO_LABEL: Record<string, string> = { '9x16': '9:16', '4x5': '4:5' }

export function SlideChecksList({ checks, withLocation, className }: { checks: readonly SlideshowCheck[]; withLocation?: boolean; className?: string }) {
  const ordered = [...checks].sort((a, b) => Number(b.severity === 'error') - Number(a.severity === 'error'))
  return (
    <ul className={cn('space-y-1.5', className)} data-testid="slide-checks">
      {ordered.map((c, i) => (
        <li
          key={`${c.slide}-${c.ratio}-${c.rule}-${i}`}
          data-testid={`slide-check-${c.rule}`}
          data-severity={c.severity}
          className="text-sm"
        >
          <span className={cn('font-medium', c.severity === 'error' ? 'text-status-failed' : 'text-status-progress')}>
            {c.severity === 'error' ? 'Error' : 'Warning'} · {ruleLabel(c.rule)}
          </span>
          {withLocation && c.slide > 0 && (
            <span className="text-muted-foreground">
              {' '}
              · Slide {c.slide}, {RATIO_LABEL[c.ratio] ?? c.ratio}
            </span>
          )}
          <span className="block text-foreground">{c.message}</span>
        </li>
      ))}
    </ul>
  )
}
