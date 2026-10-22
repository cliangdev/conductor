// Data-quality flag for one metric: the value rests on too few users or events to read as a real change.
// Renders nothing otherwise. There is deliberately no "provisional" flag: days after the window are never in the
// total and are drawn dashed, and the page already says "Data through ... later days are still updating".

import { cn } from '@/lib/utils'
import type { OverviewMetric } from './types'

export const SMALL_SAMPLE_NOTE = 'Too few users or events to read this as a real change.'

/** A metric whose status already says "Too early" does not also need the chip. */
export function metricFlags(metric: Pick<OverviewMetric, 'insufficientData' | 'status'>) {
  return { smallSample: metric.insufficientData === true && metric.status !== 'TOO_EARLY' }
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
  metric: Pick<OverviewMetric, 'insufficientData' | 'status'>
  className?: string
}) {
  const { smallSample } = metricFlags(metric)
  if (!smallSample) return null
  return (
    <span className={cn('inline-flex flex-wrap items-center gap-1', className)}>
      {smallSample && <Chip title={SMALL_SAMPLE_NOTE}>Small sample</Chip>}
    </span>
  )
}
