import { readFileSync } from 'fs'
import { resolve } from 'path'
import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'

import { WorkItemCalendarTray } from './WorkItemCalendarTray'
import type { CalendarWorkItem } from './WorkItemCalendarView'

// Same deliberately non-engineering, non-marketing Workflow the grid tests use: the tray must read
// its noun and its terminal statuses straight off the Workflow.
const VIEW: WorkflowView = {
  slug: 'EDITORIAL',
  noun: 'Story',
  area: 'EDITORIAL',
  defaultView: 'calendar',
  version: 1,
  types: ['FEATURE'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_PROGRESS', label: 'In Progress', category: 'in_progress' },
    { id: 'DONE', label: 'Done', category: 'terminal' },
    // A statechart may mark a status terminal without filing it in the terminal category.
    { id: 'SHELVED', label: 'Shelved', category: 'open', terminal: true },
  ],
  transitions: [],
}

function item(overrides: Partial<CalendarWorkItem> & { id: string }): CalendarWorkItem {
  return {
    title: `Story ${overrides.id}`,
    status: 'DRAFT',
    displayId: `ED-${overrides.id}`,
    scheduleTimezone: 'UTC',
    ...overrides,
  }
}

function renderTray(issues: CalendarWorkItem[], noun = 'Story') {
  return render(
    <WorkItemCalendarTray
      projectId="proj-1"
      area="EDITORIAL"
      noun={noun}
      workflowView={VIEW}
      issues={issues}
    />
  )
}

function tray() {
  return screen.getByTestId('work-item-calendar-tray')
}

describe('what lands in the tray', () => {
  it('lists an unscheduled non-terminal item', () => {
    renderTray([item({ id: '1' }), item({ id: '2', scheduledFor: null })])

    expect(within(tray()).getByTestId('tray-item-1')).toBeInTheDocument()
    expect(within(tray()).getByTestId('tray-item-2')).toBeInTheDocument()
  })

  it('leaves out an item that already carries a scheduled date', () => {
    renderTray([item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' }), item({ id: '2' })])

    expect(within(tray()).queryByTestId('tray-item-1')).not.toBeInTheDocument()
    expect(within(tray()).getByTestId('tray-item-2')).toBeInTheDocument()
  })

  it('leaves out a terminal unscheduled item', () => {
    renderTray([item({ id: '1', status: 'DONE' }), item({ id: '2', status: 'IN_PROGRESS' })])

    expect(within(tray()).queryByTestId('tray-item-1')).not.toBeInTheDocument()
    expect(within(tray()).getByTestId('tray-item-2')).toBeInTheDocument()
  })

  it('reads terminal-ness off the Workflow status flag, not the category name', () => {
    renderTray([item({ id: '1', status: 'SHELVED' }), item({ id: '2', status: 'DRAFT' })])

    expect(within(tray()).queryByTestId('tray-item-1')).not.toBeInTheDocument()
    expect(within(tray()).getByTestId('tray-item-2')).toBeInTheDocument()
  })

  it('keeps an item whose status the Workflow does not define', () => {
    renderTray([item({ id: '1', status: 'SOMETHING_NEW' })])

    expect(within(tray()).getByTestId('tray-item-1')).toBeInTheDocument()
  })

  it('keeps every unscheduled item when no Workflow view has loaded', () => {
    render(
      <WorkItemCalendarTray
        projectId="proj-1"
        area="EDITORIAL"
        noun="Story"
        issues={[item({ id: '1', status: 'DONE' })]}
      />
    )

    expect(within(tray()).getByTestId('tray-item-1')).toBeInTheDocument()
  })

  it('keeps an item whose scheduled date cannot be read, since it reaches no day cell', () => {
    renderTray([item({ id: '1', scheduledFor: 'not-a-date' })])

    expect(within(tray()).getByTestId('tray-item-1')).toBeInTheDocument()
  })
})

describe('tray presentation', () => {
  it('names the Workflow noun in its heading', () => {
    renderTray([item({ id: '1' })], 'Announcement')

    expect(within(tray()).getByText(/unscheduled announcements/i)).toBeInTheDocument()
  })

  it('counts what it holds', () => {
    renderTray([item({ id: '1' }), item({ id: '2' }), item({ id: '3', scheduledFor: '2026-03-05T15:00:00Z' })])

    expect(within(tray()).getByTestId('tray-count')).toHaveTextContent('2')
  })

  it('shows an empty state naming the Workflow noun when everything is scheduled', () => {
    renderTray([item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' })])

    expect(within(tray()).queryByTestId('tray-item-1')).not.toBeInTheDocument()
    expect(within(tray()).getByTestId('tray-empty')).toHaveTextContent(/stories/i)
  })

  it('links a tray row to the Work Item detail route', () => {
    renderTray([item({ id: '1', displayId: 'ED-42' })])

    expect(screen.getByTestId('tray-item-1')).toHaveAttribute(
      'href',
      '/app/projects/proj-1/editorial/stories/ED-42'
    )
  })

  it('shows the item title and status label', () => {
    renderTray([item({ id: '1', title: 'A quiet morning', status: 'IN_PROGRESS' })])

    const row = screen.getByTestId('tray-item-1')
    expect(row).toHaveTextContent('A quiet morning')
    expect(row).toHaveTextContent('In Progress')
  })

  it('colors a row dot from the design-system status ramp', () => {
    renderTray([item({ id: '1', status: 'IN_PROGRESS' })])

    expect(screen.getByTestId('tray-dot-1').className).toContain('status-progress')
  })
})

describe('workflow-agnostic', () => {
  const source = readFileSync(resolve(__dirname, 'WorkItemCalendarTray.tsx'), 'utf8')

  it('carries no marketing vocabulary in the component source', () => {
    const banned = [
      /\bposts?\b/i,
      /\bfire time\b/i,
      /\bplatform/i,
      /\bcaption/i,
      /\bmarketing\b/i,
      /\binstagram\b/i,
      /\btiktok\b/i,
      /\byoutube\b/i,
      /\bfacebook\b/i,
      /\blinkedin\b/i,
      /\bpublish/i,
      /\bchannel\b/i,
    ]
    for (const pattern of banned) {
      expect(source, `source must not mention ${pattern}`).not.toMatch(pattern)
    }
  })

  it('derives terminal statuses from the Workflow rather than a hardcoded status list', () => {
    expect(source).not.toMatch(/'DONE'|"DONE"|'CLOSED'|"CLOSED"/)
    expect(source).toContain('statuses')
  })
})
