'use client'

// Project overview: what is in flight (active PRDs), what is coming (milestones) and what needs a person
// (blocked, stale, waiting on input). Health and forecast arrive with the throughput forecast, so those
// slots render "—" until a row is given them.

import Link from 'next/link'
import { AlertTriangleIcon, ClockIcon, FlagIcon, HandIcon, TargetIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { Card, CardContent, CardHeader } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/components/ui/status-badge'
import { ProgressBar } from '@/components/roadmap/ProgressBar'
import {
  useFetched,
  useItemWorkflowLookups,
  useMilestones,
  workItemsPath,
  type Fetched,
} from '@/components/roadmap/useRoadmapData'
import type { Issue } from '@/components/workitems/listTypes'
import { parseTargetOn } from '@/lib/roadmap'
import type { Milestone } from '@/types/workItem'

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
  fetched,
  emptyCopy,
  children,
}: {
  title: string
  icon: React.ComponentType<{ className?: string }>
  fetched: Fetched<T[]>
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
      {fetched.loading ? (
        <RowSkeleton />
      ) : fetched.error || !fetched.data ? (
        <Empty>Could not load: {fetched.error ?? 'no data'}</Empty>
      ) : fetched.data.length === 0 ? (
        <Empty>{emptyCopy}</Empty>
      ) : (
        <CardContent>{children(fetched.data)}</CardContent>
      )}
    </Card>
  )
}

function ItemLink({ item, href, children }: { item: Issue; href: string | null; children: ReactNode }) {
  const cls = 'flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5 text-sm'
  return href ? (
    <Link href={href} className={`${cls} hover:bg-surface-3`}>
      {children}
    </Link>
  ) : (
    <div className={cls} data-item={item.id}>
      {children}
    </div>
  )
}

export interface ActivePrdExtras {
  /** Filled by the health update; "—" until then. */
  health?: string | null
  /** Filled by the throughput forecast; "—" until then. */
  forecast?: string | null
}

function ActivePrdRow({ item, href, extras }: { item: Issue; href: string | null; extras?: ActivePrdExtras }) {
  const progress = item.progress
  return (
    <ItemLink item={item} href={href}>
      <span className="font-mono text-xs text-muted-foreground">{item.displayId}</span>
      <span className="min-w-0 flex-1 basis-40 truncate font-medium text-foreground">{item.title}</span>
      <StatusBadge status={item.status} />
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
      <span className="text-xs text-muted-foreground" title="Health">
        {extras?.health ?? '—'}
      </span>
      <span className="text-xs text-muted-foreground" title="Forecast">
        {extras?.forecast ?? '—'}
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

function AttentionList({
  title,
  icon,
  fetched,
  emptyCopy,
  badge,
  hrefOf,
}: {
  title: string
  icon: React.ComponentType<{ className?: string }>
  fetched: Fetched<Issue[]>
  emptyCopy: string
  badge: (item: Issue) => ReactNode
  hrefOf: (item: Issue) => string | null
}) {
  return (
    <Section title={title} icon={icon} fetched={fetched} emptyCopy={emptyCopy}>
      {(items) =>
        items.map((item) => (
          <ItemLink key={item.id} item={item} href={hrefOf(item)}>
            <span className="font-mono text-xs text-muted-foreground">{item.displayId}</span>
            <span className="min-w-0 flex-1 basis-40 truncate text-foreground">{item.title}</span>
            {badge(item)}
          </ItemLink>
        ))
      }
    </Section>
  )
}

function Chip({ children }: { children: ReactNode }) {
  return <span className="rounded-full border border-border px-2 py-0.5 text-xs text-muted-foreground">{children}</span>
}

export function OverviewPanel({ projectId }: { projectId: string }) {
  const prds = useFetched<Issue[]>(workItemsPath(projectId, 'type=PRD&withProgress=true'))
  const milestones = useMilestones(projectId)
  // One backlog fetch (every item, sub-items included); blocked, stale and needs-input are read off each
  // item's `signals`. Tranche 3 replaces this with `GET /delivery-overview`, which returns them directly.
  const backlog = useFetched<Issue[]>(workItemsPath(projectId, 'parentId=any'))

  // The detail links and the terminal check need each item's Workflow, so look them up across both lists.
  const listsLoading = prds.loading || backlog.loading
  const { categoryOf, hrefOf, ready } = useItemWorkflowLookups(
    projectId,
    listsLoading ? null : [...(prds.data ?? []), ...(backlog.data ?? [])]
  )
  // Until the Workflow views are in every item reads as "open", so finished PRDs would flash as active.
  const gate = <T,>(f: Fetched<T>): Fetched<T> => (ready ? f : { ...f, loading: true })

  const activePrds: Fetched<Issue[]> = gate({ ...prds, data: prds.data?.filter((i) => categoryOf(i) !== 'terminal') ?? null })
  const upcoming: Fetched<Milestone[]> = {
    ...milestones,
    data:
      milestones.data
        ?.filter((m) => m.state === 'OPEN')
        .sort((a, b) => (parseTargetOn(a.targetOn) ?? Infinity) - (parseTargetOn(b.targetOn) ?? Infinity)) ?? null,
  }
  const fromBacklog = (keep: (i: Issue) => boolean | undefined): Fetched<Issue[]> =>
    gate({ ...backlog, data: backlog.data?.filter(keep) ?? null })
  const blocked = fromBacklog((i) => i.signals?.blocked)
  const stale = fromBacklog((i) => i.signals?.stale)
  const needsInput = fromBacklog((i) => i.signals?.needsInput)

  return (
    <div className="space-y-6">
      <Section title="Active PRDs" icon={TargetIcon} fetched={activePrds} emptyCopy="No active PRDs.">
        {(items) => items.map((item) => <ActivePrdRow key={item.id} item={item} href={hrefOf(item)} />)}
      </Section>

      <Section title="Upcoming milestones" icon={FlagIcon} fetched={upcoming} emptyCopy="No open milestones.">
        {(items) => items.map((m) => <MilestoneRow key={m.id} milestone={m} />)}
      </Section>

      <div className="grid gap-6 lg:grid-cols-3">
        <AttentionList
          title="Blocked"
          icon={AlertTriangleIcon}
          fetched={blocked}
          emptyCopy="Nothing blocked"
          hrefOf={hrefOf}
          badge={(i) => <Chip>{i.signals?.blockedBy.length ? `Blocked by ${i.signals.blockedBy.length}` : 'Blocked'}</Chip>}
        />
        <AttentionList
          title="Stale"
          icon={ClockIcon}
          fetched={stale}
          emptyCopy="Nothing stale"
          hrefOf={hrefOf}
          badge={() => <Chip>Stale</Chip>}
        />
        <AttentionList
          title="Needs input"
          icon={HandIcon}
          fetched={needsInput}
          emptyCopy="Nobody is waiting on you"
          hrefOf={hrefOf}
          badge={() => <Chip>Needs input</Chip>}
        />
      </div>
    </div>
  )
}
