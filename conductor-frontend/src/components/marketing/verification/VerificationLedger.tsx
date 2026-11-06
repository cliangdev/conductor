'use client'

// The verification ledger: for every checkable metric, Conductor's value beside the product backend's and the
// latest direct platform read, which figure to trust, and whether they disagree. A metric with no second source
// is UNCHECKED and looks it: the ledger never shows an unchecked number as verified.
// Reads GET …/marketing/verification; "Record a read" posts to …/marketing/verification/reads.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { ShieldCheckIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { Tabs, type TabItem } from '@/components/ui/tabs'
import { useAuth } from '@/contexts/AuthContext'
import { usePermissions } from '@/contexts/PermissionsContext'
import { apiErrorMessage } from '@/lib/api'
import { shortDate } from '@/components/marketing/overview/format'
import { dayCount, dayRange, daysLabel, formatLedgerValue } from './format'
import { RecordReadDialog } from './RecordReadDialog'
import {
  DEFAULT_LEDGER_WINDOW,
  LEDGER_WINDOWS,
  getVerificationLedger,
  type LedgerWindow,
  type MarketingVerificationLedger,
  type VerificationRow,
} from './types'
import { MismatchMarker, VERDICT_META, VerdictBadge } from './VerdictBadge'

const WINDOW_TABS: TabItem[] = LEDGER_WINDOWS.map((w) => ({ value: w, label: w }))

function isLedgerWindow(value: string): value is LedgerWindow {
  return (LEDGER_WINDOWS as string[]).includes(value)
}

/** A row that needs someone's attention: a disagreement, nothing checking it, or a read gone stale. */
export function isProblem(row: VerificationRow): boolean {
  return row.mismatch || row.readStale || row.verdict === 'UNCHECKED' || row.verdict === 'BACKEND' || row.verdict === 'PLATFORM'
}

function Muted({ children }: { children: React.ReactNode }) {
  return <p className="text-xs text-muted-foreground">{children}</p>
}

function ConductorCell({ row }: { row: VerificationRow }) {
  const range = dayRange(row.windowFrom, row.windowTo)
  const days = dayCount(row.windowFrom, row.windowTo)
  return (
    <>
      <p className="tabular-nums text-foreground">{formatLedgerValue(row.conductorValue, row.unit)}</p>
      {range && days !== null ? <Muted>{`${range} · ${daysLabel(days)}`}</Muted> : <Muted>No data in this window</Muted>}
    </>
  )
}

function BackendCell({ row }: { row: VerificationRow }) {
  const backend = row.backend
  if (!backend) {
    return (
      <>
        <p className="text-muted-foreground">—</p>
        <Muted>No backend figure</Muted>
      </>
    )
  }
  return (
    <>
      <p className={`tabular-nums ${backend.matches ? 'text-foreground' : 'text-status-failed'}`}>{formatLedgerValue(backend.value, row.unit)}</p>
      <Muted>{`${daysLabel(backend.daysCompared)} compared · ${dayRange(backend.from, backend.to) ?? ''}`}</Muted>
      <Muted>{`Conductor had ${formatLedgerValue(backend.conductorValue, row.unit)} over these days`}</Muted>
      <Muted>{backend.source}</Muted>
    </>
  )
}

function PlatformCell({ row, canRecord, onRecord }: { row: VerificationRow; canRecord: boolean; onRecord: () => void }) {
  const read = row.platformRead
  return (
    <>
      {read ? (
        <>
          <p className="flex flex-wrap items-center gap-2">
            <span className={`tabular-nums ${read.matches === false ? 'text-status-failed' : 'text-foreground'}`}>
              {formatLedgerValue(read.value, row.unit)}
            </span>
            {row.readStale && <StatusBadge status="STALE" label="Stale" hue="amber" />}
          </p>
          <Muted>{read.sourceName}</Muted>
          <Muted>{`${dayRange(read.from, read.to) ?? ''} · recorded ${shortDate(read.recordedAt) ?? ''}`}</Muted>
          {read.conductorValueOverReadDays !== null && read.conductorValueOverReadDays !== undefined ? (
            <Muted>{`Conductor had ${formatLedgerValue(read.conductorValueOverReadDays, row.unit)} over these days`}</Muted>
          ) : (
            <Muted>Cannot be compared with Conductor</Muted>
          )}
        </>
      ) : (
        <>
          <p className="text-muted-foreground">—</p>
          <Muted>No platform read yet</Muted>
        </>
      )}
      {canRecord && (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="mt-1 -ml-2 h-7 px-2 text-xs"
          aria-label={`Record a read for ${row.label}`}
          onClick={onRecord}
        >
          Record a read
        </Button>
      )}
    </>
  )
}

function LedgerRow({ row, canRecord, onRecord }: { row: VerificationRow; canRecord: boolean; onRecord: () => void }) {
  return (
    <tr className="align-top">
      <th scope="row" className="px-3 py-3 text-left font-normal">
        <p className="text-sm font-medium text-foreground">{row.label}</p>
        <code className="font-mono text-xs text-muted-foreground">{row.metricRef}</code>
      </th>
      <td className="px-3 py-3">
        <ConductorCell row={row} />
      </td>
      <td className="px-3 py-3">
        <BackendCell row={row} />
      </td>
      <td className="px-3 py-3">
        <PlatformCell row={row} canRecord={canRecord} onRecord={onRecord} />
      </td>
      <td className="max-w-[18rem] px-3 py-3">
        <div className="flex flex-wrap items-center gap-2">
          <VerdictBadge verdict={row.verdict} />
          {row.mismatch && <MismatchMarker />}
        </div>
        {row.verdict === 'UNCHECKED' && <p className="mt-1 text-xs font-medium text-foreground">Not verified: no second source</p>}
        <p className="mt-1 text-xs text-muted-foreground">{row.trustNote}</p>
      </td>
    </tr>
  )
}

function SummaryStrip({ ledger }: { ledger: MarketingVerificationLedger }) {
  const s = ledger.summary
  const cells: { label: string; count: number; alert?: boolean }[] = [
    { label: VERDICT_META.BOTH.label, count: s.both },
    { label: VERDICT_META.CONNECTOR.label, count: s.connector },
    { label: VERDICT_META.BACKEND.label, count: s.backend },
    { label: VERDICT_META.PLATFORM.label, count: s.platform },
    { label: VERDICT_META.UNCHECKED.label, count: s.unchecked },
    { label: 'Mismatched', count: s.mismatched, alert: s.mismatched > 0 },
    { label: 'Stale reads', count: s.staleReads },
  ]
  return (
    <div role="group" aria-label="Verification summary" className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
      {cells.map((c) => (
        <div key={c.label} className="rounded-lg border border-border bg-card px-3 py-2">
          <span className="block text-xs text-muted-foreground">{c.label}</span>
          <span className={`text-xl font-semibold tabular-nums ${c.alert ? 'text-status-failed' : 'text-foreground'}`}>{c.count}</span>
        </div>
      ))}
    </div>
  )
}

function LedgerSkeleton() {
  return (
    <div className="space-y-4" aria-busy="true" aria-label="Loading ledger">
      <Skeleton className="h-16 w-full" />
      <Skeleton className="h-64 w-full" />
    </div>
  )
}

export function VerificationLedger({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const { can } = usePermissions()
  // The API lets ADMIN and CREATOR record reads: the same people who may manage marketing targets.
  const canRecord = can('marketing.target.manage')

  const [range, setRange] = useState<LedgerWindow>(DEFAULT_LEDGER_WINDOW)
  const [data, setData] = useState<MarketingVerificationLedger | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [onlyProblems, setOnlyProblems] = useState(false)
  const [recording, setRecording] = useState<{ channel: string; metric: string } | 'any' | null>(null)
  const latest = useRef(0)

  const load = useCallback(
    (silent = false) => {
      if (!accessToken) return
      const request = ++latest.current
      setError(null)
      if (!silent) setData(null)
      getVerificationLedger(projectId, accessToken, range)
        .then((ledger) => {
          if (request === latest.current) setData(ledger)
        })
        .catch((err) => {
          if (request === latest.current) setError(apiErrorMessage(err, 'Could not load the ledger — please try again.'))
        })
    },
    [projectId, accessToken, range],
  )

  useEffect(() => {
    load()
  }, [load])

  const rows = useMemo(() => (data?.rows ?? []).filter((r) => !onlyProblems || isProblem(r)), [data, onlyProblems])
  const windowRange = data ? dayRange(data.window.from, data.window.to) : null

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
        <Tabs
          ariaLabel="Time window"
          items={WINDOW_TABS}
          value={range}
          onValueChange={(v) => isLedgerWindow(v) && setRange(v)}
        />
        {windowRange && <p className="text-xs text-muted-foreground">{`${windowRange} · settled days only`}</p>}
        <div className="ml-auto flex flex-wrap items-center gap-3">
          <Checkbox checked={onlyProblems} onCheckedChange={setOnlyProblems} label="Only problems" />
          {canRecord && (
            <Button type="button" size="sm" variant="outline" disabled={!data || data.rows.length === 0} onClick={() => setRecording('any')}>
              Record a read
            </Button>
          )}
        </div>
      </div>

      {error && (
        <Alert variant="destructive">
          <div className="flex flex-wrap items-center gap-3">
            <span>{error}</span>
            <Button size="sm" variant="outline" onClick={() => load()}>
              Retry
            </Button>
          </div>
        </Alert>
      )}

      {!data && !error && <LedgerSkeleton />}

      {data && data.rows.length === 0 && (
        <EmptyState
          icon={ShieldCheckIcon}
          title="Nothing to verify yet"
          description="Connect a channel and its metrics appear here, each with the figure to trust."
        />
      )}

      {data && data.rows.length > 0 && (
        <>
          <SummaryStrip ledger={data} />
          {rows.length === 0 ? (
            <p className="rounded-lg border border-border bg-card px-4 py-6 text-sm text-muted-foreground">No problems in this window.</p>
          ) : (
            <div className="overflow-x-auto rounded-lg border border-border bg-card">
              <table className="w-full min-w-[56rem] text-sm">
                <caption className="sr-only">Verification ledger</caption>
                <thead className="bg-muted/50 text-xs text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-3 py-2 text-left font-medium">Metric</th>
                    <th scope="col" className="px-3 py-2 text-left font-medium">Conductor</th>
                    <th scope="col" className="px-3 py-2 text-left font-medium">Backend</th>
                    <th scope="col" className="px-3 py-2 text-left font-medium">Latest platform read</th>
                    <th scope="col" className="px-3 py-2 text-left font-medium">Verdict</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border">
                  {rows.map((row) => (
                    <LedgerRow
                      key={row.metricRef}
                      row={row}
                      canRecord={canRecord}
                      onRecord={() => setRecording({ channel: row.channel, metric: row.metric })}
                    />
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      )}

      <RecordReadDialog
        open={recording !== null}
        onOpenChange={(open) => !open && setRecording(null)}
        projectId={projectId}
        rows={data?.rows ?? []}
        initial={recording && recording !== 'any' ? recording : undefined}
        onRecorded={() => load(true)}
      />
    </div>
  )
}
