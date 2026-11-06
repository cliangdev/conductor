import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { ledger, row } from './fixtures'

vi.mock('@/lib/api', () => ({
  apiPost: vi.fn(),
  apiGet: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => (err as { detail?: string } | null)?.detail ?? fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'test-token', loading: false }),
}))

import { apiPost } from '@/lib/api'
import { RecordReadDialog } from '../RecordReadDialog'

const rows = ledger().rows

function open(props: Partial<React.ComponentProps<typeof RecordReadDialog>> = {}) {
  const onRecorded = vi.fn()
  const onOpenChange = vi.fn()
  render(
    <RecordReadDialog
      open
      onOpenChange={onOpenChange}
      projectId="proj-1"
      rows={rows}
      initial={{ channel: 'ADS', metric: 'installs' }}
      onRecorded={onRecorded}
      {...props}
    />,
  )
  return { onRecorded, onOpenChange }
}

function fill(values: { from?: string; to?: string; value?: string; source?: string; note?: string }) {
  const set = (label: string, value: string | undefined) => {
    if (value !== undefined) fireEvent.change(screen.getByLabelText(label), { target: { value } })
  }
  set('First day', values.from)
  set('Last day', values.to)
  set('Value', values.value)
  set('Source', values.source)
  set('Note (optional)', values.note)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(new Date('2026-10-10T08:00:00Z'))
})

afterEach(() => {
  vi.useRealTimers()
})

describe('RecordReadDialog', () => {
  it('starts on the chosen metric and shows what to read for it', () => {
    open({ rows: [row({ channel: 'ADS', metric: 'installs', metricRef: 'ADS.installs', label: 'Installs', definition: { source: 'Apple Ads campaign report, tapInstalls', numerator: 'tap_installs' } }), ...rows] })
    expect(screen.getByLabelText('Channel')).toHaveValue('ADS')
    expect(screen.getByLabelText('Metric')).toHaveValue('installs')
    expect(screen.getByText('Counts tap installs · Source: Apple Ads campaign report, tapInstalls')).toBeInTheDocument()
  })

  it('offers only the metrics of the chosen channel and resets the metric when the channel changes', () => {
    open()
    const metric = screen.getByLabelText('Metric') as HTMLSelectElement
    expect([...metric.options].map((o) => o.value)).toEqual(['installs'])
    fireEvent.change(screen.getByLabelText('Channel'), { target: { value: 'SOCIAL' } })
    expect((screen.getByLabelText('Metric') as HTMLSelectElement).value).toBe('views')
  })

  it('refuses a range that starts after it ends, without calling the API', async () => {
    open()
    fill({ from: '2026-09-28', to: '2026-09-01', value: '23', source: 'Apple Ads console' })
    fireEvent.click(screen.getByRole('button', { name: 'Record read' }))
    expect(await screen.findByText(/on or before the last day/)).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('refuses a last day of today, a range over 92 days, a negative value and a missing source', async () => {
    open()
    const submit = () => fireEvent.click(screen.getByRole('button', { name: 'Record read' }))

    fill({ from: '2026-10-01', to: '2026-10-10', value: '23', source: 'Apple Ads console' })
    submit()
    expect(await screen.findByText(/yesterday or earlier/)).toBeInTheDocument()

    fill({ from: '2026-07-01', to: '2026-10-01' })
    submit()
    expect(await screen.findByText(/at most 92 days/)).toBeInTheDocument()

    fill({ from: '2026-09-01', to: '2026-09-28', value: '-3' })
    submit()
    expect(await screen.findByText(/number of zero or more/)).toBeInTheDocument()

    fill({ value: '23', source: ' ' })
    submit()
    expect(await screen.findByText(/where the figure was read/)).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('posts the read and shows the returned row\'s verdict, mismatch and trust note', async () => {
    ;(apiPost as Mock).mockResolvedValue(
      row({
        channel: 'ADS',
        metric: 'installs',
        metricRef: 'ADS.installs',
        label: 'Installs',
        verdict: 'PLATFORM',
        mismatch: true,
        trustNote: 'The platform read disagrees with every other source.',
      }),
    )
    const { onRecorded } = open()
    fill({ from: '2026-09-01', to: '2026-09-28', value: ' 23 ', source: ' Apple Ads console ', note: 'Checked twice' })
    fireEvent.click(screen.getByRole('button', { name: 'Record read' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/verification/reads',
      { channel: 'ADS', metric: 'installs', from: '2026-09-01', to: '2026-09-28', value: 23, sourceName: 'Apple Ads console', note: 'Checked twice' },
      'test-token',
    )
    const result = within(await screen.findByRole('status'))
    expect(result.getByText('Trust platform')).toBeInTheDocument()
    expect(result.getByText('Mismatch')).toBeInTheDocument()
    expect(result.getByText('The platform read disagrees with every other source.')).toBeInTheDocument()
    expect(onRecorded).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Record read' })).not.toBeInTheDocument()
  })

  it('shows an UNCHECKED verdict as unverified, never as a success', async () => {
    ;(apiPost as Mock).mockResolvedValue(row({ verdict: 'UNCHECKED', trustNote: 'Nothing has checked this.' }))
    open()
    fill({ from: '2026-09-01', to: '2026-09-28', value: '23', source: 'Apple Ads console' })
    fireEvent.click(screen.getByRole('button', { name: 'Record read' }))
    const result = within(await screen.findByRole('status'))
    expect(result.getByText('Unchecked')).toBeInTheDocument()
    expect(result.queryByText(/recorded.*verified/i)).not.toBeInTheDocument()
  })

  it('surfaces the API error detail and keeps the form so the read can be fixed', async () => {
    ;(apiPost as Mock).mockRejectedValue({ detail: 'A read must end yesterday (2026-10-09) or earlier' })
    open()
    fill({ from: '2026-09-01', to: '2026-09-28', value: '23', source: 'Apple Ads console' })
    fireEvent.click(screen.getByRole('button', { name: 'Record read' }))
    expect(await screen.findByText('A read must end yesterday (2026-10-09) or earlier')).toBeInTheDocument()
    expect(screen.getByLabelText('Value')).toHaveValue('23')
    expect(screen.getByRole('button', { name: 'Record read' })).toBeEnabled()
  })

  it('does not post twice while a read is being recorded', async () => {
    let resolve: (r: unknown) => void = () => {}
    ;(apiPost as Mock).mockReturnValue(new Promise((r) => (resolve = r)))
    open()
    fill({ from: '2026-09-01', to: '2026-09-28', value: '23', source: 'Apple Ads console' })
    const button = screen.getByRole('button', { name: 'Record read' })
    fireEvent.click(button)
    fireEvent.click(button)
    expect(apiPost).toHaveBeenCalledTimes(1)
    resolve(row())
    await screen.findByRole('status')
  })

  it('says there is nothing to record against when the ledger has no rows', () => {
    open({ rows: [], initial: undefined })
    expect(screen.getByText(/connect a channel/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Record read' })).not.toBeInTheDocument()
  })
})
