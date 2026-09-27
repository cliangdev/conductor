import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

const { mockCan, toastSuccessSpy, toastErrorSpy } = vi.hoisted(() => ({
  mockCan: vi.fn((_cap?: string) => true),
  toastSuccessSpy: vi.fn(),
  toastErrorSpy: vi.fn(),
}))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => mockCan(cap),
  usePermissions: () => ({ role: 'CREATOR', loading: false, can: mockCan, refresh: vi.fn() }),
}))
vi.mock('@/components/ui/toast', () => ({
  toastSuccess: toastSuccessSpy,
  toastError: toastErrorSpy,
}))
vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

vi.mock('./types', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./types')>()),
  getCreativePerformance: vi.fn(),
  listCreativeExperiments: vi.fn(),
  createCreativeExperiment: vi.fn(),
  decideCreativeExperiment: vi.fn(),
  confirmCreativeExperimentWinner: vi.fn(),
}))

import {
  getCreativePerformance,
  listCreativeExperiments,
  createCreativeExperiment,
  decideCreativeExperiment,
  confirmCreativeExperimentWinner,
  type CreativeExperimentResponse,
  type CreativePerformanceEntry,
  type CreativePerformanceResponse,
} from './types'
import { CreativePerformancePanel } from './CreativePerformancePanel'

function entry(overrides: Partial<CreativePerformanceEntry> = {}): CreativePerformanceEntry {
  return {
    creativeId: 'cr-12a',
    label: '12a',
    headline: 'Original hook',
    posts: 2,
    views: 10000,
    engagementRate: 0.08,
    avgViewPct: null,
    views72h: 8000,
    byPlatform: [{ platform: 'tiktok', posts: 2, views: 10000, engagementRate: 0.08 }],
    ...overrides,
  }
}

function performance(family: CreativePerformanceEntry[]): CreativePerformanceResponse {
  return { creativeId: 'cr-12a', family }
}

function experiment(overrides: Partial<CreativeExperimentResponse> = {}): CreativeExperimentResponse {
  return {
    id: 'exp-1',
    projectId: 'proj-1',
    parentCreativeId: 'cr-12a',
    metric: 'views',
    windowHours: 72,
    state: 'RUNNING',
    winnerCreativeId: null,
    decidedAt: null,
    summary: null,
    winnerLineConfirmedAt: null,
    winnerLineConfirmedBy: null,
    createdBy: 'user-1',
    createdAt: '2026-09-01T00:00:00Z',
    ...overrides,
  }
}

function setup() {
  return render(<CreativePerformancePanel projectId="proj-1" creativeId="cr-12a" token="tok" />)
}

describe('CreativePerformancePanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
  })

  it('renders the variant table with headline, posts, views, 72h views and engagement rate', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(
      performance([entry(), entry({ creativeId: 'cr-12b', label: '12b', headline: 'New hook', views: 20000 })]),
    )
    ;(listCreativeExperiments as Mock).mockResolvedValue([])
    setup()

    expect(await screen.findByText('12a')).toBeInTheDocument()
    expect(screen.getByText('Original hook')).toBeInTheDocument()
    expect(screen.getByText('12b')).toBeInTheDocument()
    expect(screen.getByText('New hook')).toBeInTheDocument()
    expect(screen.getByText('10K')).toBeInTheDocument()
    expect(screen.getByText('20K')).toBeInTheDocument()
    expect(screen.getAllByText('8K').length).toBeGreaterThan(0) // 72h views
    expect(screen.getAllByText('8.0%').length).toBeGreaterThan(0) // engagement rate
  })

  it('expands a variant row to show its per-platform breakdown', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(
      performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]),
    )
    ;(listCreativeExperiments as Mock).mockResolvedValue([])
    setup()

    await screen.findByText('12a')
    expect(screen.queryByText('Tiktok')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /12a platform breakdown/i }))
    expect(screen.getByText('Tiktok')).toBeInTheDocument()
  })

  it('shows an empty state when nothing has published yet', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry({ posts: 0, views: 0 }), entry({ creativeId: 'cr-12b', label: '12b', posts: 0, views: 0 })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([])
    setup()

    expect(await screen.findByText('No published Posts yet')).toBeInTheDocument()
  })

  it('shows Start experiment when the family has ≥ 2 variants and no active experiment', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([])
    setup()

    expect(await screen.findByRole('button', { name: 'Start experiment' })).toBeInTheDocument()
  })

  it('hides Start experiment when the family has fewer than 2 variants', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry()]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([])
    setup()

    await screen.findByText('12a')
    expect(screen.queryByRole('button', { name: 'Start experiment' })).not.toBeInTheDocument()
  })

  it('hides Start experiment when an experiment is already RUNNING', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([experiment({ state: 'RUNNING' })])
    setup()

    await screen.findByText('12a')
    expect(screen.queryByRole('button', { name: 'Start experiment' })).not.toBeInTheDocument()
  })

  it('hides Start experiment and Check now for a reviewer (no creative.manage)', async () => {
    mockCan.mockReturnValue(false)
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([experiment({ state: 'RUNNING' })])
    setup()

    await screen.findByText('12a')
    expect(screen.queryByRole('button', { name: 'Start experiment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Check now' })).not.toBeInTheDocument()
  })

  it('starts an experiment with the chosen metric and window', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([])
    ;(createCreativeExperiment as Mock).mockResolvedValue(experiment({ metric: 'engagement_rate', windowHours: 48 }))
    setup()

    await userEvent.click(await screen.findByRole('button', { name: 'Start experiment' }))
    const dialog = screen.getByRole('dialog')
    await userEvent.selectOptions(within(dialog).getByLabelText('Metric'), 'engagement_rate')
    const windowInput = within(dialog).getByLabelText('Window (hours)')
    await userEvent.clear(windowInput)
    await userEvent.type(windowInput, '48')
    await userEvent.click(within(dialog).getByRole('button', { name: 'Start experiment' }))

    await waitFor(() =>
      expect(createCreativeExperiment).toHaveBeenCalledWith(
        'proj-1',
        { creativeId: 'cr-12a', metric: 'engagement_rate', windowHours: 48 },
        'tok',
      ),
    )
  })

  it('calls decide when Check now is clicked on a RUNNING experiment', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([experiment({ state: 'RUNNING' })])
    ;(decideCreativeExperiment as Mock).mockResolvedValue(experiment({ state: 'RUNNING' }))
    setup()

    await userEvent.click(await screen.findByRole('button', { name: 'Check now' }))
    await waitFor(() => expect(decideCreativeExperiment).toHaveBeenCalledWith('proj-1', 'exp-1', 'tok'))
  })

  it('shows "Add winning headline to approved lines" for a DECIDED, unconfirmed experiment and calls confirm-winner', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([
      experiment({
        state: 'DECIDED',
        winnerCreativeId: 'cr-12b',
        decidedAt: '2026-09-20T00:00:00Z',
        summary: {
          comparisonMetric: 'views',
          variants: [
            { creativeId: 'cr-12a', label: '12a', headline: 'Original hook', hasData: true, views: 10000 },
            { creativeId: 'cr-12b', label: '12b', headline: 'New hook', hasData: true, views: 20000 },
          ],
        },
      }),
    ])
    ;(confirmCreativeExperimentWinner as Mock).mockResolvedValue(
      experiment({ state: 'DECIDED', winnerCreativeId: 'cr-12b', winnerLineConfirmedAt: '2026-09-21T00:00:00Z' }),
    )
    setup()

    const confirmButton = await screen.findByRole('button', { name: /add winning headline to approved lines/i })
    expect(screen.getByText(/nothing is added automatically/i)).toBeInTheDocument()
    await userEvent.click(confirmButton)

    await waitFor(() => expect(confirmCreativeExperimentWinner).toHaveBeenCalledWith('proj-1', 'exp-1', 'tok'))
  })

  it('does not show the confirm action once a winner is already confirmed', async () => {
    ;(getCreativePerformance as Mock).mockResolvedValue(performance([entry(), entry({ creativeId: 'cr-12b', label: '12b' })]))
    ;(listCreativeExperiments as Mock).mockResolvedValue([
      experiment({
        state: 'DECIDED',
        winnerCreativeId: 'cr-12b',
        winnerLineConfirmedAt: '2026-09-21T00:00:00Z',
        summary: { comparisonMetric: 'views', variants: [] },
      }),
    ])
    setup()

    await screen.findByText('12a')
    expect(screen.queryByRole('button', { name: /add winning headline/i })).not.toBeInTheDocument()
    expect(screen.getByText(/added to the Brand Kit/i)).toBeInTheDocument()
  })
})
