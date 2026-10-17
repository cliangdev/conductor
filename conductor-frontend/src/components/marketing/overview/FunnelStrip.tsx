import { InfoIcon } from 'lucide-react'
import { ChangeLine } from './ChangeLine'
import { MetricFlags } from './MetricFlags'
import { conversionRate, formatMetricValue, readOnlyTargetSource, targetLine } from './format'
import type { OverviewFunnelStage, OverviewMetric } from './types'

const PRODUCT_STAGE_PREFIX = 'product_'

/** The in-app metric behind a funnel stage (stage keys are `product_<metric>`). */
function productMetricFor(stage: OverviewFunnelStage, metrics: OverviewMetric[]): OverviewMetric | null {
  if (stage.path !== 'PRODUCT' || !stage.key.startsWith(PRODUCT_STAGE_PREFIX)) return null
  const key = stage.key.slice(PRODUCT_STAGE_PREFIX.length)
  return metrics.find((m) => m.metric === key) ?? null
}

const FRESHNESS_NOTE =
  'Days up to the "data through" date are complete. Later days may still be reported and can change.'

function stageChange(stage: OverviewFunnelStage) {
  const pct =
    stage.change !== null && stage.change !== undefined && stage.previous ? stage.change / Math.abs(stage.previous) : null
  return {
    metric: stage.key,
    label: stage.label,
    unit: 'COUNT' as const,
    goodDirection: 'UP' as const,
    change: stage.change,
    changePct: pct,
    noise: false,
    series: [],
  }
}

type Item =
  | { kind: 'stage'; stage: OverviewFunnelStage; from: OverviewFunnelStage | null }
  | { kind: 'unavailable'; stages: OverviewFunnelStage[] }

/** Consecutive unavailable stages with the same source and reason read as one muted tile. */
function toItems(stages: OverviewFunnelStage[]): Item[] {
  const items: Item[] = []
  for (const stage of stages) {
    if (stage.available) {
      const from = stage.conversionFromKey ? (stages.find((s) => s.key === stage.conversionFromKey) ?? null) : null
      items.push({ kind: 'stage', stage, from })
      continue
    }
    const last = items[items.length - 1]
    if (
      last?.kind === 'unavailable' &&
      last.stages[0].source === stage.source &&
      (last.stages[0].unavailableReason ?? '') === (stage.unavailableReason ?? '')
    ) {
      last.stages.push(stage)
    } else {
      items.push({ kind: 'unavailable', stages: [stage] })
    }
  }
  return items
}

/** The acquisition funnel as one strip: columns on wide screens, compact rows (label, value, change) on
 *  narrow ones. A stage with no source yet is muted and says why; conversion is only shown where the
 *  API names the stage it converts from. */
export function FunnelStrip({
  stages,
  channelLabels = {},
  productMetrics = [],
}: {
  stages: OverviewFunnelStage[]
  /** The In-app channel's metrics: their provisional and small-sample flags and read-only targets show on the in-app stages. */
  productMetrics?: OverviewMetric[]
  /** Channel key to display label, used to name a collapsed group of unavailable stages. */
  channelLabels?: Record<string, string>
}) {
  if (stages.length === 0) return null
  const items = toItems(stages)

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
      <ol className="divide-y divide-border overflow-hidden rounded-lg border border-border bg-card lg:grid lg:grid-cols-[repeat(auto-fill,minmax(9rem,1fr))] lg:gap-2 lg:divide-y-0 lg:overflow-visible lg:border-0 lg:bg-transparent">
        {items.map((item) => {
          if (item.kind === 'unavailable') {
            const first = item.stages[0]
            const names = item.stages.map((s) => s.label.toLowerCase()).join(' → ')
            const prefix = channelLabels[first.source]
            const title = item.stages.length > 1 && prefix ? `${prefix}: ${names}` : item.stages.length > 1 ? names : first.label
            return (
              <li key={item.stages.map((s) => s.key).join('+')} className="min-w-0 bg-muted/40 px-4 py-2.5 lg:rounded-md lg:border lg:border-border lg:py-3">
                <p className="text-xs font-medium text-muted-foreground">{title}</p>
                <p className="text-xs text-muted-foreground">{first.unavailableReason ?? 'No data source yet'}</p>
              </li>
            )
          }
          const { stage, from } = item
          const conversion = from ? conversionRate(from.value, stage.value) : null
          const product = productMetricFor(stage, productMetrics)
          const targetNote = product?.target ? targetLine(product) : null
          return (
            <li
              key={stage.key}
              className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 px-4 py-2.5 lg:block lg:rounded-md lg:border lg:border-border lg:bg-card lg:py-3"
            >
              <p className="text-xs font-medium text-muted-foreground">{stage.label}</p>
              <div className="flex flex-col items-end lg:mt-1 lg:items-start">
                <p className="text-base font-semibold tabular-nums text-foreground lg:text-2xl">
                  {formatMetricValue(stage.value, 'COUNT')}
                </p>
                <ChangeLine metric={stageChange(stage)} />
              </div>
              {conversion !== null && from && (
                <p className="basis-full text-xs text-muted-foreground lg:mt-1">
                  {(conversion * 100).toFixed(1)}% of {from.label.toLowerCase()}
                </p>
              )}
              {product && <MetricFlags metric={product} className="basis-full lg:mt-1" />}
              {product?.target && targetNote && (
                <p className="basis-full text-xs text-muted-foreground lg:mt-1">
                  {targetNote}
                  {product.target.readOnly && <> · {readOnlyTargetSource(product.target)}</>}
                </p>
              )}
            </li>
          )
        })}
      </ol>
    </section>
  )
}
