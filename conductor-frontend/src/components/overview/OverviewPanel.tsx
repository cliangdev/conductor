'use client'

// Project overview: what is in flight (active PRDs, with health and forecast), what is coming
// (milestones) and what needs a person (blocked, stale, waiting on input). One call to
// GET /delivery-overview supplies all of it; the server decides what is active.

import Link from 'next/link'
import { AlertTriangleIcon, ClockIcon, FlagIcon, HandIcon, TargetIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { ProgressBar } from '@/components/roadmap/ProgressBar'
import { useDeliveryOverview, useWorkflowHrefs } from '@/components/roadmap/useRoadmapData'
import { HealthBadge } from '@/components/workitems/plan/HealthSection'
import { forecastLine, formatForecastDate } from '@/lib/delivery'
import { parseTargetOn } from '@/lib/roadmap'
import type { DeliveryActiveItem, DeliveryItemSummary, Milestone } from '@/types/workItem'

const SECTION_TITLE = 'text-sm font-semibold text-foreground'

function RowSkeleton() {
  return (
    <div className="space-y-2 p-4" aria-busy="true">
      <Skeleton className="h-5 w-full" />
      <Skeleton className="h-5 w-3/4" />
    </div>
  )
}

function Empty({ children }: { children: ReactNode }) {
  return <p className="px-4 py-6 text-center text-sm text-muted-foreground">{children}</p>
}

/** Skeleton while loading, a quiet message on error, the rows otherwise. */
function Section<T>({
  title,
  icon: Icon,
  rows,
  loading,
  error,
  emptyCopy,
  children,
}: {
  title: string
  icon: React.ComponentType<{ className?: string }>
  rows: T[] | null
  loading: boolean
  error: string | null
  emptyCopy: string
  children: (data: T[]) => ReactNode
}) {
  return (
    <Card>
      <CardHeader>
        <h2 className={`${SECTION_TITLE} flex items-center gap-2`}>
          <Icon className="h-4 w-4 text-muted-foreground" />
          {title}
        </h2>
      </CardHeader>
      {loading ? (
        <RowSkeleton />
      ) : error || !rows ? (
        <Empty>Could not load: {error ?? 'no data'}</Empty>
      ) : rows.length === 0 ? (
        <Empty>{emptyCopy}</Empty>
      ) : (
        <CardContent>{children(rows)}</CardContent>
      )}
    </Card>
  )
}

function ItemLink({ id, href, children }: { id: string; href: string | null; children: ReactNode }) {
  const cls = 'flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm'
  return href ? (
    <Link href={href} className={`${cls} hover:bg-surface-3`}>
      {children}
    </Link>
  ) : (
    <div className={cls} data-item={id}>
      {children}
    </div>
  )
}

function ActivePrdRow({ active, href, milestoneTargetOn }: { active: DeliveryActiveItem; href: string | null; milestoneTargetOn?: string | null }) {
  const { item, progress, health, forecast } = active
  return (
    <ItemLink id={item.id} href={href}>
      <span className="font-mono text-xs text-muted-foreground">{item.displayId}</span>
      <span className="min-w-0 flex-1 basis-40 truncate font-medium text-foreground">{item.title}</span>
      <StatusBadge status={item.status} label={item.statusLabel ?? undefined} />
      <span className="flex w-40 items-center gap-2">
        {progress && progress.total > 0 ? (
          <>
            <ProgressBar done={progress.done} total={progress.total} label={`${item.displayId} progress`} />
            <span className="shrink-0 text-xs text-muted-foreground">
              {progress.done}/{progress.total}
            </span>
          </>
        ) : (
          <span className="text-xs text-muted-foreground">No sub-items</span>
        )}
      </span>
      <span className="text-xs text-muted-foreground" title="Acceptance criteria met">
        {progress ? `${progress.criteriaMet}/${progress.criteriaTotal} criteria` : '—'}
      </span>
      <span title="Health">
        <HealthBadge health={health?.health} />
      </span>
      <span className="text-xs text-muted-foreground" title="Forecast">
        {forecast ? forecastLine(forecast, formatForecastDate, milestoneTargetOn) : '—'}
      </span>
    </ItemLink>
  )
}

function MilestoneRow({ milestone }: { milestone: Milestone }) {
  const p = milestone.progress
  const target = parseTargetOn(milestone.targetOn)
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm">
      <span className="min-w-0 flex-1 basis-40 truncate font-medium text-foreground">{milestone.name}</span>
      <span className="text-xs text-muted-foreground">
        {target === null ? 'No date' : new Date(target).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })}
      </span>
      <span className="flex w-40 items-center gap-2">
        <ProgressBar done={p?.leafDone ?? 0} total={p?.leafTotal ?? 0} label={`${milestone.name} progress`} />
        <span className="shrink-0 text-xs text-muted-foreground">
          {p?.leafDone ?? 0}/{p?.leafTotal ?? 0}
        </span>
      </span>
    </div>
  )
}

function Chip({ children }: { children: ReactNode }) {
  return <span className="rounded-full border border-border px-2 py-0.5 text-xs text-muted-foreground">{children}</span>
}

export function OverviewPanel({ projectId }: { projectId: string }) {
  const overview = useDeliveryOverview(projectId)
  const data = overview.data
  const { loading, error } = overview

  const slugs = [
    ...(data?.activeItems.map((a) => a.item.workflow) ?? []),
    ...(data ? [...data.attention.blocked, ...data.attention.stale, ...data.attention.needsInput].map((i) => i.workflow) : []),
  ].filter((s): s is string => !!s)
  const hrefOf = useWorkflowHrefs(projectId, slugs)

  const targetOnByMilestone = Object.fromEntries((data?.milestones ?? []).map((m) => [m.id, m.targetOn]))

  const upcoming =
    data?.milestones
      .filter((m) => m.state === 'OPEN')
      .sort((a, b) => (parseTargetOn(a.targetOn) ?? Infinity) - (parseTargetOn(b.targetOn) ?? Infinity)) ?? null

  const attention = (
    title: string,
    icon: React.ComponentType<{ className?: string }>,
    rows: DeliveryItemSummary[] | undefined,
    emptyCopy: string,
    badge: (i: DeliveryItemSummary) => ReactNode
  ) => (
    <Section title={title} icon={icon} rows={rows ?? null} loading={loading} error={error} emptyCopy={emptyCopy}>
      {(items) =>
        items.map((item) => (
          <ItemLink key={item.id} id={item.id} href={hrefOf(item)}>
            <span className="font-mono text-xs text-muted-foreground">{item.displayId}</span>
            <span className="min-w-0 flex-1 basis-40 truncate text-foreground">{item.title}</span>
            {badge(item)}
          </ItemLink>
        ))
      }
    </Section>
  )

  return (
    <div className="space-y-6">
      <Section title="Active PRDs" icon={TargetIcon} rows={data?.activeItems ?? null} loading={loading} error={error} emptyCopy="No active PRDs.">
        {(items) => items.map((a) => <ActivePrdRow key={a.item.id} active={a} href={hrefOf(a.item)} milestoneTargetOn={targetOnByMilestone[a.item.milestoneId ?? '']} />)}
      </Section>

      <Section title="Upcoming milestones" icon={FlagIcon} rows={upcoming} loading={loading} error={error} emptyCopy="No open milestones.">
        {(items) => items.map((m) => <MilestoneRow key={m.id} milestone={m} />)}
      </Section>

      <div className="grid gap-6 lg:grid-cols-3">
        {attention('Blocked', AlertTriangleIcon, data?.attention.blocked, 'Nothing blocked', (i) => <Chip>{i.blockedByCount ? `Blocked by ${i.blockedByCount}` : 'Blocked'}</Chip>)}
        {attention('Stale', ClockIcon, data?.attention.stale, 'Nothing stale', () => <Chip>Stale</Chip>)}
        {attention('Needs input', HandIcon, data?.attention.needsInput, 'Nobody is waiting on you', () => <Chip>Needs input</Chip>)}
      </div>
    </div>
  )
}
