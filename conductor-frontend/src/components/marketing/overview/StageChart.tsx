// The in-app funnel as one chart: a bar per stage in funnel order, sized against the first stage, with the
// share of the previous stage and the feed's provisional and small-sample flags. Reads the PRODUCT
// channel's metrics that declare a funnelOrder.

import { MetricFlags } from './MetricFlags'
import { conversionRate, formatMetricValue } from './format'
import type { OverviewMetric } from './types'

export function productStages(metrics: OverviewMetric[]): OverviewMetric[] {
  return metrics
    .filter((m) => m.funnelOrder !== null && m.funnelOrder !== undefined)
    .sort((a, b) => (a.funnelOrder ?? 0) - (b.funnelOrder ?? 0))
}

export function StageChart({ metrics }: { metrics: OverviewMetric[] }) {
  const stages = productStages([...metrics])
  if (stages.length === 0) return null
  const max = Math.max(...stages.map((s) => s.value ?? 0), 0)

  return (
    <section aria-label="Stages" className="space-y-3 rounded-lg border border-border bg-card p-4">
      <h3 className="text-sm font-semibold text-foreground">Stages</h3>
      <ol className="space-y-3">
        {stages.map((stage, i) => {
          const previous = i > 0 ? stages[i - 1] : null
          const conversion = previous ? conversionRate(previous.value, stage.value) : null
          const width = max > 0 && stage.value ? Math.max(2, Math.round((stage.value / max) * 100)) : 0
          return (
            <li key={stage.metric} aria-label={stage.label} className="space-y-1">
              <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
                <p className="text-sm font-medium text-foreground">{stage.label}</p>
                <p className="text-sm tabular-nums text-foreground">
                  {formatMetricValue(stage.value, stage.unit)}
                  {conversion !== null && previous && (
                    <span className="ml-2 text-xs text-muted-foreground">
                      {(conversion * 100).toFixed(1)}% of {previous.label.toLowerCase()}
                    </span>
                  )}
                </p>
              </div>
              <div className="h-2 rounded-full bg-muted" aria-hidden>
                <div className="h-2 rounded-full bg-primary" style={{ width: `${width}%` }} />
              </div>
              <MetricFlags metric={stage} />
            </li>
          )
        })}
      </ol>
    </section>
  )
}
