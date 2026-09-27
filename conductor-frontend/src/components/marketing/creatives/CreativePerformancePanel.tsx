'use client'

// COND-24 T5: performance + hook experiments on the Creative editor page. Attribution and the winner
// rule live entirely on the backend (docs/creatives.md's "Performance and experiments") — this panel
// only reads CreativePerformanceResponse/CreativeExperimentResponse and drives the two experiment
// actions (decide, confirm-winner) a human can take. A small table beats a chart here (dataviz skill).

import { useCallback, useEffect, useState } from 'react'
import { ChevronDownIcon, ChevronRightIcon, FlaskConicalIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Modal } from '@/components/ui/modal'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { toastError, toastSuccess } from '@/components/ui/toast'
import { Can } from '@/components/auth/Can'
import { apiErrorMessage } from '@/lib/api'
import { humanizeId } from '@/lib/workflows'
import { compactCount, count } from '@/components/marketing/destinations/DestinationMetrics'
import { avgViewPctLabel, engagementRateLabel } from '@/components/marketing/insights/types'
import {
  confirmCreativeExperimentWinner,
  createCreativeExperiment,
  decideCreativeExperiment,
  getCreativePerformance,
  listCreativeExperiments,
  type CreativeExperimentMetric,
  type CreativeExperimentResponse,
  type CreativeExperimentState,
  type CreativePerformanceEntry,
  type CreativePerformanceResponse,
} from '@/components/marketing/creatives/types'

const EXPERIMENT_STATE_HUE: Record<CreativeExperimentState, 'blue' | 'green' | 'slate'> = {
  RUNNING: 'blue',
  DECIDED: 'green',
  INCONCLUSIVE: 'slate',
}

const METRIC_LABEL: Record<CreativeExperimentMetric, string> = {
  views: 'Views',
  engagement_rate: 'Engagement rate',
  avg_view_pct: 'Avg view %',
}

function metricValueLabel(metric: string | undefined, value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  if (metric === 'engagement_rate') return engagementRateLabel(value)
  if (metric === 'avg_view_pct') return avgViewPctLabel(value)
  return compactCount(value)
}

function StartExperimentDialog({
  open,
  onOpenChange,
  onCreated,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  onCreated: (metric: CreativeExperimentMetric, windowHours: number) => Promise<void>
}) {
  const [metric, setMetric] = useState<CreativeExperimentMetric>('views')
  const [windowHours, setWindowHours] = useState('72')
  const [busy, setBusy] = useState(false)

  async function submit() {
    const hours = Number(windowHours)
    setBusy(true)
    try {
      await onCreated(metric, Number.isFinite(hours) && hours > 0 ? hours : 72)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Start experiment"
      description="Compares this family's variants once each has published and reported window data. Post the variants in similar conditions — same time of day, same platform — or the comparison won't be a fair one."
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={busy}>
            Cancel
          </Button>
          <Button onClick={submit} disabled={busy}>
            {busy ? 'Starting…' : 'Start experiment'}
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        <div>
          <Label htmlFor="experiment-metric">Metric</Label>
          <Select
            id="experiment-metric"
            value={metric}
            onChange={(e) => setMetric(e.target.value as CreativeExperimentMetric)}
          >
            <option value="views">Views</option>
            <option value="engagement_rate">Engagement rate</option>
            <option value="avg_view_pct">Avg view %</option>
          </Select>
          <p className="mt-1 text-xs text-muted-foreground">
            Used only when a variant doesn&rsquo;t report avg view % — the decision prefers avg view % whenever
            every variant has it.
          </p>
        </div>
        <div>
          <Label htmlFor="experiment-window">Window (hours)</Label>
          <Input
            id="experiment-window"
            type="number"
            min={1}
            value={windowHours}
            onChange={(e) => setWindowHours(e.target.value)}
          />
        </div>
      </div>
    </Modal>
  )
}

function ExperimentCard({
  experiment,
  onDecide,
  onConfirmWinner,
}: {
  experiment: CreativeExperimentResponse
  onDecide: (id: string) => Promise<void>
  onConfirmWinner: (id: string) => Promise<void>
}) {
  const [busy, setBusy] = useState<'decide' | 'confirm' | null>(null)

  async function handleDecide() {
    setBusy('decide')
    try {
      await onDecide(experiment.id)
    } finally {
      setBusy(null)
    }
  }

  async function handleConfirm() {
    setBusy('confirm')
    try {
      await onConfirmWinner(experiment.id)
    } finally {
      setBusy(null)
    }
  }

  const metric = experiment.summary?.comparisonMetric ?? experiment.metric
  const canConfirm =
    experiment.state === 'DECIDED' && experiment.winnerCreativeId != null && !experiment.winnerLineConfirmedAt

  return (
    <Card data-testid={`experiment-card-${experiment.id}`}>
      <CardHeader>
        <div className="flex items-center gap-2">
          <FlaskConicalIcon className="h-4 w-4 text-muted-foreground" aria-hidden />
          <StatusBadge status={experiment.state} hue={EXPERIMENT_STATE_HUE[experiment.state]} label={humanizeId(experiment.state)} />
          <span className="text-xs text-muted-foreground">
            {METRIC_LABEL[experiment.metric]} · {experiment.windowHours}h window
          </span>
        </div>
        {experiment.state === 'RUNNING' && (
          <Can do="creative.manage">
            <Button variant="outline" size="sm" onClick={handleDecide} disabled={busy !== null}>
              {busy === 'decide' ? 'Checking…' : 'Check now'}
            </Button>
          </Can>
        )}
      </CardHeader>
      <CardContent className="space-y-3 p-4">
        {experiment.summary ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                  <th className="py-1 pr-3 font-medium">Variant</th>
                  <th className="py-1 pr-3 font-medium">Headline</th>
                  <th className="py-1 pr-3 text-right font-medium">{METRIC_LABEL[metric as CreativeExperimentMetric] ?? metric}</th>
                </tr>
              </thead>
              <tbody>
                {experiment.summary.variants.map((v) => {
                  const isWinner = v.creativeId === experiment.winnerCreativeId
                  const value =
                    metric === 'engagement_rate' ? v.engagementRate : metric === 'avg_view_pct' ? v.avgViewPct : v.views
                  return (
                    <tr key={v.creativeId} className="border-t border-border">
                      <td className="py-1.5 pr-3">
                        <span className={isWinner ? 'font-semibold text-status-approved' : 'text-foreground'}>
                          {v.label}
                          {isWinner && ' · Winner'}
                        </span>
                      </td>
                      <td className="max-w-xs truncate py-1.5 pr-3 text-muted-foreground">{v.headline ?? '—'}</td>
                      <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">
                        {v.hasData ? metricValueLabel(metric, value) : 'No data yet'}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            {experiment.summary.reason === 'tie' && (
              <p className="mt-2 text-xs text-muted-foreground">Settled inconclusive — the leaders tied.</p>
            )}
            {experiment.summary.reason === 'insufficient_data' && (
              <p className="mt-2 text-xs text-muted-foreground">
                Settled inconclusive — a variant never reported window data.
              </p>
            )}
          </div>
        ) : (
          <p className="text-sm text-muted-foreground">
            Waiting on every variant to publish and reach its {experiment.windowHours}h window.
          </p>
        )}

        {canConfirm && (
          <div className="space-y-2 rounded-md border border-border bg-surface-2 p-3">
            <p className="text-sm text-foreground">
              Add the winning variant&rsquo;s headline to this family&rsquo;s Brand Kit approved lines?
            </p>
            <p className="text-xs text-muted-foreground">
              Nothing is added automatically — this is the one step a person takes to let a decided winner
              influence future brand copy.
            </p>
            <Can do="creative.manage">
              <Button size="sm" onClick={handleConfirm} disabled={busy !== null}>
                {busy === 'confirm' ? 'Confirming…' : 'Add winning headline to approved lines'}
              </Button>
            </Can>
          </div>
        )}
        {experiment.winnerLineConfirmedAt && (
          <p className="text-xs text-muted-foreground">Winning headline added to the Brand Kit&rsquo;s approved lines.</p>
        )}
      </CardContent>
    </Card>
  )
}

function VariantRow({
  entry,
  showAvgViewPct,
  expanded,
  onToggle,
}: {
  entry: CreativePerformanceEntry
  showAvgViewPct: boolean
  expanded: boolean
  onToggle: () => void
}) {
  const hasBreakdown = entry.byPlatform.length > 0
  return (
    <>
      <tr className="border-t border-border">
        <td className="py-1.5 pr-3">
          <button
            type="button"
            onClick={onToggle}
            disabled={!hasBreakdown}
            className="flex items-center gap-1 text-left font-mono text-foreground disabled:cursor-default"
            aria-expanded={expanded}
            aria-label={`${entry.label} platform breakdown`}
          >
            {hasBreakdown &&
              (expanded ? (
                <ChevronDownIcon className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
              ) : (
                <ChevronRightIcon className="h-3.5 w-3.5 text-muted-foreground" aria-hidden />
              ))}
            {entry.label}
          </button>
        </td>
        <td className="max-w-xs truncate py-1.5 pr-3 text-muted-foreground">{entry.headline ?? '—'}</td>
        <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{count(entry.posts)}</td>
        <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{compactCount(entry.views)}</td>
        <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{compactCount(entry.views72h)}</td>
        <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{engagementRateLabel(entry.engagementRate)}</td>
        {showAvgViewPct && (
          <td className="py-1.5 pr-3 text-right tabular-nums text-foreground">{avgViewPctLabel(entry.avgViewPct)}</td>
        )}
      </tr>
      {expanded &&
        entry.byPlatform.map((p) => (
          <tr key={p.platform} className="border-t border-border bg-surface-2 text-xs">
            <td className="py-1 pl-6 pr-3 text-muted-foreground">{humanizeId(p.platform)}</td>
            <td className="py-1 pr-3" />
            <td className="py-1 pr-3 text-right tabular-nums text-muted-foreground">{count(p.posts)}</td>
            <td className="py-1 pr-3 text-right tabular-nums text-muted-foreground">{compactCount(p.views)}</td>
            <td className="py-1 pr-3" />
            <td className="py-1 pr-3 text-right tabular-nums text-muted-foreground">{engagementRateLabel(p.engagementRate)}</td>
            {showAvgViewPct && <td className="py-1 pr-3" />}
          </tr>
        ))}
    </>
  )
}

export interface CreativePerformancePanelProps {
  projectId: string
  creativeId: string
  token: string
}

export function CreativePerformancePanel({ projectId, creativeId, token }: CreativePerformancePanelProps) {
  const [performance, setPerformance] = useState<CreativePerformanceResponse | null>(null)
  const [experiments, setExperiments] = useState<CreativeExperimentResponse[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [startOpen, setStartOpen] = useState(false)

  const load = useCallback(async () => {
    try {
      const [perf, exps] = await Promise.all([
        getCreativePerformance(projectId, creativeId, token),
        listCreativeExperiments(projectId, token, { creativeId }),
      ])
      setPerformance(perf)
      setExperiments(exps)
      setError(null)
    } catch (err) {
      setError(apiErrorMessage(err, 'Could not load performance for this Creative'))
    }
  }, [projectId, creativeId, token])

  useEffect(() => {
    void load()
  }, [load])

  function toggle(id: string) {
    setExpanded((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  async function handleCreateExperiment(metric: CreativeExperimentMetric, windowHours: number) {
    try {
      const created = await createCreativeExperiment(projectId, { creativeId, metric, windowHours }, token)
      setExperiments((prev) => [created, ...(prev ?? [])])
      setStartOpen(false)
      toastSuccess('Experiment started')
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not start this experiment'))
    }
  }

  async function handleDecide(experimentId: string) {
    try {
      const updated = await decideCreativeExperiment(projectId, experimentId, token)
      setExperiments((prev) => (prev ?? []).map((e) => (e.id === experimentId ? updated : e)))
      if (updated.state === 'RUNNING') {
        toastSuccess('Still waiting on window data — nothing decided yet')
      } else {
        toastSuccess(`Experiment settled ${humanizeId(updated.state).toLowerCase()}`)
      }
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not check this experiment'))
    }
  }

  async function handleConfirmWinner(experimentId: string) {
    try {
      const updated = await confirmCreativeExperimentWinner(projectId, experimentId, token)
      setExperiments((prev) => (prev ?? []).map((e) => (e.id === experimentId ? updated : e)))
      toastSuccess("Winning headline added to the Brand Kit's approved lines")
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not confirm this winner'))
    }
  }

  const family = performance?.family ?? []
  const showAvgViewPct = family.some((f) => f.avgViewPct !== null && f.avgViewPct !== undefined)
  const hasAnyPosts = family.some((f) => f.posts > 0)
  const activeExperiment = (experiments ?? []).find((e) => e.state === 'RUNNING') ?? null
  const canStartExperiment = family.length >= 2 && !activeExperiment

  if (error) {
    return (
      <Card className="p-4">
        <Alert variant="destructive">{error}</Alert>
      </Card>
    )
  }

  if (performance === null || experiments === null) {
    return (
      <Card className="space-y-3 p-4">
        <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Performance</h3>
        <Skeleton className="h-24 w-full" />
      </Card>
    )
  }

  return (
    <div className="space-y-4" data-testid="creative-performance-panel">
      <Card>
        <CardHeader>
          <h3 className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Performance</h3>
          {canStartExperiment && (
            <Can do="creative.manage">
              <Button variant="outline" size="sm" onClick={() => setStartOpen(true)}>
                Start experiment
              </Button>
            </Can>
          )}
        </CardHeader>
        <CardContent className="p-4">
          {!hasAnyPosts ? (
            <EmptyState
              icon={FlaskConicalIcon}
              title="No published Posts yet"
              description="Once a variant of this family is published and Conductor reads its counters, its performance shows up here."
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="text-left text-xs uppercase tracking-wide text-muted-foreground">
                    <th className="py-1 pr-3 font-medium">Variant</th>
                    <th className="py-1 pr-3 font-medium">Headline</th>
                    <th className="py-1 pr-3 text-right font-medium">Posts</th>
                    <th className="py-1 pr-3 text-right font-medium">Views</th>
                    <th className="py-1 pr-3 text-right font-medium">72h views</th>
                    <th className="py-1 pr-3 text-right font-medium">Engagement rate</th>
                    {showAvgViewPct && <th className="py-1 pr-3 text-right font-medium">Avg view %</th>}
                  </tr>
                </thead>
                <tbody>
                  {family.map((entry) => (
                    <VariantRow
                      key={entry.creativeId}
                      entry={entry}
                      showAvgViewPct={showAvgViewPct}
                      expanded={expanded.has(entry.creativeId)}
                      onToggle={() => toggle(entry.creativeId)}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {experiments.length > 0 && (
        <div className="space-y-3">
          {experiments.map((experiment) => (
            <ExperimentCard
              key={experiment.id}
              experiment={experiment}
              onDecide={handleDecide}
              onConfirmWinner={handleConfirmWinner}
            />
          ))}
        </div>
      )}

      <StartExperimentDialog open={startOpen} onOpenChange={setStartOpen} onCreated={handleCreateExperiment} />
    </div>
  )
}
