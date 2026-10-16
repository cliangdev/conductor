'use client'

// One channel on the overview: status, freshness, up to four headline metrics (value, change, sparkline,
// target) and — when the channel has no data source — a way to connect one. The whole card links to the
// channel's detail page through a stretched link; the inner buttons sit above it.

import Link from 'next/link'
import { buttonVariants } from '@/components/ui/button'
import { timeAgo } from '@/lib/format'
import { ChangeLine } from './ChangeLine'
import { ChannelStatusPill } from './ChannelStatusPill'
import { MetricFlags } from './MetricFlags'
import { Sparkline } from './Sparkline'
import { TargetNote } from './TargetNote'
import { formatMetricValue, shortDate } from './format'
import type { ChannelKey, OverviewChannel, OverviewMetric } from './types'

const HEADLINE_COUNT = 4

function MetricRow({
  metric,
  canEditTargets,
  showFlags,
  targetsEditable,
  ownerLabel,
  onSetTarget,
}: {
  metric: OverviewMetric
  canEditTargets: boolean
  /** In-app metrics carry the feed's provisional and small-sample flags. */
  showFlags: boolean
  targetsEditable: boolean
  ownerLabel?: string
  onSetTarget: () => void
}) {
  return (
    <li className="space-y-1 py-3 first:pt-0 last:pb-0">
      <div className="flex items-end justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-xs font-medium text-muted-foreground">{metric.label}</p>
          <p className="text-xl font-semibold tabular-nums text-foreground">
            {formatMetricValue(metric.value, metric.unit)}
            {metric.asOf && (
              <span className="ml-1.5 text-xs font-normal text-muted-foreground">as of {shortDate(metric.asOf)}</span>
            )}
          </p>
        </div>
        <div className="w-24 shrink-0">
          <Sparkline series={metric.series} label={metric.label} />
        </div>
      </div>
      <ChangeLine metric={metric} />
      {showFlags && <MetricFlags metric={metric} />}
      <TargetNote
        metric={metric}
        canEdit={canEditTargets}
        onEdit={onSetTarget}
        buttonClassName="relative z-10"
        editable={targetsEditable}
        ownerLabel={ownerLabel}
      />
    </li>
  )
}

export function ChannelCard({
  projectId,
  channel,
  canEditTargets,
  onSetTarget,
}: {
  projectId: string
  channel: OverviewChannel
  canEditTargets: boolean
  onSetTarget: (channel: ChannelKey, metric: OverviewMetric) => void
}) {
  const detailHref = `/app/projects/${projectId}/marketing/overview/${channel.channel.toLowerCase()}`
  const live = channel.status === 'LIVE' || channel.status === 'STALE' || channel.status === 'MISMATCH'
  const metrics = channel.metrics.slice(0, HEADLINE_COUNT)
  const connectors = channel.connectHint?.connectors ?? []

  return (
    <section
      aria-label={channel.label}
      className={`relative flex flex-col rounded-lg border border-border bg-card p-4 transition-colors hover:bg-muted/40 focus-within:ring-2 focus-within:ring-ring${live ? '' : ' self-start'}`}
    >
      <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-foreground">
          <Link href={detailHref} className="outline-none after:absolute after:inset-0 after:content-['']">
            {channel.label}
          </Link>
        </h2>
        <ChannelStatusPill status={channel.status} />
      </header>

      {live && (
        <>
          {channel.lastFetchedAt && (
            <p className="mb-3 text-xs text-muted-foreground">Last updated {timeAgo(channel.lastFetchedAt)}</p>
          )}
          {channel.status === 'MISMATCH' && channel.mismatch && (
            <p className="mb-3 text-xs text-status-failed">Does not match the {channel.mismatch.label.toLowerCase()} reported by the product backend ({(channel.mismatch.gapPct * 100).toFixed(1)}% apart)</p>
          )}
          {metrics.length === 0 ? (
            <p className="text-sm text-muted-foreground">No data in this window yet.</p>
          ) : (
            <ul className="divide-y divide-border">
              {metrics.map((m) => (
                <MetricRow
                  key={m.metric}
                  metric={m}
                  canEditTargets={canEditTargets}
                  showFlags={channel.channel === 'PRODUCT'}
                  targetsEditable={channel.targetsEditable !== false}
                  ownerLabel={channel.connections[0]?.name}
                  onSetTarget={() => onSetTarget(channel.channel, m)}
                />
              ))}
            </ul>
          )}
        </>
      )}

      {channel.status === 'NOT_CONNECTED' && (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">
            {channel.channel === 'PRODUCT'
              ? 'Connect your product’s metrics to see trials, paid and cost per trial here.'
              : 'Connect a data source to see this channel here.'}
          </p>
          {connectors.length > 0 && (
            <div className="flex flex-wrap gap-2">
              {connectors.map((c) => (
                <Link
                  key={c.connectorId}
                  href={`/app/projects/${projectId}/integrations/${c.connectorId}`}
                  className={`${buttonVariants({ variant: 'outline', size: 'sm' })} relative z-10`}
                >
                  Connect {c.name}
                </Link>
              ))}
            </div>
          )}
        </div>
      )}

      {channel.status === 'COMING_SOON' && (
        <p className="text-sm text-muted-foreground">Coming soon. This channel is not available yet.</p>
      )}
    </section>
  )
}
