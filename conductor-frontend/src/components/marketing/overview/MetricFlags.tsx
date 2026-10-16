// Data-quality flags for one metric: the last days are still provisional, and/or the value rests on too
// few users or events to read as a real change. Renders nothing when neither applies.

import { cn } from '@/lib/utils'
import type { OverviewMetric } from './types'

export const PROVISIONAL_NOTE = 'Recent days may still change and are left out of the total.'
export const SMALL_SAMPLE_NOTE = 'Too few users or events to read this as a real change.'

export function metricFlags(metric: Pick<OverviewMetric, 'provisionalDays' | 'insufficientData'>) {
  return {
    provisional: (metric.provisionalDays ?? 0) > 0,
    smallSample: metric.insufficientData === true,
  }
}

function Chip({ children, title }: { children: string; title: string }) {
  return (
    <span
      title={title}
      className="inline-flex items-center rounded-full border border-border bg-muted px-1.5 py-0.5 text-[11px] font-medium text-muted-foreground"
    >
      {children}
    </span>
  )
}

export function MetricFlags({
  metric,
  className,
}: {
  metric: Pick<OverviewMetric, 'provisionalDays' | 'insufficientData'>
  className?: string
}) {
  const { provisional, smallSample } = metricFlags(metric)
  if (!provisional && !smallSample) return null
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1', className)}>
      {provisional && <Chip title={PROVISIONAL_NOTE}>Provisional</Chip>}
      {smallSample && <Chip title={SMALL_SAMPLE_NOTE}>Small sample</Chip>}
    </span>
  )
}
