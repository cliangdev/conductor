'use client'

// One bar per active PRD on a shared time axis, with a tick per milestone and a marker for today.
// CSS only: positions are percentages of the shared min/max span.

import { useMemo, useState } from 'react'
import type { Issue } from '@/components/workitems/listTypes'
import { milestoneBarSpan, parseTargetOn, startOfToday } from '@/lib/roadmap'
import type { Milestone } from '@/types/workItem'

const fmt = (ms: number) => new Date(ms).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })

export function MilestoneStrip({
  items,
  milestones,
  today: todayProp,
}: {
  /** The PRDs to draw; the caller has already dropped finished ones. */
  items: Issue[]
  milestones: Milestone[]
  today?: Date
}) {
  // Once per mount: a fresh `new Date()` each render would defeat the memo below.
  const [mountedToday] = useState(startOfToday)
  const today = todayProp ?? mountedToday
  const layout = useMemo(() => {
    const byId = Object.fromEntries(milestones.map((m) => [m.id, m]))
    // A bar built only from createdAt and today says nothing about the plan, so it does not count as dated.
    const hasDate = (i: Issue) => !!(i.startedAt || i.completedAt || parseTargetOn(byId[i.milestoneId ?? '']?.targetOn) !== null)
    const rows = items.map((item) => ({ item, span: milestoneBarSpan(item, byId[item.milestoneId ?? ''], today), dated: hasDate(item) }))
    if (!rows.some((r) => r.dated)) return null

    const ticks = milestones
      .filter((m) => m.state === 'OPEN')
      .flatMap((m) => {
        const ms = parseTargetOn(m.targetOn)
        return ms === null ? [] : [{ milestone: m, ms }]
      })
    const points = [today.getTime(), ...rows.flatMap((r) => [r.span.startMs, r.span.endMs]), ...ticks.map((t) => t.ms)]
    const min = Math.min(...points)
    const max = Math.max(...points)
    const pct = (ms: number) => (max === min ? 0 : ((ms - min) / (max - min)) * 100)
    return { rows, ticks, pct, todayPct: pct(today.getTime()) }
  }, [items, milestones, today])

  if (!layout) return null

  return (
    <div className="mb-6 rounded-lg border border-border bg-surface p-4" aria-label="Milestone timeline">
      <div className="flex gap-3">
        <div className="w-24 shrink-0 sm:w-40" />
        <div className="relative h-6 flex-1 text-xs text-muted-foreground">
          {layout.ticks.map(({ milestone, ms }) => (
            <span
              key={milestone.id}
              className="absolute top-0 -translate-x-1/2 truncate max-w-24"
              style={{ left: `${layout.pct(ms)}%` }}
              title={`${milestone.name}: ${fmt(ms)}`}
            >
              {milestone.name}
            </span>
          ))}
        </div>
      </div>
      <div className="space-y-2">
        {layout.rows.map(({ item, span }) => (
          <div key={item.id} className="flex items-center gap-3">
            <div className="w-24 shrink-0 truncate text-xs sm:w-40" title={item.title}>
              <span className="font-mono text-muted-foreground">{item.displayId}</span>{' '}
              <span className="text-foreground">{item.title}</span>
            </div>
            <div className="relative h-5 flex-1">
              {layout.ticks.map(({ milestone, ms }) => (
                <span key={milestone.id} className="absolute inset-y-0 w-px bg-border-strong" style={{ left: `${layout.pct(ms)}%` }} />
              ))}
              <span className="absolute inset-y-0 w-px bg-primary" style={{ left: `${layout.todayPct}%` }} title={`Today: ${fmt(today.getTime())}`} />
              <div
                className="absolute inset-y-1 min-w-1 rounded-full bg-primary/40"
                style={{ left: `${layout.pct(span.startMs)}%`, width: `${layout.pct(span.endMs) - layout.pct(span.startMs)}%` }}
                title={`${item.displayId}: ${fmt(span.startMs)} to ${fmt(span.endMs)}`}
              />
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
