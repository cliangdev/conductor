import { AlertTriangleIcon } from 'lucide-react'
import { StatusBadge, type StatusHue } from '@/components/ui/status-badge'
import type { LedgerVerdict } from './types'

export const VERDICT_META: Record<LedgerVerdict, { label: string; hue: StatusHue; hint: string }> = {
  BOTH: { label: 'Both agree', hue: 'green', hint: 'Every second source available agrees with Conductor.' },
  CONNECTOR: { label: 'Trust connector', hue: 'teal', hint: 'Trust the connector: a platform read agrees with it, or the backend disagrees and no read exists yet.' },
  BACKEND: { label: 'Trust backend', hue: 'blue', hint: 'The backend and the platform read agree; the connector does not.' },
  PLATFORM: { label: 'Trust platform', hue: 'violet', hint: 'The platform read disagrees with every other source.' },
  UNCHECKED: { label: 'Unchecked', hue: 'gray', hint: 'No second source: not verified.' },
}

/** UNCHECKED is outlined and dashed, never filled or green: it is the absence of verification. */
export function VerdictBadge({ verdict }: { verdict: LedgerVerdict }) {
  const meta = VERDICT_META[verdict]
  return (
    <span title={meta.hint}>
      <StatusBadge
        status={verdict}
        label={meta.label}
        hue={meta.hue}
        className={verdict === 'UNCHECKED' ? 'border border-dashed border-status-draft/60 bg-transparent' : undefined}
      />
    </span>
  )
}

export function MismatchMarker() {
  return (
    <span className="inline-flex items-center gap-1 text-xs font-medium text-status-failed">
      <AlertTriangleIcon className="h-3 w-3" aria-hidden />
      Mismatch
    </span>
  )
}
