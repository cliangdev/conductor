'use client'

// What every marketing metric counts, one definition line each, grouped by channel. Reads
// GET …/marketing/definitions; the search box filters on label, metricRef and the definition text.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { BookOpenIcon, SearchIcon } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { useAuth } from '@/contexts/AuthContext'
import { apiErrorMessage } from '@/lib/api'
import { CHANNEL_ORDER } from '@/components/marketing/overview/types'
import { definitionLine } from './definitionLine'
import { CHANNEL_LABELS, getMarketingDefinitions, type MarketingDefinitionEntry } from './types'

interface Row {
  entry: MarketingDefinitionEntry
  line: string
}

interface Group {
  channel: string
  label: string
  rows: Row[]
}

function matches(row: Row, needle: string): boolean {
  const { entry, line } = row
  return [entry.label, entry.metricRef, entry.key, line].some((text) => text.toLowerCase().includes(needle))
}

function groupByChannel(rows: Row[]): Group[] {
  const byChannel = new Map<string, Row[]>()
  for (const row of rows) {
    const channel = row.entry.channel.toUpperCase()
    byChannel.set(channel, [...(byChannel.get(channel) ?? []), row])
  }
  const rank = (channel: string) => {
    const i = (CHANNEL_ORDER as string[]).indexOf(channel)
    return i === -1 ? CHANNEL_ORDER.length : i
  }
  return [...byChannel.entries()]
    .sort(([a], [b]) => rank(a) - rank(b))
    .map(([channel, channelRows]) => ({ channel, label: CHANNEL_LABELS[channel] ?? channel, rows: channelRows }))
}

function DefinitionsSkeleton() {
  return (
    <div className="space-y-4" aria-busy="true" aria-label="Loading definitions">
      <Skeleton className="h-8 w-72" />
      <Skeleton className="h-40 w-full" />
      <Skeleton className="h-40 w-full" />
    </div>
  )
}

export function DefinitionsList({ projectId }: { projectId: string }) {
  const { accessToken } = useAuth()
  const [entries, setEntries] = useState<MarketingDefinitionEntry[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')

  const load = useCallback(() => {
    if (!accessToken) return
    setError(null)
    setEntries(null)
    getMarketingDefinitions(projectId, accessToken)
      .then((res) => setEntries(res.definitions ?? []))
      .catch((err) => setError(apiErrorMessage(err, 'Could not load the definitions — please try again.')))
  }, [projectId, accessToken])

  useEffect(() => {
    load()
  }, [load])

  const rows = useMemo<Row[]>(
    () => (entries ?? []).map((entry) => ({ entry, line: definitionLine(entry.definition) })),
    [entries],
  )
  const needle = query.trim().toLowerCase()
  const groups = useMemo(
    () => groupByChannel(needle ? rows.filter((row) => matches(row, needle)) : rows),
    [rows, needle],
  )

  if (error) {
    return (
      <Alert variant="destructive">
        <div className="flex flex-wrap items-center gap-3">
          <span>{error}</span>
          <Button size="sm" variant="outline" onClick={load}>
            Retry
          </Button>
        </div>
      </Alert>
    )
  }
  if (!entries) return <DefinitionsSkeleton />
  if (entries.length === 0) {
    return (
      <EmptyState
        icon={BookOpenIcon}
        title="No metric definitions yet"
        description="Definitions appear once a channel is connected."
      />
    )
  }

  return (
    <div className="space-y-6">
      <div className="relative max-w-sm">
        <SearchIcon className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-foreground-subtle" aria-hidden />
        <Input
          type="search"
          aria-label="Search metrics"
          placeholder="Search metrics"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          className="pl-8"
        />
      </div>

      {groups.length === 0 ? (
        <p className="rounded-lg border border-border bg-card px-4 py-6 text-sm text-muted-foreground">
          No metrics match “{query.trim()}”.
        </p>
      ) : (
        groups.map((group) => (
          <section key={group.channel} aria-label={group.label} className="space-y-2">
            <h2 className="text-sm font-semibold text-foreground">{group.label}</h2>
            <Card>
              <ul className="divide-y divide-border">
                {group.rows.map(({ entry, line }) => (
                  <li key={entry.metricRef} className="space-y-0.5 px-4 py-3">
                    <p className="flex flex-wrap items-baseline gap-x-2">
                      <span className="text-sm font-medium text-foreground">{entry.label}</span>
                      <code className="font-mono text-xs text-muted-foreground">{entry.metricRef}</code>
                    </p>
                    <p className="text-xs text-muted-foreground">{line}</p>
                  </li>
                ))}
              </ul>
            </Card>
          </section>
        ))
      )}
    </div>
  )
}
