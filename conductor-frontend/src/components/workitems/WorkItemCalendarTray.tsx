'use client'

// COND-23: the side rail of the Work Item calendar — everything still waiting on a date.
//
// The grid can only place an item that carries a date, so without this rail an undated item would
// simply vanish from the view. The two surfaces together hold every item exactly once: an item with
// a usable `scheduledFor` sits on its day cell, one without sits here, and an item the Workflow has
// already marked finished needs neither.
//
// Terminal-ness is read off the bound Workflow's own status metadata (the statechart's `terminal`
// flag, or the terminal category) — never a hardcoded list of status ids, which would bind this
// component to one Workflow's vocabulary.

import { useMemo } from 'react'
import Link from 'next/link'
import { CalendarCheck } from 'lucide-react'
import { EmptyState } from '@/components/ui/empty-state'
import { statusHueClasses } from '@/components/ui/status-badge'
import { pluralizeNoun, statusHue, statusMeta, workItemDetailPath } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'
import type { CalendarWorkItem } from './WorkItemCalendarView'

/** True when the Workflow marks this status an end state, by explicit flag or by category. */
function isTerminalStatus(workflowView: WorkflowView | undefined, statusId: string): boolean {
  const status = workflowView?.statuses.find((s) => s.id === statusId)
  if (!status) return false
  return status.terminal === true || status.category === 'terminal'
}

/** True when the item carries a date the grid can actually resolve to a day cell. */
function hasResolvableDate(issue: CalendarWorkItem): boolean {
  if (!issue.scheduledFor) return false
  return !Number.isNaN(new Date(issue.scheduledFor).getTime())
}

/** The exact complement of what the grid shows: still live, still without a usable date. */
function selectUnscheduled(
  issues: CalendarWorkItem[],
  workflowView: WorkflowView | undefined,
): CalendarWorkItem[] {
  return issues.filter(
    (issue) => !hasResolvableDate(issue) && !isTerminalStatus(workflowView, issue.status),
  )
}

/**
 * The rail beside the month grid, listing every live Work Item the grid cannot place. Takes the same
 * unfiltered `issues` the grid takes and selects its own rows, so neither surface can drift from the
 * other's idea of what belongs where.
 */
export function WorkItemCalendarTray({
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
  const unscheduled = useMemo(() => selectUnscheduled(issues, workflowView), [issues, workflowView])

  const plural = pluralizeNoun(noun)

  return (
    <aside data-testid="work-item-calendar-tray" className="w-60 shrink-0">
      <div className="flex items-center gap-2 mb-3 h-7">
        <h2 className="text-[11.5px] font-semibold uppercase tracking-wider text-muted-foreground">
          Unscheduled {plural}
        </h2>
        {unscheduled.length > 0 && (
          <span
            data-testid="tray-count"
            className="rounded-full bg-surface-raised px-1.5 text-[11.5px] font-medium leading-5 text-muted-foreground"
          >
            {unscheduled.length}
          </span>
        )}
      </div>

      <div className="rounded-lg border border-border bg-card">
        {unscheduled.length === 0 ? (
          <div data-testid="tray-empty">
            <EmptyState
              icon={CalendarCheck}
              title={`Every ${noun.toLowerCase()} has a date`}
              description={`${plural} without a scheduled date wait here.`}
              className="px-3 py-8"
            />
          </div>
        ) : (
          <div className="max-h-[36rem] overflow-y-auto p-1 flex flex-col gap-0.5">
            {unscheduled.map((issue) => {
              const meta = statusMeta(workflowView, issue.status)
              const hue = statusHueClasses(statusHue(issue.status, meta.category))
              return (
                <Link
                  key={issue.id}
                  data-testid={`tray-item-${issue.id}`}
                  href={workItemDetailPath(projectId, area, noun, issue.displayId ?? '')}
                  title={`${issue.displayId ? `${issue.displayId} · ` : ''}${issue.title} — ${meta.label}`}
                  className="flex items-start gap-2 rounded-md px-2 py-1.5 transition-colors hover:bg-surface-3"
                >
                  <span
                    data-testid={`tray-dot-${issue.id}`}
                    className={`mt-1.5 h-1.5 w-1.5 shrink-0 rounded-full ${hue.dot}`}
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-[13px] text-foreground">{issue.title}</span>
                    <span className="block truncate text-[11.5px] text-muted-foreground">
                      {issue.displayId && <span className="font-mono">{issue.displayId} · </span>}
                      {meta.label}
                    </span>
                  </span>
                </Link>
              )
            })}
          </div>
        )}
      </div>
    </aside>
  )
}
