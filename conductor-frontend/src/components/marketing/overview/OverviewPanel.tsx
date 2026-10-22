'use client'

// Marketing overview: the funnel on top, then one card per channel (Web, Search, Social, App Store,
// Apple Ads). Reads GET …/marketing/overview; targets are edited in a dialog and shown optimistically.

import Link from 'next/link'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { PlugIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button, buttonVariants } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { usePersistedStateAfterMount } from '@/lib/persisted'
import { ChannelCard } from './ChannelCard'
import { FunnelStrip } from './FunnelStrip'
import { TargetDialog } from './TargetDialog'
import { dataThroughSummary } from './format'
import { useTargetEditor } from './useTargetEditor'
import {
  CHANNEL_ORDER,
  DEFAULT_OVERVIEW_WINDOW,
  OVERVIEW_WINDOWS,
  getMarketingOverview,
  type ChannelKey,
  type MarketingOverviewResponse,
  type MarketingTarget,
  type OverviewPageWindow,
} from './types'

export const OVERVIEW_WINDOW_STORAGE_KEY = 'marketing.overview.window'

const WINDOW_TABS: TabItem[] = OVERVIEW_WINDOWS.map((w) => ({ value: w, label: w }))

function isOverviewWindow(value: string): value is OverviewPageWindow {
  return (OVERVIEW_WINDOWS as string[]).includes(value)
}

function OverviewSkeleton() {
  return (
    <div className="space-y-6" aria-busy="true" aria-label="Loading overview">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {[0, 1, 2, 3].map((i) => (
          <Skeleton key={i} className="h-28 w-full" />
        ))}
      </div>
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {CHANNEL_ORDER.map((c) => (
          <Skeleton key={c} className="h-64 w-full" />
        ))}
      </div>
    </div>
  )
}

export function OverviewPanel({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  const canEditTargets = can('marketing.target.manage')

  const [range, setRange, rangeReady] = usePersistedStateAfterMount(
    OVERVIEW_WINDOW_STORAGE_KEY,
    isOverviewWindow,
    DEFAULT_OVERVIEW_WINDOW,
  )
  const [data, setData] = useState<MarketingOverviewResponse | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    if (!accessToken || !rangeReady) return
    setError(null)
    setData(null)
    getMarketingOverview(projectId, accessToken, range)
      .then(setData)
      .catch((err) => setError(apiErrorMessage(err, 'Could not load the overview — please try again.')))
  }, [projectId, accessToken, range, rangeReady])

  useEffect(() => {
    void load()
  }, [load])

  const patchTarget = useCallback((channel: ChannelKey, metric: string, target: MarketingTarget | null) => {
    setData((prev) =>
      prev
        ? {
            ...prev,
            channels: prev.channels.map((c) =>
              c.channel !== channel
                ? c
                : { ...c, metrics: c.metrics.map((m) => (m.metric === metric ? { ...m, target } : m)) },
            ),
          }
        : prev,
    )
  }, [])
  const { editing, setEditing, save, remove } = useTargetEditor(projectId, patchTarget)

  const channels = useMemo(() => {
    if (!data) return []
    return [...data.channels].sort((a, b) => CHANNEL_ORDER.indexOf(a.channel) - CHANNEL_ORDER.indexOf(b.channel))
  }, [data])

  const dataThrough = data ? dataThroughSummary(data.channels, data.window.to) : null

  const channelLabels = useMemo(() => Object.fromEntries(channels.map((c) => [c.channel, c.label])), [channels])

  const nothingConnected =
    channels.length > 0 && channels.every((c) => c.status === 'NOT_CONNECTED' || c.status === 'COMING_SOON')

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Tabs
          ariaLabel="Time window"
          items={WINDOW_TABS}
          value={range}
          onValueChange={(v) => isOverviewWindow(v) && setRange(v)}
        />
        {dataThrough && <p className="text-xs text-muted-foreground">Data through {dataThrough}</p>}
      </div>

      {error && (
        <Alert variant="destructive">
          <div className="flex flex-wrap items-center gap-3">
            <span>{error}</span>
            <Button size="sm" variant="outline" onClick={load}>
              Retry
            </Button>
          </div>
        </Alert>
      )}

      {!data && !error && <OverviewSkeleton />}

      {data && (
        <>
          {nothingConnected ? (
            <EmptyState
              icon={PlugIcon}
              title="Nothing is connected yet"
              description="Connect a data source to see traffic, search, social and ad results in one place."
              action={
                <Link href={`/app/projects/${projectId}/integrations`} className={buttonVariants({ size: 'sm' })}>
                  Browse integrations
                </Link>
              }
            />
          ) : (
            <FunnelStrip
              stages={data.funnel}
              channelLabels={channelLabels}
              basis={data.funnelBasis}
              productMetrics={channels.find((c) => c.channel === 'PRODUCT')?.metrics ?? []}
            />
          )}

          <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
            {channels.map((channel) => (
              <ChannelCard
                key={channel.channel}
                projectId={projectId}
                channel={channel}
                canEditTargets={canEditTargets}
                onSetTarget={(ch, metric) => setEditing({ channel: ch, metric })}
              />
            ))}
          </div>
        </>
      )}

      <TargetDialog
        open={!!editing}
        onOpenChange={(open) => !open && setEditing(null)}
        metric={editing?.metric ?? null}
        onSave={save}
        onRemove={remove}
      />
    </div>
  )
}
