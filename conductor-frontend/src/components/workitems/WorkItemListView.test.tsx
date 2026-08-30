import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'

const push = vi.fn()
const replace = vi.fn()
let searchParamsValue = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push, replace }),
  usePathname: () => '/app/projects/proj-1/engineering/issues',
  useSearchParams: () => searchParamsValue,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token', user: { id: 'user-1' } }),
}))

const { toastErrorSpy } = vi.hoisted(() => ({ toastErrorSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/toast')>()
  return { ...actual, toastError: toastErrorSpy }
})

interface MockIssue {
  id: string
  title: string
  type: string
  status: string
  updatedAt: string
  createdAt: string
  displayId: string
  unresolvedCommentCount?: number
  assignee?: { userId: string; name: string; avatarUrl?: string | null } | null
  scheduledFor?: string | null
  scheduleTimezone?: string | null
}

let issues: MockIssue[] = []
const apiPatch = vi.fn().mockResolvedValue(undefined)
const apiPost = vi.fn().mockResolvedValue(undefined)
const apiDelete = vi.fn().mockResolvedValue(undefined)

const MEMBERS = [
  { userId: 'user-1', name: 'Ada Admin', email: 'ada@x.com', role: 'ADMIN' },
  { userId: 'user-2', name: 'Rita Reviewer', email: 'rita@x.com', role: 'REVIEWER' },
]

const TRANSITIONS: Record<string, { toStatus: string; label: string }[]> = {
  DRAFT: [{ toStatus: 'IN_PROGRESS', label: 'Start' }],
  IN_PROGRESS: [{ toStatus: 'DONE', label: 'Finish' }],
  DONE: [],
}

vi.mock('@/lib/api', () => ({
  apiGet: (url: string) => {
    if (url.includes('/work-items?workflow=')) return Promise.resolve(issues)
    if (url.includes('/members')) return Promise.resolve(MEMBERS)
    if (url.includes('/available-transitions')) {
      const issueId = url.match(/work-items\/([^/]+)\/available-transitions/)?.[1]
      const issue = issues.find((i) => i.id === issueId)
      return Promise.resolve({ workflow: 'ENGINEERING', currentStatus: issue?.status, transitions: TRANSITIONS[issue?.status ?? ''] ?? [] })
    }
    if (url.includes('/reviewers')) return Promise.resolve([])
    return Promise.resolve([])
  },
  apiPost: (...args: unknown[]) => apiPost(...args),
  apiPatch: (...args: unknown[]) => apiPatch(...args),
  apiDelete: (...args: unknown[]) => apiDelete(...args),
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))

const BASE_VIEW: WorkflowView = {
  slug: 'ENGINEERING',
  noun: 'Issue',
  area: 'ENGINEERING',
  defaultView: 'list',
  version: 1,
  types: ['PRD', 'TASK'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_PROGRESS', label: 'In Progress', category: 'in_progress' },
    { id: 'DONE', label: 'Done', category: 'terminal' },
  ],
  transitions: [],
}

// Mutable so a test can exercise a Workflow that declares a different `default_view`; reset per test.
let VIEW: WorkflowView = BASE_VIEW

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowView: () => VIEW }
})

import { WorkItemListView } from './WorkItemListView'

function baseIssues(): MockIssue[] {
  return [
    {
      id: 'i1',
      title: 'Zeta task',
      type: 'TASK',
      status: 'DRAFT',
      updatedAt: '2026-01-05T00:00:00Z',
      createdAt: '2026-01-01T00:00:00Z',
      displayId: 'COND-1',
      scheduledFor: '2026-03-04T15:00:00Z',
      scheduleTimezone: 'UTC',
    },
    {
      id: 'i2',
      title: 'Alpha prd',
      type: 'PRD',
      status: 'DRAFT',
      updatedAt: '2026-01-01T00:00:00Z',
      createdAt: '2026-01-05T00:00:00Z',
      displayId: 'COND-2',
    },
    {
      id: 'i3',
      title: 'Gamma task',
      type: 'TASK',
      status: 'IN_PROGRESS',
      updatedAt: '2026-01-03T00:00:00Z',
      createdAt: '2026-01-03T00:00:00Z',
      displayId: 'COND-3',
    },
    {
      id: 'i4',
      title: 'Delta prd',
      type: 'PRD',
      status: 'DONE',
      updatedAt: '2026-01-02T00:00:00Z',
      createdAt: '2026-01-02T00:00:00Z',
      displayId: 'COND-4',
    },
  ]
}

async function renderList() {
  render(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)
  await waitFor(() => expect(screen.getAllByText('Zeta task').length).toBeGreaterThan(0))
}

/** The desktop grouped-row list container — a plain div (no more fake role="listbox"). */
function rowList() {
  return screen.getByTestId('work-item-list')
}

/** The wrapping row element for a given Work Item id — ancestor of both its link and its mutation controls. */
function rowFor(id: string) {
  return document.getElementById(`wi-row-${id}`)!
}

/** Rows no longer carry their own `id` (only the id+title span is a real `<a>`) — resolve it via the wrapping row element instead. */
function rowIdOf(link: Element): string | null {
  return link.closest('[id^="wi-row-"]')?.id ?? null
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  issues = baseIssues()
  searchParamsValue = new URLSearchParams()
  VIEW = BASE_VIEW
})

describe('WorkItemListView links', () => {
  it('links each Work Item to the workflow-scoped displayId route', async () => {
    await renderList()
    const links = screen.getAllByRole('link')
    const itemLink = links.find((l) => l.getAttribute('href') === '/app/projects/proj-1/engineering/issues/COND-1')
    expect(itemLink).toBeDefined()
  })

  it('renders an area breadcrumb sourced from the Workflow view', async () => {
    await renderList()
    expect(await screen.findByText('Engineering')).toBeInTheDocument()
  })
})

describe('grouping + sorting', () => {
  it('groups rows by workflow status, in workflow order, with per-group counts', async () => {
    await renderList()
    const box = rowList()
    // Active tab (default) shows open + in_progress categories: Draft (2), In Progress (1). Done is hidden.
    const draftGroup = within(box).getByTestId('group-DRAFT')
    const progressGroup = within(box).getByTestId('group-IN_PROGRESS')
    expect(within(draftGroup).getByTestId('group-count-DRAFT')).toHaveTextContent('2')
    expect(within(progressGroup).getByTestId('group-count-IN_PROGRESS')).toHaveTextContent('1')
    expect(within(box).queryByTestId('group-DONE')).not.toBeInTheDocument()

    // Workflow order: Draft's group precedes In Progress's group in the DOM.
    const groups = within(box).getAllByTestId(/^group-(DRAFT|IN_PROGRESS)$/)
    expect(groups.map((g) => g.getAttribute('data-testid'))).toEqual(['group-DRAFT', 'group-IN_PROGRESS'])
  })

  it('sorts within a group by Updated (desc) by default, and re-sorts on Title', async () => {
    await renderList()
    const box = rowList()
    const draftGroup = within(box).getByTestId('group-DRAFT')

    // Default "Updated" desc: Zeta (updated 01-05) before Alpha (updated 01-01).
    let rows = within(draftGroup).getAllByRole('link')
    expect(rows.map(rowIdOf)).toEqual(['wi-row-i1', 'wi-row-i2'])

    const sortSelect = screen.getByLabelText('Sort:')
    fireEvent.change(sortSelect, { target: { value: 'title' } })

    // Title asc: "Alpha prd" before "Zeta task".
    rows = within(draftGroup).getAllByRole('link')
    expect(rows.map(rowIdOf)).toEqual(['wi-row-i2', 'wi-row-i1'])
  })
})

describe('tab counts + filtering', () => {
  it('computes Active/Done/All tab counts from status category', async () => {
    await renderList()
    const tablist = screen.getByRole('tablist', { name: 'Issues view' })
    expect(within(tablist).getByRole('tab', { name: /Active/ })).toHaveTextContent('3')
    expect(within(tablist).getByRole('tab', { name: /Done/ })).toHaveTextContent('1')
    expect(within(tablist).getByRole('tab', { name: /All/ })).toHaveTextContent('4')
  })

  it('adds and removes a Type filter pill', async () => {
    const user = userEvent.setup()
    await renderList()
    const box = rowList()
    expect(within(box).getAllByRole('link')).toHaveLength(3) // Draft(2) + In Progress(1) in Active tab

    await user.click(screen.getByRole('button', { name: 'Filter' }))
    await user.click(await screen.findByRole('menuitem', { name: 'TASK' }))

    await waitFor(() => expect(within(box).getAllByRole('link')).toHaveLength(2)) // Zeta + Gamma are TASK

    await user.click(screen.getByRole('button', { name: /Remove type filter/ }))
    await waitFor(() => expect(within(box).getAllByRole('link')).toHaveLength(3))
  })

  it('shows a filtered-empty state with a Clear filters affordance', async () => {
    const user = userEvent.setup()
    await renderList()
    await user.click(screen.getByRole('button', { name: 'Filter' }))
    await user.click(await screen.findByRole('menuitem', { name: 'PRD' }))
    // Alpha (PRD) is the only Draft/Active PRD — add Status: Draft too isn't needed; instead pick a
    // combination with zero matches: Type PRD + Status In Progress.
    await user.click(screen.getByRole('button', { name: 'Filter' }))
    await user.click(await screen.findByRole('menuitem', { name: 'In Progress' }))

    expect(await screen.findByText('No issues match the current filters')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(within(rowList()).getAllByRole('link')).toHaveLength(3))
  })

  it('resets a stale status pill when switching tabs (Draft doesn\'t survive onto Done)', async () => {
    // Tab clicks call router.replace() to change the `?view=` param, which in this test harness is a
    // bare mock that doesn't feed back into useSearchParams() — so drive the "navigation" directly by
    // updating searchParamsValue and re-rendering, same as a real URL change would.
    const user = userEvent.setup()
    const { rerender } = render(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)
    await waitFor(() => expect(screen.getAllByText('Zeta task').length).toBeGreaterThan(0))

    await user.click(screen.getByRole('button', { name: 'Filter' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Draft' }))
    expect(within(rowList()).getAllByRole('link')).toHaveLength(2)

    searchParamsValue = new URLSearchParams('view=done')
    rerender(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)

    // The Draft pill is gone (it isn't a valid status on the Done tab) and Delta (Done) shows.
    expect(screen.queryByText('Status:')).not.toBeInTheDocument()
    await waitFor(() => expect(within(rowList()).getAllByRole('link')).toHaveLength(1))
    expect(within(rowList()).getByText('Delta prd')).toBeInTheDocument()
  })
})

describe('mutations', () => {
  it('changes a row status via the status ring menu', async () => {
    const user = userEvent.setup()
    await renderList()
    const row = rowFor('i1')
    const ringButton = await within(row).findByRole('button', { name: /Change status/ })
    await user.click(ringButton)
    await user.click(await screen.findByRole('menuitem', { name: 'Start' }))

    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/i1',
      { status: 'IN_PROGRESS' },
      'token'
    )
  })

  it('reassigns a row via the assignee menu', async () => {
    const user = userEvent.setup()
    await renderList()
    const row = rowFor('i1')
    const assignButton = within(row).getByRole('button', { name: 'Assign' })
    await user.click(assignButton)
    await user.click(await screen.findByText('Ada Admin'))

    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/i1',
      { assigneeId: 'user-1' },
      'token'
    )
  })
})

describe('keyboard nav + bulk actions', () => {
  it('J/K moves real DOM focus between row links, X toggles selection for the focused row, Escape clears', async () => {
    await renderList()
    const box = rowList()
    const firstLink = within(box).getAllByRole('link')[0]

    // Tab would land here via roving tabIndex (it defaults to the first row) — simulate that directly.
    act(() => firstLink.focus())
    expect(rowFor('i1')).toContainElement(document.activeElement as HTMLElement)

    fireEvent.keyDown(document.activeElement as Element, { key: 'j' })
    expect(rowFor('i2')).toContainElement(document.activeElement as HTMLElement)

    fireEvent.keyDown(document.activeElement as Element, { key: 'x' })
    expect(rowFor('i2')).toHaveAttribute('data-selected', 'true')

    fireEvent.keyDown(document.activeElement as Element, { key: 'k' })
    expect(rowFor('i1')).toContainElement(document.activeElement as HTMLElement)

    // Enter needs no JS handling anymore — the row is a real <a href>, so there's no router.push
    // duplication left to assert; just confirm the focused link points at the right Work Item.
    expect(document.activeElement).toHaveAttribute('href', '/app/projects/proj-1/engineering/issues/COND-1')

    fireEvent.keyDown(document.activeElement as Element, { key: 'Escape' })
    expect(rowFor('i2')).not.toHaveAttribute('data-selected')
  })

  it('S opens the focused row\'s status menu (regression: Radix opens on pointerdown, a plain .click() no-ops)', async () => {
    await renderList()
    const box = rowList()
    const firstLink = within(box).getAllByRole('link')[0]
    act(() => firstLink.focus())

    fireEvent.keyDown(document.activeElement as Element, { key: 's' })

    expect(await screen.findByRole('menuitem', { name: 'Start' })).toBeInTheDocument()
  })

  it('A opens the focused row\'s assignee menu (same pointerdown-vs-click regression as S)', async () => {
    await renderList()
    const box = rowList()
    const firstLink = within(box).getAllByRole('link')[0]
    act(() => firstLink.focus())

    fireEvent.keyDown(document.activeElement as Element, { key: 'a' })

    expect(await screen.findByText('Ada Admin')).toBeInTheDocument()
  })

  it('bulk-changes status for every selected row and refetches', async () => {
    const user = userEvent.setup()
    await renderList()
    const box = rowList()
    const firstLink = within(box).getAllByRole('link')[0]
    act(() => firstLink.focus())
    fireEvent.keyDown(document.activeElement as Element, { key: 'x' })
    fireEvent.keyDown(document.activeElement as Element, { key: 'j' })
    fireEvent.keyDown(document.activeElement as Element, { key: 'x' })

    expect(await screen.findByText('2 selected')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Change status' }))
    await user.click(await screen.findByRole('menuitem', { name: 'In Progress' }))

    await waitFor(() => {
      expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/i1', { status: 'IN_PROGRESS' }, 'token')
      expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/i2', { status: 'IN_PROGRESS' }, 'token')
    })
    // Selection clears and the bulk bar disappears once the mutation completes.
    await waitFor(() => expect(screen.queryByText('2 selected')).not.toBeInTheDocument())
  })

  it('skips rows already at the target status instead of re-submitting them', async () => {
    const user = userEvent.setup()
    await renderList()
    const box = rowList()
    const firstLink = within(box).getAllByRole('link')[0]
    act(() => firstLink.focus())
    fireEvent.keyDown(document.activeElement as Element, { key: 'x' }) // select i1 (DRAFT)
    fireEvent.keyDown(document.activeElement as Element, { key: 'j' })
    fireEvent.keyDown(document.activeElement as Element, { key: 'j' })
    fireEvent.keyDown(document.activeElement as Element, { key: 'x' }) // also select i3 (IN_PROGRESS)

    await user.click(await screen.findByText('2 selected'))
    await user.click(screen.getByRole('button', { name: 'Change status' }))
    await user.click(await screen.findByRole('menuitem', { name: 'In Progress' }))

    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/proj-1/work-items/i1', { status: 'IN_PROGRESS' }, 'token')
  })

  it('shows one toastError when a bulk mutation partially fails, keeping only the failed row selected', async () => {
    apiPatch.mockImplementationOnce(() => Promise.reject(new Error('nope'))).mockResolvedValueOnce(undefined)
    const user = userEvent.setup()
    await renderList()
    const box = rowList()
    const firstLink = within(box).getAllByRole('link')[0]
    act(() => firstLink.focus())
    fireEvent.keyDown(document.activeElement as Element, { key: 'x' })
    fireEvent.keyDown(document.activeElement as Element, { key: 'j' })
    fireEvent.keyDown(document.activeElement as Element, { key: 'x' })

    await user.click(await screen.findByRole('button', { name: 'Change status' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Done' }))

    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalledTimes(1))
    // i1's PATCH was the first (rejected) call; i2's the second (resolved) — only i1 stays selected.
    await waitFor(() => expect(rowFor('i1')).toHaveAttribute('data-selected', 'true'))
    expect(rowFor('i2')).not.toHaveAttribute('data-selected')
  })

  it('gates selection and the bulk bar off entirely for REVIEWERs', async () => {
    const currentUserAsReviewer = { ...MEMBERS[0], role: 'REVIEWER' }
    const originalMembers = [...MEMBERS]
    MEMBERS.splice(0, MEMBERS.length, currentUserAsReviewer, MEMBERS[1])
    try {
      await renderList()
      const box = rowList()
      const firstLink = within(box).getAllByRole('link')[0]
      act(() => firstLink.focus())
      fireEvent.keyDown(document.activeElement as Element, { key: 'x' })

      expect(rowFor('i1')).not.toHaveAttribute('data-selected')
      expect(screen.queryByText('1 selected')).not.toBeInTheDocument()
    } finally {
      MEMBERS.splice(0, MEMBERS.length, ...originalMembers)
    }
  })
})

describe('empty states', () => {
  it('renders the Active-tab empty state when there are no active items', async () => {
    issues = issues.filter((i) => i.status === 'DONE')
    render(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)
    expect(await screen.findByText('No active issues — nice work!')).toBeInTheDocument()
  })

  it('renders the All-tab empty state when there are no items at all', async () => {
    issues = []
    searchParamsValue = new URLSearchParams('view=all')
    render(<WorkItemListView projectId="proj-1" slug="ENGINEERING" noun="Issue" />)
    expect(await screen.findByText('No issues yet')).toBeInTheDocument()
  })
})

describe('display mode', () => {
  beforeEach(() => {
    // Pin "today" so the calendar opens on the month the fixture Work Item is scheduled in.
    vi.useFakeTimers({ shouldAdvanceTime: true })
    vi.setSystemTime(new Date('2026-03-15T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('renders the calendar when the Workflow declares default_view: calendar', async () => {
    VIEW = { ...BASE_VIEW, defaultView: 'calendar' }
    await renderList()

    await waitFor(() => expect(screen.getByTestId('work-item-calendar-grid')).toBeInTheDocument())
    expect(screen.queryByTestId('work-item-list')).not.toBeInTheDocument()
  })

  it('honors ?mode=calendar over the Workflow default', async () => {
    searchParamsValue = new URLSearchParams('mode=calendar')
    await renderList()

    expect(screen.getByTestId('work-item-calendar-grid')).toBeInTheDocument()
  })

  it('places scheduled Work Items on their fire dates in the calendar', async () => {
    VIEW = { ...BASE_VIEW, defaultView: 'calendar' }
    await renderList()

    await waitFor(() => expect(screen.getByTestId('calendar-day-2026-03-04')).toBeInTheDocument())
    const cell = screen.getByTestId('calendar-day-2026-03-04')
    expect(within(cell).getByTestId('calendar-chip-i1')).toHaveAttribute(
      'href',
      '/app/projects/proj-1/engineering/issues/COND-1'
    )
    // An unscheduled Work Item never lands on the grid (the unscheduled tray is a follow-up).
    expect(screen.queryByTestId('calendar-chip-i2')).not.toBeInTheDocument()
  })

  it('switches back to list and persists the choice', async () => {
    VIEW = { ...BASE_VIEW, defaultView: 'calendar' }
    await renderList()
    await waitFor(() => expect(screen.getByTestId('work-item-calendar-grid')).toBeInTheDocument())

    fireEvent.click(screen.getByTitle('List view'))

    expect(screen.getByTestId('work-item-list')).toBeInTheDocument()
    expect(screen.queryByTestId('work-item-calendar-grid')).not.toBeInTheDocument()
    expect(localStorage.getItem('wv_mode_proj-1_ENGINEERING')).toBe('list')
    expect(localStorage.getItem('wv_mode_explicit_proj-1_ENGINEERING')).toBe('1')
  })

  it('switches to board and persists the choice', async () => {
    await renderList()

    fireEvent.click(screen.getByTitle('Board view'))

    expect(screen.queryByTestId('work-item-list')).not.toBeInTheDocument()
    expect(localStorage.getItem('wv_mode_proj-1_ENGINEERING')).toBe('board')
  })

  it('restores a persisted calendar choice on remount', async () => {
    localStorage.setItem('wv_mode_proj-1_ENGINEERING', 'calendar')
    localStorage.setItem('wv_mode_explicit_proj-1_ENGINEERING', '1')
    await renderList()

    expect(screen.getByTestId('work-item-calendar-grid')).toBeInTheDocument()
  })
})
