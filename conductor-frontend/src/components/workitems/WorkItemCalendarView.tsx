'use client'

// COND-23: the generic, workflow-scoped month calendar for a Workflow's Work Items.
//
// Deliberately domain-free. It keys on the generic `scheduledFor` / `scheduleTimezone` fields that
// every Work Item carries, and takes every label and color from the bound Workflow (noun, statuses,
// status ramp hues). Any Workflow that declares `default_view: calendar` gets this view for free —
// there is no vocabulary here that belongs to one area.
//
// Layout note: the grid is rendered inside a horizontal flex row so a side rail (the unscheduled
// tray, T8.2) can be dropped in beside it without restructuring this component.

import { useMemo, useState } from 'react'
import Link from 'next/link'
import { CalendarOff, ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EmptyState } from '@/components/ui/empty-state'
import { statusHueClasses } from '@/components/ui/status-badge'
import { pluralizeNoun, statusHue, statusMeta, workItemDetailPath } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'

/**
 * The slice of a Work Item the calendar needs. Structurally satisfied by the list surface's
 * `IssueWithReviewers`, so callers pass their existing rows straight through.
 */
export interface CalendarWorkItem {
  id: string
  title: string
  status: string
  displayId?: string
  /** ISO-8601 instant the item is scheduled for. Absent/null items never appear on the grid. */
  scheduledFor?: string | null
  /** IANA zone the item was scheduled in; the day it lands on is resolved in this zone. */
  scheduleTimezone?: string | null
}

/** Chips shown before a day collapses into a "+N more" affordance. */
const VISIBLE_PER_DAY = 3

/** `YYYY-MM-DD` for a Date, read in the browser's local calendar. */
function localDayKey(date: Date): string {
  const month = `${date.getMonth() + 1}`.padStart(2, '0')
  const day = `${date.getDate()}`.padStart(2, '0')
  return `${date.getFullYear()}-${month}-${day}`
}

/**
 * `YYYY-MM-DD` for an instant, read in `timeZone` (the zone the item was scheduled in) so an item
 * lands on the day its author meant, not the day it happens to be for the viewer.
 */
function zonedDayKey(iso: string, timeZone?: string | null): string | null {
  const instant = new Date(iso)
  if (Number.isNaN(instant.getTime())) return null
  const parts: Intl.DateTimeFormatOptions = { year: 'numeric', month: '2-digit', day: '2-digit' }
  try {
    return new Intl.DateTimeFormat('en-CA', { ...parts, timeZone: timeZone ?? undefined }).format(instant)
  } catch {
    // Unknown/garbage zone id — fall back to the viewer's own calendar rather than dropping the item.
    return new Intl.DateTimeFormat('en-CA', parts).format(instant)
  }
}

function startOfMonth(date: Date): Date {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

function addMonths(anchor: Date, delta: number): Date {
  return new Date(anchor.getFullYear(), anchor.getMonth() + delta, 1)
}

/** Whole weeks (Sunday-first) covering `anchor`'s month, including the leading/trailing spill days. */
function buildMonthWeeks(anchor: Date): Date[][] {
  const year = anchor.getFullYear()
  const month = anchor.getMonth()
  const lead = new Date(year, month, 1).getDay()
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const cellCount = Math.ceil((lead + daysInMonth) / 7) * 7

  const weeks: Date[][] = []
  for (let i = 0; i < cellCount; i += 7) {
    weeks.push(Array.from({ length: 7 }, (_, d) => new Date(year, month, 1 - lead + i + d)))
  }
  return weeks
}

const MONTH_LABEL = new Intl.DateTimeFormat(undefined, { month: 'long', year: 'numeric' })
const WEEKDAY_LABEL = new Intl.DateTimeFormat(undefined, { weekday: 'short' })
const DAY_LABEL = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric' })

// A known Sunday, used only to render the seven weekday column headings in the viewer's locale.
const WEEKDAY_HEADINGS = Array.from({ length: 7 }, (_, i) => WEEKDAY_LABEL.format(new Date(2024, 0, 7 + i)))

/** Items grouped by the day key they fall on, each day sorted by scheduled time ascending. */
function bucketByDay(issues: CalendarWorkItem[]): Map<string, CalendarWorkItem[]> {
  const scheduled = issues
    .map((issue) => ({
      issue,
      key: issue.scheduledFor ? zonedDayKey(issue.scheduledFor, issue.scheduleTimezone) : null,
    }))
    .filter((entry): entry is { issue: CalendarWorkItem; key: string } => entry.key !== null)
    .sort((a, b) => (a.issue.scheduledFor ?? '').localeCompare(b.issue.scheduledFor ?? ''))

  const byDay = new Map<string, CalendarWorkItem[]>()
  for (const { issue, key } of scheduled) {
    const bucket = byDay.get(key)
    if (bucket) bucket.push(issue)
    else byDay.set(key, [issue])
  }
  return byDay
}

/**
 * A month grid of a Workflow's Work Items, placed on their `scheduledFor` day and colored by the
 * Workflow's own status ramp. Items with no `scheduledFor` are simply absent from the grid.
 */
export function WorkItemCalendarView({
  projectId,
  area,
  noun,
  workflowView,
  issues,
}: {
  projectId: string
  area: string
  noun: string
  workflowView?: WorkflowView
  issues: CalendarWorkItem[]
}) {
  const [anchor, setAnchor] = useState<Date>(() => startOfMonth(new Date()))
  const [expandedDays, setExpandedDays] = useState<Set<string>>(() => new Set())

  const byDay = useMemo(() => bucketByDay(issues), [issues])
  const weeks = useMemo(() => buildMonthWeeks(anchor), [anchor])

  const plural = pluralizeNoun(noun)
  const lowerPlural = plural.toLowerCase()
  const todayKey = localDayKey(new Date())
  const anchorMonth = anchor.getMonth()

  function goToMonth(next: Date) {
    setAnchor(next)
    setExpandedDays(new Set())
  }

  function expandDay(key: string) {
    setExpandedDays((prev) => new Set(prev).add(key))
  }

  return (
    <div className="flex items-start gap-4">
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 mb-3">
          <h2 data-testid="calendar-month-label" className="text-sm font-semibold text-foreground">
            {MONTH_LABEL.format(anchor)}
          </h2>
          <div className="ml-auto flex items-center gap-1">
            <Button
              variant="outline"
              size="sm"
              aria-label="Previous month"
              onClick={() => goToMonth(addMonths(anchor, -1))}
              className="h-7 w-7 p-0"
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={() => goToMonth(startOfMonth(new Date()))}
              className="h-7 px-2 text-xs"
            >
              Today
            </Button>
            <Button
              variant="outline"
              size="sm"
              aria-label="Next month"
              onClick={() => goToMonth(addMonths(anchor, 1))}
              className="h-7 w-7 p-0"
            >
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>
        </div>

        {byDay.size === 0 ? (
          <EmptyState
            icon={CalendarOff}
            title={`No scheduled ${lowerPlural}`}
            description={`${plural} appear on this calendar once they carry a scheduled date.`}
          />
        ) : (
          <div
            data-testid="work-item-calendar-grid"
            className="border border-border rounded-lg overflow-hidden"
          >
            <div className="grid grid-cols-7 bg-surface-2 border-b border-border">
              {WEEKDAY_HEADINGS.map((label) => (
                <div
                  key={label}
                  className="px-2 py-1.5 text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground"
                >
                  {label}
                </div>
              ))}
            </div>

            {weeks.map((week) => (
              <div key={localDayKey(week[0])} className="grid grid-cols-7 border-b border-border last:border-b-0">
                {week.map((date) => {
                  const key = localDayKey(date)
                  const dayItems = byDay.get(key) ?? []
                  const inMonth = date.getMonth() === anchorMonth
                  const isToday = key === todayKey
                  const expanded = expandedDays.has(key)
                  const overflowing = !expanded && dayItems.length > VISIBLE_PER_DAY
                  const shown = overflowing ? dayItems.slice(0, VISIBLE_PER_DAY - 1) : dayItems

                  return (
                    <div
                      key={key}
                      data-testid={`calendar-day-${key}`}
                      aria-label={DAY_LABEL.format(date)}
                      className={`min-h-[92px] border-r border-border last:border-r-0 p-1 flex flex-col gap-0.5 ${
                        inMonth ? 'bg-card' : 'bg-surface-2/40'
                      }`}
                    >
                      <span
                        className={`self-start px-1 text-xs leading-5 rounded-full ${
                          isToday
                            ? 'bg-accent-soft font-semibold text-accent-foreground'
                            : inMonth
                              ? 'text-muted-foreground'
                              : 'text-foreground-subtle'
                        }`}
                      >
                        {date.getDate()}
                      </span>

                      {shown.map((issue) => {
                        const meta = statusMeta(workflowView, issue.status)
                        const hue = statusHueClasses(statusHue(issue.status, meta.category))
                        return (
                          <Link
                            key={issue.id}
                            data-testid={`calendar-chip-${issue.id}`}
                            href={workItemDetailPath(projectId, area, noun, issue.displayId ?? '')}
                            title={`${issue.displayId ? `${issue.displayId} · ` : ''}${issue.title} — ${meta.label}`}
                            className={`flex items-center gap-1 rounded-full px-1.5 py-0.5 text-xs border transition-colors hover:border-border-strong ${hue.bg} ${hue.text} ${hue.border}`}
                          >
                            <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${hue.dot}`} />
                            <span className="truncate">{issue.title}</span>
                          </Link>
                        )
                      })}

                      {overflowing && (
                        <button
                          type="button"
                          data-testid={`calendar-overflow-${key}`}
                          onClick={() => expandDay(key)}
                          className="self-start px-1.5 text-xs text-muted-foreground hover:text-foreground"
                        >
                          +{dayItems.length - shown.length} more
                        </button>
                      )}
                    </div>
                  )
                })}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
