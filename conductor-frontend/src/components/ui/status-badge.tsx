import { cn } from '@/lib/utils'
import { statusHue, statusLabel, type StatusHue } from '@/lib/workflows'

export type { StatusHue }
export { statusHue }

// Static class lookup, not interpolated — Tailwind can't see a `` `bg-status-${hue}` `` template
// at build time and would drop the class.
const HUE_CLASSES: Record<StatusHue, { bg: string; text: string; dot: string; border: string; borderStrong: string; ring: string }> = {
  gray: {
    bg: 'bg-status-draft/10',
    text: 'text-status-draft',
    dot: 'bg-status-draft',
    border: 'border-status-draft/30',
    borderStrong: 'border-status-draft',
    ring: 'ring-status-draft',
  },
  blue: {
    bg: 'bg-status-review/10',
    text: 'text-status-review',
    dot: 'bg-status-review',
    border: 'border-status-review/30',
    borderStrong: 'border-status-review',
    ring: 'ring-status-review',
  },
  amber: {
    bg: 'bg-status-progress/10',
    text: 'text-status-progress',
    dot: 'bg-status-progress',
    border: 'border-status-progress/30',
    borderStrong: 'border-status-progress',
    ring: 'ring-status-progress',
  },
  violet: {
    bg: 'bg-status-code-review/10',
    text: 'text-status-code-review',
    dot: 'bg-status-code-review',
    border: 'border-status-code-review/30',
    borderStrong: 'border-status-code-review',
    ring: 'ring-status-code-review',
  },
  teal: {
    bg: 'bg-status-approved/10',
    text: 'text-status-approved',
    dot: 'bg-status-approved',
    border: 'border-status-approved/30',
    borderStrong: 'border-status-approved',
    ring: 'ring-status-approved',
  },
  green: {
    bg: 'bg-status-done/10',
    text: 'text-status-done',
    dot: 'bg-status-done',
    border: 'border-status-done/30',
    borderStrong: 'border-status-done',
    ring: 'ring-status-done',
  },
  slate: {
    bg: 'bg-status-closed/10',
    text: 'text-status-closed',
    dot: 'bg-status-closed',
    border: 'border-status-closed/30',
    borderStrong: 'border-status-closed',
    ring: 'ring-status-closed',
  },
  red: {
    bg: 'bg-status-failed/10',
    text: 'text-status-failed',
    dot: 'bg-status-failed',
    border: 'border-status-failed/30',
    borderStrong: 'border-status-failed',
    ring: 'ring-status-failed',
  },
}

/** Static bg/text/dot/border/borderStrong classes for a hue — for non-badge uses (e.g. a status ring on an avatar). */
export function statusHueClasses(hue: StatusHue) {
  return HUE_CLASSES[hue]
}

export interface StatusBadgeProps {
  status: string
  category?: string
  label?: string
  className?: string
}

/** The single source of status color — replaces every local STATUS_COLORS map. */
function StatusBadge({ status, category, label, className }: StatusBadgeProps) {
  const classes = HUE_CLASSES[statusHue(status, category)]
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-medium',
        classes.bg,
        classes.text,
        className
      )}
    >
      <span className={cn('h-1.5 w-1.5 rounded-full', classes.dot)} />
      {label ?? statusLabel(status)}
    </span>
  )
}

export { StatusBadge }
