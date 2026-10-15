import { InfoIcon } from 'lucide-react'
import { StatCard } from '@/components/integrations/StatCard'
import { cn } from '@/lib/utils'
import { ChangeLine } from './ChangeLine'
import { conversionRate, formatMetricValue, shortDate } from './format'
import type { OverviewFunnelStage } from './types'

const FRESHNESS_NOTE =
  'Days up to the "data through" date are complete. Later days may still be reported and can change.'

function stageChange(stage: OverviewFunnelStage) {
  const pct =
    stage.change !== null && stage.change !== undefined && stage.previous ? stage.change / Math.abs(stage.previous) : null
  return { metric: stage.key, label: stage.label, unit: 'COUNT' as const, goodDirection: 'UP' as const, change: stage.change, changePct: pct, noise: false, series: [] }
}

/** The acquisition funnel, left to right (stacked on phones). A stage with no source yet is muted and
 *  says why; conversion is shown between two stages that both have data. */
export function FunnelStrip({ stages }: { stages: OverviewFunnelStage[] }) {
  if (stages.length === 0) return null
  const items = stages.map((stage, i) => {
    const from = stage.available ? [...stages.slice(0, i)].reverse().find((s) => s.available) ?? null : null
    const conversion = from ? conversionRate(from.value, stage.value) : null
    return { stage, conversion, from }
  })

  return (
    <section aria-label="Funnel" className="space-y-2">
      <div className="flex items-center gap-1.5">
        <h2 className="text-sm font-semibold text-foreground">Funnel</h2>
        <span
          tabIndex={0}
          role="img"
          aria-label={FRESHNESS_NOTE}
          title={FRESHNESS_NOTE}
          className="rounded-sm text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <InfoIcon className="h-3.5 w-3.5" aria-hidden />
        </span>
      </div>
      <ol className="grid gap-3 sm:grid-cols-2 lg:grid-flow-col lg:auto-cols-fr lg:grid-cols-none">
        {items.map(({ stage, conversion, from }) => (
          <li key={stage.key} className="min-w-0">
            <StatCard
              label={stage.label}
              value={stage.available ? formatMetricValue(stage.value, 'COUNT') : '—'}
              className={cn('h-full', !stage.available && 'bg-muted/40')}
            >
              {stage.available ? (
                <div className="mt-2 space-y-1">
                  <ChangeLine metric={stageChange(stage)} />
                  {conversion !== null && from && (
                    <p className="text-xs text-muted-foreground">
                      {(conversion * 100).toFixed(1)}% of {from.label.toLowerCase()}
                    </p>
                  )}
                  {stage.dataCompleteThrough && (
                    <p className="text-xs text-muted-foreground">
                      Data through {shortDate(stage.dataCompleteThrough)}
                    </p>
                  )}
                </div>
              ) : (
                <p className="mt-2 text-xs text-muted-foreground">{stage.unavailableReason ?? 'No data source yet'}</p>
              )}
            </StatCard>
          </li>
        ))}
      </ol>
    </section>
  )
}
