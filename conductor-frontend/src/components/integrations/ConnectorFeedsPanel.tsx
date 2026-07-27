'use client'

import { useCallback, useEffect, useState } from 'react'
import { RefreshCwIcon } from 'lucide-react'
import { Card } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { Select } from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import { Alert } from '@/components/ui/alert'
import { StatusBadge } from '@/components/ui/status-badge'
import { useToast } from '@/components/ui/toast'
import { useAuth } from '@/contexts/AuthContext'
import { useCan } from '@/contexts/PermissionsContext'
import { timeAgo } from '@/lib/format'
import { cn } from '@/lib/utils'
import {
  listConnectorFeeds,
  updateConnectorFeed,
  runConnectorFeedNow,
  apiErrorMessage,
  type ConnectorFeedDto,
  type ConnectorFeedStatus,
} from '@/lib/api'

const CADENCE_PRESETS = [
  { minutes: 60, label: 'Hourly' },
  { minutes: 1440, label: 'Daily' },
  { minutes: 10080, label: 'Weekly' },
] as const

/** Local vocabulary → StatusBadge hue, same idiom as ConnectorHeader's HEALTH_STATUS map. */
const FEED_STATUS: Record<ConnectorFeedStatus, { status: string; label: string }> = {
  ACTIVE: { status: 'done', label: 'Active' },
  PAUSED: { status: 'closed', label: 'Paused' },
  SETUP_REQUIRED: { status: 'in_progress', label: 'Setup required' },
  DEAD: { status: 'failed', label: 'Dead' },
}

/** The three cadence presets, plus the feed's current value if it doesn't land on one of them (e.g.
 *  a connector-declared defaultIntervalMinutes outside the preset list) — so the control never
 *  silently shows the wrong selection. */
function cadenceOptions(currentMinutes: number) {
  if (CADENCE_PRESETS.some((p) => p.minutes === currentMinutes)) return CADENCE_PRESETS
  return [...CADENCE_PRESETS, { minutes: currentMinutes, label: `Every ${currentMinutes}m` }]
}

/**
 * A connector's declared Knowledge Center feeds (see docs/knowledge.md "Metrics digests" and the
 * ingest pipeline), rendered under the connector's own overview content. Renders nothing while
 * loading and nothing once loaded if the connector declares no feeds — the six pre-existing
 * connectors (no `ingest[]`) must render this panel as if it didn't exist.
 */
export default function ConnectorFeedsPanel({
  projectId,
  connectorId,
}: {
  projectId: string
  connectorId: string
}) {
  const { accessToken } = useAuth()
  const { showToast } = useToast()
  const canMutate = useCan('integration.manage')

  const [feeds, setFeeds] = useState<ConnectorFeedDto[] | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  const load = useCallback(async () => {
    if (!accessToken) return
    try {
      const rows = await listConnectorFeeds(projectId, connectorId, accessToken)
      setFeeds(rows)
    } catch {
      // Best-effort, same idiom as knowledge/manage's IngestCadenceSetting — leave the panel
      // unrendered rather than surfacing an error for what's usually a minor/optional setting.
      setFeeds([])
    }
  }, [projectId, connectorId, accessToken])

  useEffect(() => {
    load()
  }, [load])

  if (!feeds || feeds.length === 0) return null

  async function handleToggle(feed: ConnectorFeedDto, enabled: boolean) {
    if (!accessToken) return
    const previous = feeds
    setFeeds((prev) => (prev ? prev.map((f) => (f.id === feed.id ? { ...f, enabled } : f)) : prev))
    try {
      const updated = await updateConnectorFeed(projectId, connectorId, feed.id, { enabled }, accessToken)
      setFeeds((prev) => (prev ? prev.map((f) => (f.id === feed.id ? updated : f)) : prev))
    } catch (err) {
      setFeeds(previous)
      showToast(apiErrorMessage(err, 'Failed to update feed'), 'error')
    }
  }

  async function handleCadenceChange(feed: ConnectorFeedDto, intervalMinutes: number) {
    if (!accessToken) return
    const previous = feeds
    setFeeds((prev) => (prev ? prev.map((f) => (f.id === feed.id ? { ...f, intervalMinutes } : f)) : prev))
    try {
      const updated = await updateConnectorFeed(
        projectId,
        connectorId,
        feed.id,
        { intervalMinutes },
        accessToken,
      )
      setFeeds((prev) => (prev ? prev.map((f) => (f.id === feed.id ? updated : f)) : prev))
    } catch (err) {
      setFeeds(previous)
      showToast(apiErrorMessage(err, 'Failed to update cadence'), 'error')
    }
  }

  async function handleSyncNow(feed: ConnectorFeedDto) {
    if (!accessToken) return
    setBusyId(feed.id)
    try {
      const updated = await runConnectorFeedNow(projectId, connectorId, feed.id, accessToken)
      setFeeds((prev) => (prev ? prev.map((f) => (f.id === feed.id ? updated : f)) : prev))
      showToast(`${feed.label} queued to sync`)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to trigger sync'), 'error')
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 pb-8 -mt-4 space-y-2">
      <h2 className="text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground">Feeds</h2>
      <Card className="divide-y divide-border">
        {feeds.map((feed) => {
          const health = FEED_STATUS[feed.status]
          return (
            <div key={feed.id} className="p-4 flex items-center justify-between gap-4">
              <div className="min-w-0 flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium text-foreground truncate">{feed.label}</span>
                  <StatusBadge status={health.status} label={health.label} />
                </div>
                {feed.description && (
                  <p className="text-sm text-muted-foreground mt-0.5">{feed.description}</p>
                )}
                <p className="text-xs text-foreground-subtle mt-1">
                  {feed.lastRunAt ? `Last run ${timeAgo(feed.lastRunAt)}` : 'Never run'}
                </p>
                {feed.lastError && (
                  <Alert variant="destructive" className="mt-2 py-1.5 text-xs">
                    {feed.lastError}
                  </Alert>
                )}
              </div>

              <div className="flex items-center gap-3 shrink-0">
                <Select
                  aria-label={`${feed.label} cadence`}
                  className="w-auto"
                  value={String(feed.intervalMinutes)}
                  disabled={!canMutate}
                  onChange={(e) => handleCadenceChange(feed, Number(e.target.value))}
                >
                  {cadenceOptions(feed.intervalMinutes).map((preset) => (
                    <option key={preset.minutes} value={preset.minutes}>
                      {preset.label}
                    </option>
                  ))}
                </Select>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => handleSyncNow(feed)}
                  disabled={!canMutate || !feed.enabled || busyId === feed.id}
                  title={!feed.enabled ? 'Enable this feed to sync it' : undefined}
                >
                  <RefreshCwIcon className={cn('h-3.5 w-3.5 mr-1.5', busyId === feed.id && 'animate-spin')} />
                  {busyId === feed.id ? 'Syncing…' : 'Sync now'}
                </Button>
                <Switch
                  checked={feed.enabled}
                  onCheckedChange={(checked) => handleToggle(feed, checked)}
                  disabled={!canMutate}
                  aria-label={`${feed.enabled ? 'Disable' : 'Enable'} ${feed.label}`}
                />
              </div>
            </div>
          )
        })}
      </Card>
    </div>
  )
}
