import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { backend, ledger, platformRead, row } from './fixtures'

let role: 'ADMIN' | 'CREATOR' | 'REVIEWER' = 'ADMIN'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => (err as { detail?: string } | null)?.detail ?? fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'test-token', loading: false }),
}))

vi.mock('@/contexts/PermissionsContext', async () => {
  const { can } = await import('@/lib/permissions')
  return {
    usePermissions: () => ({ roles: [role], loading: false, can: (c: Parameters<typeof can>[1]) => can([role], c), refresh: vi.fn() }),
  }
})

import { apiGet, apiPost } from '@/lib/api'
import { VerificationLedger } from '../VerificationLedger'

function tableRow(name: string): HTMLElement {
  const cell = screen.getAllByRole('rowheader').find((c) => c.textContent?.includes(name))
  if (!cell) throw new Error(`no row for ${name}`)
  return cell.closest('tr') as HTMLElement
}

beforeEach(() => {
  vi.clearAllMocks()
  role = 'ADMIN'
  ;(apiGet as Mock).mockResolvedValue(ledger())
})

describe('VerificationLedger', () => {
  it('reads the 28 day ledger by default and offers 7d, 28d and 90d', async () => {
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/verification?window=28d', 'test-token')
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['7d', '28d', '90d'])

    fireEvent.click(screen.getByRole('tab', { name: '90d' }))
    await waitFor(() =>
      expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/verification?window=90d', 'test-token'),
    )
  })

  it('shows the summary strip from the ledger summary', async () => {
    render(<VerificationLedger projectId="proj-1" />)
    const strip = within(await screen.findByRole('group', { name: 'Verification summary' }))
    expect(strip.getByText('Both agree').parentElement).toHaveTextContent('1')
    expect(strip.getByText('Unchecked').parentElement).toHaveTextContent('1')
    expect(strip.getByText('Mismatched').parentElement).toHaveTextContent('1')
    expect(strip.getByText('Stale reads').parentElement).toHaveTextContent('0')
  })

  it('renders BOTH, UNCHECKED and a mismatched CONNECTOR row distinctly', async () => {
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')

    const both = within(tableRow('Impressions'))
    expect(both.getByText('Both agree')).toBeInTheDocument()
    expect(both.queryByText('Mismatch')).not.toBeInTheDocument()

    const unchecked = within(tableRow('Post views'))
    const uncheckedBadge = unchecked.getByText('Unchecked').closest('span[class*="rounded-full"]') as HTMLElement
    expect(uncheckedBadge.className).toContain('border-dashed')
    expect(unchecked.queryByText('Both agree')).not.toBeInTheDocument()
    expect(unchecked.queryByText('Mismatch')).not.toBeInTheDocument()
    expect(unchecked.getByText('Not verified: no second source')).toBeInTheDocument()

    const connector = within(tableRow('Installs'))
    expect(connector.getByText('Trust connector')).toBeInTheDocument()
    expect(connector.getByText('Mismatch')).toBeInTheDocument()

    // The verified badge is green, the unchecked one never is.
    const bothBadge = both.getByText('Both agree').closest('span[class*="rounded-full"]') as HTMLElement
    expect(bothBadge.className).toContain('text-status-done')
    expect(uncheckedBadge.className).not.toContain('text-status-done')
  })

  it('shows each row: metric with its ref, Conductor value with days, backend value with days compared, platform read', async () => {
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')

    const search = within(tableRow('Impressions'))
    expect(search.getByText('SEARCH.impressions')).toBeInTheDocument()
    expect(search.getByText('Sep 1 – Sep 28 · 28 days')).toBeInTheDocument()
    expect(search.getByText('Search Console UI')).toBeInTheDocument()
    expect(search.getByText('Sep 1 – Sep 28 · recorded Sep 30')).toBeInTheDocument()
    expect(search.getByText('Search Console and the platform read agree.')).toBeInTheDocument()

    const ads = within(tableRow('Installs'))
    expect(ads.getByText('ADS.installs')).toBeInTheDocument()
    expect(ads.getByText('12 days compared · Sep 14 – Sep 25')).toBeInTheDocument()
    expect(ads.getByText('Conductor had 7 over these days')).toBeInTheDocument()
    expect(ads.getByText('No platform read yet')).toBeInTheDocument()
  })

  it('shows a dash, never 0, where a figure is unknown', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      ledger({
        rows: [row({ conductorValue: null, platformRead: null, backend: null, verdict: 'UNCHECKED', trustNote: 'No data.' })],
        summary: { both: 0, connector: 0, backend: 0, platform: 0, unchecked: 1, mismatched: 0, staleReads: 0 },
      }),
    )
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    const r = within(tableRow('Impressions'))
    expect(r.getAllByText('—').length).toBeGreaterThanOrEqual(3)
    expect(r.queryByText('0')).not.toBeInTheDocument()
  })

  it('marks a stale platform read and counts it', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      ledger({
        rows: [row({ readStale: true, platformRead: platformRead({ recordedAt: '2026-09-01T10:00:00Z' }) })],
        summary: { both: 1, connector: 0, backend: 0, platform: 0, unchecked: 0, mismatched: 0, staleReads: 1 },
      }),
    )
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    expect(within(tableRow('Impressions')).getByText('Stale')).toBeInTheDocument()
  })

  it('shows what the platform read says about Conductor, and when it cannot be compared', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      ledger({
        rows: [
          row({
            platformRead: platformRead({ value: 600, conductorValueOverReadDays: 550, matches: false }),
            verdict: 'PLATFORM',
            mismatch: true,
            trustNote: 'The platform read disagrees with every other source.',
          }),
          row({
            channel: 'WEB',
            metric: 'visitors',
            metricRef: 'WEB.visitors',
            label: 'Visitors',
            platformRead: platformRead({ conductorValueOverReadDays: null, matches: null }),
            verdict: 'CONNECTOR',
          }),
        ],
      }),
    )
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    const differing = within(tableRow('Impressions'))
    expect(differing.getByText('Trust platform')).toBeInTheDocument()
    expect(differing.getByText('Conductor had 550 over these days')).toBeInTheDocument()
    expect(differing.getByText('Mismatch')).toBeInTheDocument()
    expect(within(tableRow('Visitors')).getByText('Cannot be compared with Conductor')).toBeInTheDocument()
  })

  it('"Only problems" keeps mismatched, unchecked and stale rows and drops verified ones', async () => {
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    expect(screen.getAllByRole('rowheader')).toHaveLength(3)

    fireEvent.click(screen.getByLabelText('Only problems'))
    const names = screen.getAllByRole('rowheader').map((c) => c.textContent)
    expect(names.some((n) => n?.includes('Impressions'))).toBe(false)
    expect(names.some((n) => n?.includes('Installs'))).toBe(true)
    expect(names.some((n) => n?.includes('Post views'))).toBe(true)

    fireEvent.click(screen.getByLabelText('Only problems'))
    expect(screen.getAllByRole('rowheader')).toHaveLength(3)
  })

  it('says so when "Only problems" leaves nothing', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      ledger({
        rows: [row()],
        summary: { both: 1, connector: 0, backend: 0, platform: 0, unchecked: 0, mismatched: 0, staleReads: 0 },
      }),
    )
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    fireEvent.click(screen.getByLabelText('Only problems'))
    expect(screen.getByText('No problems in this window.')).toBeInTheDocument()
  })

  it('shows an empty state when no channel can be checked yet', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      ledger({ rows: [], window: { from: '2026-09-01', to: null }, summary: { both: 0, connector: 0, backend: 0, platform: 0, unchecked: 0, mismatched: 0, staleReads: 0 } }),
    )
    render(<VerificationLedger projectId="proj-1" />)
    expect(await screen.findByText('Nothing to verify yet')).toBeInTheDocument()
  })

  it('shows the API error and retries', async () => {
    ;(apiGet as Mock).mockRejectedValueOnce({ detail: 'Unknown window' })
    render(<VerificationLedger projectId="proj-1" />)
    expect(await screen.findByText('Unknown window')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('table')).toBeInTheDocument()
  })

  it('opens the record dialog from a row with its metric chosen, posts, and reloads the ledger', async () => {
    ;(apiPost as Mock).mockResolvedValue(row({ channel: 'SOCIAL', metric: 'views', metricRef: 'SOCIAL.views', label: 'Post views', verdict: 'CONNECTOR', trustNote: 'The platform read agrees with the connector.' }))
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    const before = (apiGet as Mock).mock.calls.length

    fireEvent.click(within(tableRow('Post views')).getByRole('button', { name: 'Record a read for Post views' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Channel')).toHaveValue('SOCIAL')
    expect(within(dialog).getByLabelText('Metric')).toHaveValue('views')

    fireEvent.change(within(dialog).getByLabelText('First day'), { target: { value: '2026-09-01' } })
    fireEvent.change(within(dialog).getByLabelText('Last day'), { target: { value: '2026-09-07' } })
    fireEvent.change(within(dialog).getByLabelText('Value'), { target: { value: '1180' } })
    fireEvent.change(within(dialog).getByLabelText('Source'), { target: { value: 'TikTok analytics' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Record read' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    expect((apiPost as Mock).mock.calls[0][0]).toBe('/api/v2/projects/proj-1/marketing/verification/reads')
    expect(await within(dialog).findByText('Trust connector')).toBeInTheDocument()
    await waitFor(() => expect((apiGet as Mock).mock.calls.length).toBeGreaterThan(before))
  })

  it('hides the record actions from a reviewer, who cannot record', async () => {
    role = 'REVIEWER'
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    expect(screen.queryByRole('button', { name: /Record a read/ })).not.toBeInTheDocument()
  })

  it('retracts a read with its reason, posts to the retract endpoint and reloads the ledger', async () => {
    ;(apiPost as Mock).mockResolvedValue({ id: 'read-1', retractedAt: '2026-11-08T10:00:00Z' })
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    const before = (apiGet as Mock).mock.calls.length

    fireEvent.click(within(tableRow('Impressions')).getByRole('button', { name: 'Retract the read for Impressions' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Retract read' }))
    expect(within(dialog).getByText('Say why the read is wrong.')).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalled()

    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: '  App stopped using it  ' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Retract read' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/verification/reads/read-1/retract',
      { reason: 'App stopped using it' },
      'test-token',
    )
    await waitFor(() => expect((apiGet as Mock).mock.calls.length).toBeGreaterThan(before))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('shows the API error when a retraction fails and keeps the dialog open', async () => {
    ;(apiPost as Mock).mockRejectedValue({ detail: 'Platform read not found' })
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    fireEvent.click(within(tableRow('Impressions')).getByRole('button', { name: 'Retract the read for Impressions' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.change(within(dialog).getByLabelText('Reason'), { target: { value: 'wrong' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Retract read' }))
    expect(await within(dialog).findByText('Platform read not found')).toBeInTheDocument()
  })

  it('offers Retract only on a row that has a read', async () => {
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    expect(within(tableRow('Impressions')).getByRole('button', { name: /Retract/ })).toBeInTheDocument()
    expect(within(tableRow('Installs')).queryByRole('button', { name: /Retract/ })).not.toBeInTheDocument()
  })

  it('hides Retract from a reviewer', async () => {
    role = 'REVIEWER'
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    expect(screen.queryByRole('button', { name: /Retract/ })).not.toBeInTheDocument()
  })

  it('keeps a backend row whose figure matches readable (no mismatch marker)', async () => {
    ;(apiGet as Mock).mockResolvedValue(
      ledger({
        rows: [row({ channel: 'STORE', metric: 'downloads', metricRef: 'STORE.downloads', label: 'Downloads', backend: backend({ value: 30, conductorValue: 30 }), platformRead: null, verdict: 'BOTH' })],
      }),
    )
    render(<VerificationLedger projectId="proj-1" />)
    await screen.findByRole('table')
    const r = within(tableRow('Downloads'))
    expect(r.getByText('12 days compared · Sep 14 – Sep 25')).toBeInTheDocument()
    expect(r.queryByText('Mismatch')).not.toBeInTheDocument()
  })
})
