import { ArrowDownIcon, ArrowUpIcon, MinusIcon } from 'lucide-react'
import { cn } from '@/lib/utils'
import { TONE_CLASS, changeLabel, changeTone, shortDate } from './format'
import type { OverviewMetric } from './types'

/** Arrow + percent change versus the previous period, coloured by whether the move is good for this
 *  metric. A change inside normal variation stays muted and says so. */
export function ChangeLine({ metric }: { metric: OverviewMetric }) {
  const label = changeLabel(metric.changePct)
  if (label === null) {
    const starts = shortDate(metric.historyStartsOn)
    return (
      <p className="text-xs text-muted-foreground">
        {metric.previous == null && starts ? `History starts ${starts}` : 'No previous period to compare'}
      </p>
    )
  }
  const tone = changeTone(metric)
  const Icon = (metric.change ?? 0) > 0 ? ArrowUpIcon : (metric.change ?? 0) < 0 ? ArrowDownIcon : MinusIcon
  const direction = (metric.change ?? 0) > 0 ? 'up' : (metric.change ?? 0) < 0 ? 'down' : 'flat'
  return (
    <p className="flex flex-wrap items-center gap-x-1.5 text-xs">
      <span className={cn('inline-flex items-center gap-0.5 font-medium tabular-nums', TONE_CLASS[tone])}>
        <Icon className="h-3 w-3" aria-hidden />
        {label}
        <span className="sr-only">{` ${direction} versus the previous period`}</span>
      </span>
      {metric.noise && <span className="text-muted-foreground">within normal range</span>}
    </p>
  )
}
