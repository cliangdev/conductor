import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'
import type { WorkflowRunDto, WorkflowScheduleSkipDto } from '@/types/workflow'

const push = vi.fn()
const replace = vi.fn()
let searchParams = new URLSearchParams()

vi.mock('next/navigation', () => ({
  useParams: () => ({ projectId: 'proj-1', workflowId: 'wf-1' }),
  usePathname: () => '/app/projects/proj-1/workflows/wf-1/runs',
  useRouter: () => ({ push, replace }),
  useSearchParams: () => searchParams,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'test-token' }),
}))

vi.mock('@/contexts/WorkflowContext', () => ({
  useWorkflow: () => ({ workflow: { name: 'Nightly digest', enabled: true }, loading: false, refetch: vi.fn(), setWorkflow: vi.fn() }),
}))

const showToast = vi.fn()
vi.mock('@/components/ui/toast', () => ({
  useToast: () => ({ showToast }),
}))

vi.mock('@/components/auth/Can', () => ({
  Can: ({ children }: { children: React.ReactNode }) => <>{children}</>,
}))

// Flatten the dropdown so RowActionsMenu items are always visible/clickable in jsdom (the real
// Radix/base-ui popup is portal-based) — same approach as RuntimeTargetsPanel.test.tsx.
vi.mock('@/components/ui/dropdown-menu', () => ({
  DropdownMenu: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuTrigger: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuContent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  DropdownMenuItem: ({ children, onSelect }: { children: React.ReactNode; onSelect?: () => void }) => (
    <button type="button" onClick={onSelect}>{children}</button>
  ),
}))

// Flatten the modal too, so ConfirmModal's content is always in the DOM when `open`.
vi.mock('@/components/ui/modal', () => ({
  Modal: ({ open, children, title, footer }: { open: boolean; children: React.ReactNode; title: string; footer: React.ReactNode }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
        {footer}
      </div>
    ) : null,
}))

function makeRun(overrides: Partial<WorkflowRunDto> = {}): WorkflowRunDto {
  return {
    id: 'RUN00001',
    workflowId: 'wf-1',
    triggerType: 'schedule',
    status: 'SUCCESS',
    startedAt: '2026-07-26T00:00:00Z',
    completedAt: '2026-07-26T00:01:00Z',
    ...overrides,
  }
}

// A run blocked on an unclaimed self-hosted job reads RUNNING at the run level (see
// WorkflowJobOrchestrator.planJobExecution) — status PENDING + waitReason set can't actually occur.
// Mirrors the fixture WorkflowControllerTest builds for the same case.
function pending(): WorkflowRunDto {
  return makeRun({ id: 'PEND0001', status: 'RUNNING', completedAt: undefined, waitReason: 'AWAITING_RUNNER' })
}
function running(): WorkflowRunDto {
  return makeRun({ id: 'RUNN0002', status: 'RUNNING', completedAt: undefined })
}
function success(): WorkflowRunDto {
  return makeRun({ id: 'SUCC0003', status: 'SUCCESS' })
}

// `vi.mock` factories are hoisted above the whole file (including the static `import RunListPage`
// below, which itself pulls in '@/lib/workflows' at import time) — a plain top-level `const`/`let`
// referenced inside the factory would still be in its temporal dead zone when that happens. `vi.hoisted`
// runs before any of that, so mutating properties on its returned object is safe from any test.
// The behaviors are plain functions (not `vi.fn()`) driven by per-test overrides: a `vi.fn()` whose
// implementation returns a rejected promise gets flagged as an unhandled rejection by this repo's
// vitest setup even when the caller awaits/catches it (see reference_vitest_rejected_promise_mock).
const mocks = vi.hoisted(() => ({
  listRunsBehavior: (
    opts?: { state?: 'queued' | 'running'; size?: number },
  ): WorkflowRunDto[] | Promise<WorkflowRunDto[]> => (opts ? [] : []),
  listRunsCalls: [] as Array<{ status?: readonly string[]; state?: 'queued' | 'running'; size?: number } | undefined>,
  cancelRunBehavior: (runId: string): Promise<WorkflowRunDto> => Promise.resolve({ id: runId } as WorkflowRunDto),
  cancelQueuedMock: vi.fn(),
  listSkipsMock: vi.fn(),
}))

vi.mock('@/lib/workflows', async () => {
  const actual = await vi.importActual<typeof import('@/lib/workflows')>('@/lib/workflows')
  return {
    ...actual,
    listWorkflowRuns: (
      _projectId: string,
      _workflowId: string,
      _token: string,
      opts?: { status?: readonly string[]; state?: 'queued' | 'running'; size?: number },
    ) => {
      mocks.listRunsCalls.push(opts)
      return Promise.resolve(mocks.listRunsBehavior(opts))
    },
    cancelWorkflowRun: (_projectId: string, _workflowId: string, runId: string) => mocks.cancelRunBehavior(runId),
    cancelQueuedWorkflowRuns: mocks.cancelQueuedMock,
    listScheduleSkips: mocks.listSkipsMock,
  }
})

import RunListPage from './page'

describe('RunListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    push.mockClear()
    replace.mockClear()
    searchParams = new URLSearchParams()
    mocks.listRunsBehavior = (opts) => {
      if (opts?.state === 'queued') return [pending()]
      if (opts?.state === 'running') return [running()]
      return [pending(), running(), success()]
    }
    mocks.cancelRunBehavior = (runId) => Promise.resolve(makeRun({ id: runId }))
    mocks.cancelQueuedMock.mockResolvedValue({ cancelledCount: 1 })
    mocks.listSkipsMock.mockResolvedValue([])
    mocks.listRunsCalls = []
  })

  it('defaults to the All tab, lists every run, and shows a Cancel queued runs button', async () => {
    render(<RunListPage />)

    expect(await screen.findByText('PEND0001')).toBeInTheDocument()
    expect(screen.getByText('RUNN0002')).toBeInTheDocument()
    expect(screen.getByText('SUCC0003')).toBeInTheDocument()

    const allTab = screen.getByRole('tab', { name: /all/i })
    expect(allTab).toHaveAttribute('aria-selected', 'true')

    // Queued segment count reflects the independent queued probe (1), not the current filter.
    const queuedTab = screen.getByRole('tab', { name: /queued/i })
    expect(queuedTab).toHaveTextContent('1')

    expect(screen.getByRole('button', { name: /cancel queued runs \(1\)/i })).toBeInTheDocument()
  })

  it('switches to the Queued filter via the ?state= URL param on click', async () => {
    render(<RunListPage />)
    await screen.findByText('PEND0001')

    fireEvent.click(screen.getByRole('tab', { name: /queued/i }))

    await waitFor(() =>
      expect(replace).toHaveBeenCalledWith('/app/projects/proj-1/workflows/wf-1/runs?state=queued'),
    )
  })

  it('requests state=queued (the derived filter), not a raw status list, for the Queued segment', async () => {
    searchParams = new URLSearchParams({ state: 'queued' })
    render(<RunListPage />)
    await screen.findByText('PEND0001')

    await waitFor(() => expect(mocks.listRunsCalls.length).toBeGreaterThan(0))
    // Every call this mount makes — the filtered table query and the always-on queued-count probe —
    // goes through `state`, never the old raw `status` array.
    expect(mocks.listRunsCalls.some((o) => o?.state === 'queued')).toBe(true)
    expect(mocks.listRunsCalls.every((o) => o?.status === undefined)).toBe(true)
  })

  it('maps the Queued filter to state=queued and renders a RUNNING-but-blocked run as Queued', async () => {
    searchParams = new URLSearchParams({ state: 'queued' })
    render(<RunListPage />)

    expect(await screen.findByText('PEND0001')).toBeInTheDocument()
    expect(screen.queryByText('RUNN0002')).not.toBeInTheDocument()
    expect(screen.queryByText('SUCC0003')).not.toBeInTheDocument()

    // status RUNNING + waitReason AWAITING_RUNNER (the only combination that can actually occur) still
    // reads as "Queued · waiting for runner", not "Running" — see the row's `queued` derivation.
    const pendingRow = screen.getByText('PEND0001').closest('tr')!
    expect(within(pendingRow).getByText('Queued')).toBeInTheDocument()
    expect(within(pendingRow).getByText(/waiting for runner/i)).toBeInTheDocument()
  })

  it('hides the bulk cancel button when nothing is queued', async () => {
    mocks.listRunsBehavior = (opts) => {
      if (opts?.state === 'queued') return []
      if (opts?.state === 'running') return [running()]
      return [running(), success()]
    }
    render(<RunListPage />)

    expect(await screen.findByText('RUNN0002')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /cancel queued runs/i })).not.toBeInTheDocument()
  })

  it('confirms and cancels queued runs in bulk without touching in-progress runs', async () => {
    render(<RunListPage />)
    await screen.findByText('PEND0001')

    fireEvent.click(screen.getByRole('button', { name: /cancel queued runs \(1\)/i }))

    expect(screen.getByTestId('modal')).toBeInTheDocument()
    expect(screen.getByText(/runs already in progress will not be affected/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Cancel queued runs' }))

    await waitFor(() => expect(mocks.cancelQueuedMock).toHaveBeenCalledWith('proj-1', 'wf-1', 'test-token'))
    await waitFor(() => expect(showToast).toHaveBeenCalledWith('Cancelled 1 queued run.', 'success'))
  })

  it('reports a mismatch honestly instead of a false success when nothing was actually cancelled', async () => {
    // The button is only rendered when queuedCount > 0, so cancelledCount === 0 always means every
    // queued run started executing on a runner between the last poll and this click -- cancellation
    // deliberately never touches in-flight work, so there is nothing left to cancel.
    mocks.cancelQueuedMock.mockResolvedValue({ cancelledCount: 0 })
    render(<RunListPage />)
    await screen.findByText('PEND0001')

    fireEvent.click(screen.getByRole('button', { name: /cancel queued runs \(1\)/i }))
    fireEvent.click(screen.getByRole('button', { name: 'Cancel queued runs' }))

    await waitFor(() => expect(mocks.cancelQueuedMock).toHaveBeenCalled())
    expect(showToast).not.toHaveBeenCalledWith(expect.stringContaining('Cancelled 0'), 'success')
    await waitFor(() =>
      expect(showToast).toHaveBeenCalledWith(expect.stringMatching(/no queued runs were cancelled/i), 'error')
    )
  })

  it('handles a 409 on row cancel gracefully instead of showing a raw error', async () => {
    mocks.cancelRunBehavior = () => Promise.reject(Object.assign(new Error('Server error (409)'), { status: 409 }))
    render(<RunListPage />)
    await screen.findByText('PEND0001')

    const pendingRow = screen.getByText('PEND0001').closest('tr')!
    fireEvent.click(within(pendingRow).getByRole('button', { name: 'Cancel run' }))

    await waitFor(() => expect(showToast).toHaveBeenCalledWith('That run already finished.', 'success'))
    expect(showToast).not.toHaveBeenCalledWith(expect.stringContaining('Server error'), 'error')
  })

  it('does not say "No runs yet" twice when a workflow has no run history', async () => {
    mocks.listRunsBehavior = () => []
    render(<RunListPage />)

    await waitFor(() => expect(mocks.listRunsCalls.length).toBeGreaterThan(0))
    // WorkflowStatsStrip has its own "No runs yet." fallback; it must be suppressed here so the
    // EmptyState below it is the only place that says it.
    expect(await screen.findAllByText(/no runs yet/i)).toHaveLength(1)
  })

  it('surfaces a failed runs fetch as an error instead of silently rendering "No runs yet"', async () => {
    // A plain function (not vi.fn()) returning a rejected promise — see the file-header comment on
    // why a vi.fn() here would be flagged as an unhandled rejection even though the page catches it.
    mocks.listRunsBehavior = () => Promise.reject(Object.assign(new Error('Server error (500)'), { status: 500 }))
    render(<RunListPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent(/couldn't load runs/i)
    expect(screen.queryByText(/no runs yet/i)).not.toBeInTheDocument()
  })

  function skip(hoursAgo: number, overrides: Partial<WorkflowScheduleSkipDto> = {}): WorkflowScheduleSkipDto {
    return {
      id: `skip-${hoursAgo}h`,
      scheduleId: 'sched-1',
      skippedAt: new Date(Date.now() - hoursAgo * 60 * 60 * 1000).toISOString(),
      reason: 'concurrency: single already has an active run',
      runId: 'run-blocking',
      ...overrides,
    }
  }

  it('only counts schedule skips from the last 24 hours, ignoring older ones the API still returns', async () => {
    mocks.listSkipsMock.mockResolvedValue([skip(2), skip(240)])
    render(<RunListPage />)

    expect(await screen.findByText(/scheduled run.*skipped in the last 24 hours/i)).toHaveTextContent(
      '1 scheduled run skipped in the last 24 hours',
    )
  })

  it('says "N+" when the skip probe returns a full page, all within the window', async () => {
    mocks.listSkipsMock.mockResolvedValue(Array.from({ length: 50 }, (_, i) => skip(i / 100)))
    render(<RunListPage />)

    expect(await screen.findByText(/50\+ scheduled runs skipped in the last 24 hours/i)).toBeInTheDocument()
  })

  it('does not refetch the stats-strip history sample on the 5s poll (only table + queued count)', async () => {
    render(<RunListPage />)
    await screen.findByText('PEND0001')
    // The mount effect fires 3 calls (table, queued probe, history) — pin the snapshot to after all
    // 3 have landed, not just after the text appears, so this can't race the last of the three.
    await waitFor(() => expect(mocks.listRunsCalls.length).toBeGreaterThanOrEqual(3))

    const callsAfterMount = mocks.listRunsCalls.length
    // The queued run keeps polling active; wait past one 5s tick (real timers — the interval is a
    // plain `setInterval`, not worth the fragility of faking it for one assertion).
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5200))
    })

    const newCalls = mocks.listRunsCalls.slice(callsAfterMount)
    expect(newCalls.length).toBeGreaterThan(0)
    // The history sample is the only call shaped `size: 20` — it must not appear among the poll
    // tick's calls, since (unlike the table and queued-count probe) it doesn't need 5s freshness.
    expect(newCalls.every((opts) => opts?.size !== 20)).toBe(true)
  }, 8000)
})
