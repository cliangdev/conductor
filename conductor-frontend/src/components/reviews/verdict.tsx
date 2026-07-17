import { CheckCircle2, RefreshCw, MessageCircle, Clock } from 'lucide-react'
import { cn } from '@/lib/utils'

// The one Verdict type — imported everywhere a review outcome is represented (list rows, the detail
// properties panel, the batch review bar) instead of each surface redeclaring the same literal union.
export type Verdict = 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'

const VERDICT_LABELS: Record<Verdict, string> = {
  APPROVED: 'Approved',
  CHANGES_REQUESTED: 'Changes requested',
  COMMENTED: 'Commented',
}

interface VerdictIconProps {
  verdict?: Verdict
  className?: string
}

/** The one verdict → icon + color mapping — replaces the emoji-keyed maps duplicated across the list, summary panel, and submission form. */
export function VerdictIcon({ verdict, className }: VerdictIconProps) {
  const label = verdict ? VERDICT_LABELS[verdict] : 'Review pending'
  const cls = cn('h-3.5 w-3.5', className)
  switch (verdict) {
    case 'APPROVED':
      return <CheckCircle2 role="img" aria-label={label} className={cn(cls, 'text-status-done')} />
    case 'CHANGES_REQUESTED':
      return <RefreshCw role="img" aria-label={label} className={cn(cls, 'text-status-progress')} />
    case 'COMMENTED':
      return <MessageCircle role="img" aria-label={label} className={cn(cls, 'text-status-review')} />
    default:
      return <Clock role="img" aria-label={label} className={cn(cls, 'text-muted-foreground')} />
  }
}
