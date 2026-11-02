'use client'

// Now / Next / Later: top-level PRDs bucketed by roadmapBucket, grouped under their milestone. Read-only:
// priority and milestone are edited on the item itself, so there is no drag-and-drop.

import Link from 'next/link'
import { useMemo, useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { StatusBadge } from '@/components/ui/status-badge'
import { ProgressBar } from '@/components/roadmap/ProgressBar'
import type { Issue } from '@/components/workitems/listTypes'
import { nextOpenMilestoneId, parseTargetOn, roadmapBucket, startOfToday, type RoadmapBucket } from '@/lib/roadmap'
import type { Milestone } from '@/types/workItem'

const COLUMNS: { bucket: RoadmapBucket; title: string; hint: string }[] = [
  { bucket: 'now', title: 'Now', hint: 'In progress, or due within two weeks' },
  { bucket: 'next', title: 'Next', hint: 'Ready to start and urgent or up next' },
  { bucket: 'later', title: 'Later', hint: 'Everything else that is open' },
]

const NO_MILESTONE = '__none__'

interface Group {
  key: string
  name: string
  items: Issue[]
}

/** Items grouped by milestone in target-date order, "No milestone" last. */
function groupByMilestone(items: Issue[], milestonesById: Record<string, Milestone>): Group[] {
  const groups = new Map<string, Group>()
  for (const item of items) {
    const key = item.milestoneId && milestonesById[item.milestoneId] ? item.milestoneId : NO_MILESTONE
    const name = key === NO_MILESTONE ? 'No milestone' : milestonesById[key].name
    if (!groups.has(key)) groups.set(key, { key, name, items: [] })
    groups.get(key)!.items.push(item)
  }
  // Undated milestones sort after dated ones, but still ahead of "No milestone".
  const dated = (g: Group) => parseTargetOn(milestonesById[g.key].targetOn) ?? Number.MAX_SAFE_INTEGER
  return [...groups.values()].sort((a, b) => {
    if (a.key === NO_MILESTONE) return 1
    if (b.key === NO_MILESTONE) return -1
    return dated(a) - dated(b) || a.name.localeCompare(b.name)
  })
}

function RoadmapCard({ item, href }: { item: Issue; href: string | null }) {
  const body = (
    <>
      <div className="flex items-center gap-2 text-xs">
        <span className="font-mono text-muted-foreground">{item.displayId}</span>
        {item.priority && <Badge variant="outline">{item.priority}</Badge>}
        <StatusBadge status={item.status} className="ml-auto" />
      </div>
      <p className="mt-1.5 text-sm font-medium text-foreground line-clamp-2">{item.title}</p>
      {item.progress && item.progress.total > 0 && (
        <div className="mt-2 flex items-center gap-2">
          <ProgressBar done={item.progress.done} total={item.progress.total} label={`${item.displayId} progress`} />
          <span className="shrink-0 text-xs text-muted-foreground">
            {item.progress.done}/{item.progress.total}
          </span>
        </div>
      )}
    </>
  )
  const cls = 'block rounded-md border border-border bg-surface p-3'
  return href ? (
    <Link href={href} className={`${cls} transition-colors hover:bg-surface-3`}>
      {body}
    </Link>
  ) : (
    <div className={cls}>{body}</div>
  )
}

export function NowNextLaterBoard({
  items,
  milestones,
  categoryOf,
  hrefOf,
  today: todayProp,
}: {
  items: Issue[]
  milestones: Milestone[]
  categoryOf: (item: Issue) => string
  hrefOf: (item: Issue) => string | null
  today?: Date
}) {
  // Once per mount: a fresh `new Date()` each render would defeat the memo below.
  const [mountedToday] = useState(startOfToday)
  const today = todayProp ?? mountedToday
  const columns = useMemo(() => {
    const milestonesById = Object.fromEntries(milestones.map((m) => [m.id, m]))
    const nextMilestoneId = nextOpenMilestoneId(milestonesById, today)
    const buckets: Record<RoadmapBucket, Issue[]> = { now: [], next: [], later: [] }
    for (const item of items) {
      const bucket = roadmapBucket(item, milestonesById, today, categoryOf, nextMilestoneId)
      if (bucket) buckets[bucket].push(item)
    }
    return COLUMNS.map((c) => ({ ...c, groups: groupByMilestone(buckets[c.bucket], milestonesById), count: buckets[c.bucket].length }))
  }, [items, milestones, categoryOf, today])

  return (
    <div className="grid gap-4 md:grid-cols-3">
      {columns.map((col) => (
        <section key={col.bucket} aria-label={col.title} className="rounded-lg border border-border bg-surface-2">
          <header className="flex items-baseline justify-between gap-2 border-b border-border px-3 py-2">
            <h2 className="text-sm font-semibold text-foreground">
              {col.title} <span className="font-normal text-muted-foreground">{col.count}</span>
            </h2>
            <span className="truncate text-xs text-muted-foreground">{col.hint}</span>
          </header>
          {col.groups.length === 0 ? (
            <p className="px-3 py-6 text-center text-sm text-muted-foreground">Nothing here</p>
          ) : (
            col.groups.map((g) => (
              <div key={g.key}>
                <h3 className="sticky top-0 z-10 border-b border-border bg-surface-2 px-3 py-1.5 text-xs font-semibold uppercase tracking-wide text-muted-foreground">
                  {g.name}
                </h3>
                <div className="space-y-2 p-3">
                  {g.items.map((item) => (
                    <RoadmapCard key={item.id} item={item} href={hrefOf(item)} />
                  ))}
                </div>
              </div>
            ))
          )}
        </section>
      ))}
    </div>
  )
}
