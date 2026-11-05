'use client'

// The small delivery-tracking marks shared by the Plan tree and the list rows, so a priority or a
// "blocked" reads the same wherever it shows. Color comes from StatusBadge's hue ramp only.

import { Badge } from '@/components/ui/badge'
import { StatusBadge } from '@/components/ui/status-badge'
import { priorityLabel } from '@/lib/delivery'
import type { WorkItemPriority, WorkItemSignals } from '@/types/workItem'

/** "P1 · High", or just "P1" when `compact` (list rows, where width is scarce; the label is the tooltip). */
export function PriorityChip({ priority, compact = false }: { priority?: WorkItemPriority | null; compact?: boolean }) {
  if (!priority) return null
  return (
    <Badge variant="outline" title={priorityLabel(priority)} className="shrink-0 px-2 py-0 text-[11px] font-medium text-muted-foreground">
      {compact ? priority : priorityLabel(priority)}
    </Badge>
  )
}

/** blocked (danger) / needs input (warning) / stale (muted), from the server's derived signals. */
export function SignalBadges({ signals, showStale = true }: { signals?: WorkItemSignals | null; showStale?: boolean }) {
  if (!signals) return null
  const blockers = signals.blockedBy.map((b) => b.displayId).join(', ')
  return (
    <>
      {signals.blocked && (
        <span title={blockers ? `Blocked by ${blockers}` : 'Blocked'} className="shrink-0">
          <StatusBadge status="blocked" label="Blocked" hue="red" />
        </span>
      )}
      {signals.needsInput && (
        <span className="shrink-0">
          <StatusBadge status="needs_input" label="Needs input" hue="amber" />
        </span>
      )}
      {showStale && signals.stale && (
        <span title="In progress with no activity for 7 days" className="shrink-0">
          <StatusBadge status="stale" label="Stale" hue="slate" />
        </span>
      )}
    </>
  )
}
