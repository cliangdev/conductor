// Pure helpers behind the roadmap board and milestone strip (docs/prd-tracking.md). Kept free of React and
// fetching so the bucketing rules can be tested on plain objects.

import type { Milestone, WorkItemDeliveryFields } from '@/types/workItem'

export type RoadmapBucket = 'now' | 'next' | 'later'

/** The fields the helpers read; any Work Item list row satisfies it. */
export interface RoadmapItem extends WorkItemDeliveryFields {
  status: string
  createdAt: string
  workflow?: string
}

const DAY_MS = 24 * 60 * 60 * 1000

/** Milestones due within this many days (or already overdue) pull their items into Now. */
export const NOW_WINDOW_DAYS = 14

/** A `YYYY-MM-DD` target as local midnight, so "today" comparisons do not slip across time zones. */
export function parseTargetOn(targetOn: string | null | undefined): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(targetOn ?? '')
  if (!m) return null
  return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])).getTime()
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/** Local midnight today. Components take this once per mount so memoised bucketing keeps a stable input. */
export function startOfToday(): Date {
  return new Date(startOfDay(new Date()))
}

/** The open milestone with the earliest target date that is not in the past; falls back to the earliest dated open one. */
export function nextOpenMilestoneId(milestonesById: Record<string, Milestone>, today: Date): string | null {
  const todayMs = startOfDay(today)
  const open = Object.values(milestonesById)
    .filter((m) => m.state === 'OPEN' && parseTargetOn(m.targetOn) !== null)
    .sort((a, b) => parseTargetOn(a.targetOn)! - parseTargetOn(b.targetOn)!)
  const upcoming = open.find((m) => parseTargetOn(m.targetOn)! >= todayMs)
  return (upcoming ?? open[0])?.id ?? null
}

/**
 * Which roadmap column an item belongs in, or `null` when it is finished and so off the roadmap.
 * Now: being worked on, or its milestone is due within {@link NOW_WINDOW_DAYS} days (or overdue).
 * Next: ready to pick up and either urgent (P0/P1) or in the next open milestone. Later: the rest.
 */
export function roadmapBucket<T extends RoadmapItem>(
  item: T,
  milestonesById: Record<string, Milestone>,
  today: Date,
  statusCategoryOf: (item: T) => string,
  /** Pass {@link nextOpenMilestoneId} when bucketing many items, so it is computed once rather than per item. */
  nextMilestoneId: string | null = nextOpenMilestoneId(milestonesById, today)
): RoadmapBucket | null {
  const category = statusCategoryOf(item)
  if (category === 'terminal') return null
  if (category === 'in_progress') return 'now'

  // Only an OPEN milestone's date can pull an item into Now; a closed one counts as no milestone.
  const milestone = item.milestoneId ? milestonesById[item.milestoneId] : undefined
  const targetMs = milestone?.state === 'OPEN' ? parseTargetOn(milestone.targetOn) : null
  if (targetMs !== null && targetMs - startOfDay(today) <= NOW_WINDOW_DAYS * DAY_MS) return 'now'

  if (item.signals?.ready) {
    const urgent = item.priority === 'P0' || item.priority === 'P1'
    const inNextMilestone = !!item.milestoneId && item.milestoneId === nextMilestoneId
    if (urgent || inNextMilestone) return 'next'
  }
  return 'later'
}

function timeOf(value: string | null | undefined): number | null {
  if (!value) return null
  const ms = Date.parse(value)
  return Number.isNaN(ms) ? null : ms
}

/**
 * The time span a PRD's bar covers on the strip. Starts when work began (or the item was created) and
 * ends when it finished, else at the forecast P85 date, else after its appetite, else at its
 * milestone's target, else today.
 */
export function milestoneBarSpan(
  item: RoadmapItem,
  milestone: Milestone | null | undefined,
  today: Date,
  /** `YYYY-MM-DD` P85 date from the item's forecast. */
  forecastP85?: string | null
): { startMs: number; endMs: number } {
  const startedMs = timeOf(item.startedAt)
  const startMs = startedMs ?? timeOf(item.createdAt) ?? today.getTime()
  const appetiteEnd =
    startedMs !== null && item.appetiteDays != null && item.appetiteDays > 0
      ? startedMs + item.appetiteDays * DAY_MS
      : null
  const endMs = timeOf(item.completedAt) ?? parseTargetOn(forecastP85) ?? appetiteEnd ?? parseTargetOn(milestone?.targetOn) ?? today.getTime()
  return { startMs, endMs: Math.max(endMs, startMs) }
}
