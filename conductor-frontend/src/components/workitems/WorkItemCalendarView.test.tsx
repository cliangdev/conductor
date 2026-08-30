import { readFileSync } from 'fs'
import { resolve } from 'path'
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, within, fireEvent } from '@testing-library/react'
import type { WorkflowView } from '@/types/workItem'

import { WorkItemCalendarView, type CalendarWorkItem } from './WorkItemCalendarView'

// A deliberately non-engineering, non-marketing Workflow: the calendar must read its statuses and
// its noun straight off the Workflow, with no domain vocabulary of its own.
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

function renderCalendar(issues: CalendarWorkItem[], noun = 'Story') {
  return render(
    <WorkItemCalendarView
      projectId="proj-1"
      area="EDITORIAL"
      noun={noun}
      workflowView={VIEW}
      issues={issues}
    />
  )
}

function day(key: string) {
  return screen.getByTestId(`calendar-day-${key}`)
}

beforeEach(() => {
  // Mid-month, mid-day: the anchor month reads as March 2026 in every timezone the runner might use.
  vi.useFakeTimers({ shouldAdvanceTime: true })
  vi.setSystemTime(new Date('2026-03-15T12:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('scheduled items on the grid', () => {
  it('renders each scheduled item on its fire date', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' }),
      item({ id: '2', scheduledFor: '2026-03-20T09:30:00Z' }),
    ])

    expect(within(day('2026-03-05')).getByTestId('calendar-chip-1')).toBeInTheDocument()
    expect(within(day('2026-03-20')).getByTestId('calendar-chip-2')).toBeInTheDocument()
    expect(within(day('2026-03-05')).queryByTestId('calendar-chip-2')).not.toBeInTheDocument()
  })

  it('colors a chip from the design-system status ramp for its status category', () => {
    renderCalendar([
      item({ id: '1', status: 'DONE', scheduledFor: '2026-03-05T15:00:00Z' }),
      item({ id: '2', status: 'IN_PROGRESS', scheduledFor: '2026-03-06T15:00:00Z' }),
      item({ id: '3', status: 'DRAFT', scheduledFor: '2026-03-09T15:00:00Z' }),
    ])

    expect(screen.getByTestId('calendar-chip-1').className).toContain('status-done')
    expect(screen.getByTestId('calendar-chip-2').className).toContain('status-progress')
    expect(screen.getByTestId('calendar-chip-3').className).toContain('status-draft')
  })

  it('buckets an item by its own schedule timezone, not the viewer local zone', () => {
    // 2026-03-06T02:00Z is still March 5 in New York — the item was scheduled for the 5th there.
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-06T02:00:00Z', scheduleTimezone: 'America/New_York' }),
    ])

    expect(within(day('2026-03-05')).getByTestId('calendar-chip-1')).toBeInTheDocument()
  })

  it('links a chip to the Work Item detail route', () => {
    renderCalendar([item({ id: '1', displayId: 'ED-42', scheduledFor: '2026-03-05T15:00:00Z' })])

    expect(screen.getByTestId('calendar-chip-1')).toHaveAttribute(
      'href',
      '/app/projects/proj-1/editorial/stories/ED-42'
    )
  })

  it('leaves an item with no scheduledFor off the grid', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' }),
      item({ id: '2', scheduledFor: null }),
      item({ id: '3' }),
    ])

    expect(screen.getByTestId('calendar-chip-1')).toBeInTheDocument()
    expect(screen.queryByTestId('calendar-chip-2')).not.toBeInTheDocument()
    expect(screen.queryByTestId('calendar-chip-3')).not.toBeInTheDocument()
  })

  it('ignores an unparseable scheduledFor rather than throwing', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: 'not-a-date' }),
      item({ id: '2', scheduledFor: '2026-03-05T15:00:00Z' }),
    ])

    expect(screen.queryByTestId('calendar-chip-1')).not.toBeInTheDocument()
    expect(within(day('2026-03-05')).getByTestId('calendar-chip-2')).toBeInTheDocument()
  })
})

describe('overflow handling', () => {
  const busy: CalendarWorkItem[] = [1, 2, 3, 4, 5].map((n) =>
    item({ id: String(n), scheduledFor: `2026-03-10T0${n}:00:00Z` })
  )

  it('shows an overflow indicator when a day holds more items than fit', () => {
    renderCalendar(busy)
    const cell = day('2026-03-10')

    expect(within(cell).getByTestId('calendar-chip-1')).toBeInTheDocument()
    expect(within(cell).getByTestId('calendar-chip-2')).toBeInTheDocument()
    expect(within(cell).queryByTestId('calendar-chip-3')).not.toBeInTheDocument()
    expect(within(cell).getByTestId('calendar-overflow-2026-03-10')).toHaveTextContent('+3 more')
  })

  it('expands the day when the overflow indicator is clicked', () => {
    renderCalendar(busy)
    const cell = day('2026-03-10')

    fireEvent.click(within(cell).getByTestId('calendar-overflow-2026-03-10'))

    expect(within(cell).getByTestId('calendar-chip-5')).toBeInTheDocument()
    expect(within(cell).queryByTestId('calendar-overflow-2026-03-10')).not.toBeInTheDocument()
  })

  it('shows every chip on a day that fits without overflow', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-11T01:00:00Z' }),
      item({ id: '2', scheduledFor: '2026-03-11T02:00:00Z' }),
      item({ id: '3', scheduledFor: '2026-03-11T03:00:00Z' }),
    ])
    const cell = day('2026-03-11')

    expect(within(cell).getByTestId('calendar-chip-3')).toBeInTheDocument()
    expect(within(cell).queryByTestId('calendar-overflow-2026-03-11')).not.toBeInTheDocument()
  })
})

describe('month navigation', () => {
  const spread = [
    item({ id: '1', scheduledFor: '2026-02-18T15:00:00Z' }),
    item({ id: '2', scheduledFor: '2026-03-05T15:00:00Z' }),
    item({ id: '3', scheduledFor: '2026-04-07T15:00:00Z' }),
  ]

  it('opens on the current month', () => {
    renderCalendar(spread)
    expect(screen.getByTestId('calendar-month-label')).toHaveTextContent('March 2026')
    expect(screen.getByTestId('calendar-chip-2')).toBeInTheDocument()
  })

  it('moves the grid back a month and re-buckets items', () => {
    renderCalendar(spread)

    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }))

    expect(screen.getByTestId('calendar-month-label')).toHaveTextContent('February 2026')
    expect(within(day('2026-02-18')).getByTestId('calendar-chip-1')).toBeInTheDocument()
    expect(screen.queryByTestId('calendar-chip-2')).not.toBeInTheDocument()
  })

  it('moves the grid forward a month and re-buckets items', () => {
    renderCalendar(spread)

    fireEvent.click(screen.getByRole('button', { name: 'Next month' }))

    expect(screen.getByTestId('calendar-month-label')).toHaveTextContent('April 2026')
    expect(within(day('2026-04-07')).getByTestId('calendar-chip-3')).toBeInTheDocument()
    expect(screen.queryByTestId('calendar-chip-2')).not.toBeInTheDocument()
  })

  it('crosses a year boundary correctly', () => {
    renderCalendar(spread)
    for (let i = 0; i < 10; i++) fireEvent.click(screen.getByRole('button', { name: 'Next month' }))
    expect(screen.getByTestId('calendar-month-label')).toHaveTextContent('January 2027')
  })

  it('returns to the current month via Today', () => {
    renderCalendar(spread)

    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }))
    fireEvent.click(screen.getByRole('button', { name: 'Today' }))

    expect(screen.getByTestId('calendar-month-label')).toHaveTextContent('March 2026')
  })
})

describe('empty state', () => {
  it('names the Workflow noun when nothing is scheduled', () => {
    renderCalendar([item({ id: '1', scheduledFor: null })], 'Story')

    expect(screen.getByText(/no scheduled stories/i)).toBeInTheDocument()
    expect(screen.queryByTestId('work-item-calendar-grid')).not.toBeInTheDocument()
  })

  it('keeps the grid when items are scheduled outside the visible month', () => {
    renderCalendar([item({ id: '1', scheduledFor: '2026-07-04T15:00:00Z' })])

    expect(screen.getByTestId('work-item-calendar-grid')).toBeInTheDocument()
    expect(screen.queryByText(/no scheduled stories/i)).not.toBeInTheDocument()
  })
})

describe('workflow-agnostic', () => {
  const source = readFileSync(
    resolve(__dirname, 'WorkItemCalendarView.tsx'),
    'utf8'
  )

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

  it('reads only the generic scheduling fields off a Work Item', () => {
    renderCalendar([item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' })], 'Announcement')
    // The noun comes from the caller, never from the component.
    expect(screen.getByTestId('calendar-chip-1')).toBeInTheDocument()
    expect(source).toContain('scheduledFor')
    expect(source).toContain('scheduleTimezone')
  })

  it('falls back to the raw status hue when no Workflow view has loaded', () => {
    render(
      <WorkItemCalendarView
        projectId="proj-1"
        area="EDITORIAL"
        noun="Story"
        issues={[item({ id: '1', status: 'DONE', scheduledFor: '2026-03-05T15:00:00Z' })]}
      />
    )
    expect(screen.getByTestId('calendar-chip-1').className).toContain('status-done')
  })
})

describe('the unscheduled tray beside the grid', () => {
  function grid() {
    return screen.getByTestId('work-item-calendar-grid')
  }

  function tray() {
    return screen.getByTestId('work-item-calendar-tray')
  }

  it('puts an unscheduled non-terminal item in the tray and on no day cell', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' }),
      item({ id: '2', scheduledFor: null }),
    ])

    expect(within(tray()).getByTestId('tray-item-2')).toBeInTheDocument()
    expect(within(grid()).queryByTestId('calendar-chip-2')).not.toBeInTheDocument()
  })

  it('keeps a scheduled item on the grid and out of the tray', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' }),
      item({ id: '2', scheduledFor: null }),
    ])

    expect(within(day('2026-03-05')).getByTestId('calendar-chip-1')).toBeInTheDocument()
    expect(within(tray()).queryByTestId('tray-item-1')).not.toBeInTheDocument()
  })

  it('leaves a terminal unscheduled item out of both the grid and the tray', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' }),
      item({ id: '2', status: 'DONE', scheduledFor: null }),
    ])

    expect(within(grid()).queryByTestId('calendar-chip-2')).not.toBeInTheDocument()
    expect(within(tray()).queryByTestId('tray-item-2')).not.toBeInTheDocument()
  })

  it('places every non-terminal item exactly once across the grid and the tray', () => {
    const issues = [
      item({ id: '1', status: 'DRAFT', scheduledFor: '2026-03-05T15:00:00Z' }),
      item({ id: '2', status: 'IN_PROGRESS', scheduledFor: null }),
      item({ id: '3', status: 'DRAFT' }),
      item({ id: '4', status: 'IN_PROGRESS', scheduledFor: '2026-03-20T09:30:00Z' }),
      item({ id: '5', status: 'DRAFT', scheduledFor: '2026-03-06T02:00:00Z', scheduleTimezone: 'America/New_York' }),
    ]
    renderCalendar(issues)

    for (const issue of issues) {
      const placements =
        screen.queryAllByTestId(`calendar-chip-${issue.id}`).length +
        screen.queryAllByTestId(`tray-item-${issue.id}`).length
      expect(placements, `${issue.id} must be placed exactly once`).toBe(1)
    }
  })

  it('shows the tray empty state when everything is scheduled', () => {
    renderCalendar([item({ id: '1', scheduledFor: '2026-03-05T15:00:00Z' })])

    expect(within(tray()).getByTestId('tray-empty')).toBeInTheDocument()
  })

  it('renders the tray even when nothing at all is scheduled', () => {
    renderCalendar([item({ id: '1', scheduledFor: null })])

    expect(screen.queryByTestId('work-item-calendar-grid')).not.toBeInTheDocument()
    expect(within(tray()).getByTestId('tray-item-1')).toBeInTheDocument()
  })
})

describe('schedule timezone on a chip', () => {
  const VIEWER_ZONE = new Intl.DateTimeFormat().resolvedOptions().timeZone
  // A zone that is never the runner's own, so the "and here is your local time" half is exercised.
  const OTHER_ZONE = VIEWER_ZONE === 'America/Denver' ? 'Pacific/Auckland' : 'America/Denver'

  it('names the schedule timezone the item carries', () => {
    // 16:00Z on 2026-03-05 is 09:00 in America/Denver (MST, before that year's DST change).
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T16:00:00Z', scheduleTimezone: 'America/Denver' }),
    ])

    const title = screen.getByTestId('calendar-chip-1').getAttribute('title') ?? ''
    expect(title).toContain('America/Denver')
    expect(title).toContain('9:00')
  })

  it('adds the viewer-local rendering when the two zones differ', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T16:00:00Z', scheduleTimezone: OTHER_ZONE }),
    ])

    const localTime = new Intl.DateTimeFormat(undefined, {
      dateStyle: 'medium',
      timeStyle: 'short',
      timeZone: VIEWER_ZONE,
    }).format(new Date('2026-03-05T16:00:00Z'))

    const title = screen.getByTestId('calendar-chip-1').getAttribute('title') ?? ''
    expect(title).toContain(OTHER_ZONE)
    expect(title).toContain(localTime)
  })

  it('falls back to the viewer zone when the item carries none', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T16:00:00Z', scheduleTimezone: null }),
    ])

    expect(screen.getByTestId('calendar-chip-1').getAttribute('title')).toContain(VIEWER_ZONE)
  })

  it('falls back to the viewer zone on an unusable zone id rather than throwing', () => {
    renderCalendar([
      item({ id: '1', scheduledFor: '2026-03-05T16:00:00Z', scheduleTimezone: 'Not/AZone' }),
    ])

    expect(screen.getByTestId('calendar-chip-1').getAttribute('title')).toContain(VIEWER_ZONE)
  })

  it('still names the item and its status in the tooltip', () => {
    renderCalendar([
      item({ id: '1', title: 'A quiet morning', status: 'IN_PROGRESS', scheduledFor: '2026-03-05T16:00:00Z' }),
    ])

    const title = screen.getByTestId('calendar-chip-1').getAttribute('title') ?? ''
    expect(title).toContain('ED-1')
    expect(title).toContain('A quiet morning')
    expect(title).toContain('In Progress')
  })
})
