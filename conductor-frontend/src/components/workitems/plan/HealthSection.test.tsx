import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkItemForecast, WorkItemHealth } from '@/types/workItem'

const { fetchForecast, confirmHealthUpdate, createHealthUpdate, apiGet, toastError } = vi.hoisted(() => ({
  fetchForecast: vi.fn(),
  confirmHealthUpdate: vi.fn(),
  createHealthUpdate: vi.fn(),
  apiGet: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('@/lib/delivery', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/delivery')>()
  return { ...actual, fetchForecast, confirmHealthUpdate, createHealthUpdate }
})
vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>()
  return { ...actual, apiGet }
})
vi.mock('@/components/ui/toast', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/toast')>()
  return { ...actual, toastError }
})

import { HealthSection } from './HealthSection'

const FORECAST: WorkItemForecast = { state: 'OK', mode: 'THROUGHPUT', remaining: 4, samples: 12, p50Date: '2026-11-12', p85Date: '2026-11-20', appetiteFit: 'FITS' }

const health = (over: Partial<WorkItemHealth> = {}): WorkItemHealth => ({
  health: 'ON_TRACK',
  note: 'Going well',
  updatedAt: new Date().toISOString(),
  stale: false,
  atRisk: false,
  pendingDraft: null,
  ...over,
})

const DRAFT = { updateId: 'u1', health: 'AT_RISK' as const, note: 'Slipping on auth', draftedByLabel: 'Planner agent', createdAt: new Date().toISOString() }

function setup(h: WorkItemHealth | null, canEdit = true) {
  const onChanged = vi.fn()
  render(<HealthSection projectId="p1" issueId="wi-1" token="tok" canEdit={canEdit} health={h} childCount={3} onChanged={onChanged} />)
  return { onChanged, user: userEvent.setup() }
}

beforeEach(() => {
  vi.clearAllMocks()
  fetchForecast.mockResolvedValue(FORECAST)
  apiGet.mockResolvedValue({})
})

describe('HealthSection', () => {
  it.each([
    ['ON_TRACK', 'On track'],
    ['AT_RISK', 'At risk'],
    ['OFF_TRACK', 'Off track'],
  ] as const)('shows the %s badge and note', async (value, label) => {
    setup(health({ health: value }))
    expect(screen.getByText(label)).toBeInTheDocument()
    expect(screen.getByText('Going well')).toBeInTheDocument()
    await screen.findByTestId('forecast-line')
  })

  it('shows a muted "No health yet" and a stale hint', async () => {
    setup(health({ health: null, note: null, updatedAt: null }))
    expect(screen.getByText('No health yet')).toBeInTheDocument()
    await screen.findByTestId('forecast-line')
  })

  it('hints when the update is stale', async () => {
    setup(health({ stale: true }))
    expect(screen.getByText(/stale/i)).toBeInTheDocument()
    await screen.findByTestId('forecast-line')
  })

  it('shows a skeleton, then the forecast line', async () => {
    setup(health())
    expect(screen.getByLabelText('Loading forecast')).toBeInTheDocument()
    expect(await screen.findByTestId('forecast-line')).toHaveTextContent(/P50 .* · P85 .* · fits appetite/)
  })

  it('says how many more tasks are needed when there is too little data', async () => {
    fetchForecast.mockResolvedValue({ state: 'INSUFFICIENT_DATA', remaining: 5, samples: 3, needed: 8 })
    setup(health())
    expect(await screen.findByText(/Needs 8 completed tasks \(3 so far\), 5 more/)).toBeInTheDocument()
  })

  it('confirms a draft through the endpoint and refreshes the item', async () => {
    confirmHealthUpdate.mockResolvedValue({ id: 'u1', health: 'AT_RISK', note: 'Slipping on auth', confirmed: true, createdAt: '' })
    const fresh = health({ health: 'AT_RISK', note: 'Slipping on auth' })
    apiGet.mockResolvedValue({ health: fresh })
    const { onChanged, user } = setup(health({ pendingDraft: DRAFT }))
    expect(screen.getByText(/Draft from Planner agent/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Confirm' }))

    await waitFor(() => expect(onChanged).toHaveBeenCalledWith({ health: fresh }))
    expect(confirmHealthUpdate).toHaveBeenCalledWith('p1', 'wi-1', 'u1', {}, 'tok')
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/p1/work-items/wi-1', 'tok')
  })

  it('sends the edits when the draft is changed before confirming', async () => {
    confirmHealthUpdate.mockResolvedValue({ id: 'u1', health: 'OFF_TRACK', note: 'Blocked', confirmed: true, createdAt: '' })
    const { user } = setup(health({ pendingDraft: DRAFT }))
    await user.click(screen.getByRole('button', { name: 'Edit' }))
    await user.selectOptions(screen.getByLabelText('Draft health'), 'OFF_TRACK')
    await user.clear(screen.getByLabelText('Draft note'))
    await user.type(screen.getByLabelText('Draft note'), 'Blocked')
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(confirmHealthUpdate).toHaveBeenCalledWith('p1', 'wi-1', 'u1', { health: 'OFF_TRACK', note: 'Blocked' }, 'tok'))
  })

  it('toasts when confirming fails and leaves the item alone', async () => {
    confirmHealthUpdate.mockRejectedValue(Object.assign(new Error('expired'), { status: 409, detail: 'Draft expired' }))
    const { onChanged, user } = setup(health({ pendingDraft: DRAFT }))
    await user.click(screen.getByRole('button', { name: 'Confirm' }))
    await waitFor(() => expect(toastError).toHaveBeenCalled())
    expect(onChanged).not.toHaveBeenCalled()
  })

  it('posts a confirmed update directly', async () => {
    createHealthUpdate.mockResolvedValue({ id: 'u2', health: 'AT_RISK', note: 'Late', confirmed: true, createdAt: '' })
    const { onChanged, user } = setup(health())
    await user.click(screen.getByRole('button', { name: 'Post update' }))
    await user.selectOptions(screen.getByLabelText('Health'), 'AT_RISK')
    await user.type(screen.getByLabelText('Health note'), 'Late')
    await user.click(screen.getByRole('button', { name: 'Post update' }))
    await waitFor(() => expect(createHealthUpdate).toHaveBeenCalledWith('p1', 'wi-1', { health: 'AT_RISK', note: 'Late', draft: false }, 'tok'))
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
  })

  it('is read-only for a reviewer', async () => {
    setup(health({ pendingDraft: DRAFT }), false)
    expect(screen.getByText(/Draft from Planner agent/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Post update' })).not.toBeInTheDocument()
    await screen.findByTestId('forecast-line')
  })
})
