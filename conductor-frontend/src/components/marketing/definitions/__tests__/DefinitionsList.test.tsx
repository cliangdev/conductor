import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import type { MarketingDefinitionEntry } from '../types'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => (err as { detail?: string } | null)?.detail ?? fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'test-token', loading: false }),
}))

import { apiGet } from '@/lib/api'
import { DefinitionsList } from '../DefinitionsList'

function entry(overrides: Partial<MarketingDefinitionEntry> & Pick<MarketingDefinitionEntry, 'metricRef'>): MarketingDefinitionEntry {
  const [channel, key] = overrides.metricRef.split('.')
  return {
    channel,
    key,
    label: key,
    unit: 'COUNT',
    aggregation: 'SUM',
    definition: { source: 'A report', numerator: 'clicks' },
    ...overrides,
  }
}

const DEFINITIONS: MarketingDefinitionEntry[] = [
  entry({
    metricRef: 'STORE.conversion_rate_search',
    label: 'Conversion rate (search)',
    unit: 'PERCENT',
    aggregation: 'DERIVED',
    definition: {
      source: 'App Store Connect analytics',
      numerator: 'total_downloads',
      denominator: 'unique_impressions',
      sourceType: 'APP_STORE_SEARCH',
      timeZone: 'Apple (Pacific)',
    },
  }),
  entry({
    metricRef: 'ADS.installs',
    label: 'Installs',
    definition: { source: 'Apple Ads campaign report, tapInstalls', numerator: 'tap_installs', timeZone: 'UTC' },
  }),
  entry({
    metricRef: 'WEB.sessions',
    label: 'Sessions',
    definition: { source: 'PostHog events', numerator: 'sessions', exclusions: 'team browsers and share pages' },
  }),
  entry({ metricRef: 'WEB.visitors', label: 'Visitors', aggregation: 'WINDOW_UNIQUE', definition: { source: 'PostHog $pageview', numerator: 'visitors' } }),
]

beforeEach(() => {
  vi.clearAllMocks()
  ;(apiGet as Mock).mockResolvedValue({ definitions: DEFINITIONS })
})

describe('DefinitionsList', () => {
  it('reads the definitions endpoint', async () => {
    render(<DefinitionsList projectId="proj-1" />)
    await screen.findByRole('region', { name: 'Web' })
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/definitions', 'test-token')
  })

  it('groups metrics by channel in the overview order, each with label, metricRef and one definition line', async () => {
    render(<DefinitionsList projectId="proj-1" />)
    await screen.findByRole('region', { name: 'Web' })
    const names = screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))
    expect(names).toEqual(['Web', 'App Store', 'Ads'])

    const web = within(screen.getByRole('region', { name: 'Web' }))
    expect(web.getByText('Sessions')).toBeInTheDocument()
    expect(web.getByText('WEB.sessions')).toBeInTheDocument()
    expect(
      web.getByText('Counts sessions · Source: PostHog events · Excludes: team browsers and share pages'),
    ).toBeInTheDocument()
    expect(web.getByText('Visitors')).toBeInTheDocument()

    const store = within(screen.getByRole('region', { name: 'App Store' }))
    expect(
      store.getByText(
        'Total downloads divided by unique impressions · App Store search only · Source: App Store Connect analytics · Days in Apple (Pacific)',
      ),
    ).toBeInTheDocument()

    const ads = within(screen.getByRole('region', { name: 'Ads' }))
    expect(ads.getByText('Counts tap installs · Source: Apple Ads campaign report, tapInstalls · Days in UTC')).toBeInTheDocument()
  })

  it('filters by the search box on label, metricRef and definition text, and drops empty channels', async () => {
    render(<DefinitionsList projectId="proj-1" />)
    await screen.findByRole('region', { name: 'Web' })
    const search = screen.getByRole('searchbox', { name: /search metrics/i })

    fireEvent.change(search, { target: { value: 'ads.inst' } })
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(['Ads'])

    fireEvent.change(search, { target: { value: 'share pages' } })
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(['Web'])
    expect(screen.queryByText('Visitors')).not.toBeInTheDocument()

    fireEvent.change(search, { target: { value: 'no such metric' } })
    expect(screen.queryAllByRole('region')).toHaveLength(0)
    expect(screen.getByText(/No metrics match/)).toBeInTheDocument()

    fireEvent.change(search, { target: { value: '' } })
    expect(screen.getAllByRole('region')).toHaveLength(3)
  })

  it('shows the API error with a retry', async () => {
    ;(apiGet as Mock).mockRejectedValueOnce({ detail: 'Not allowed' })
    render(<DefinitionsList projectId="proj-1" />)
    expect(await screen.findByText('Not allowed')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByRole('region', { name: 'Web' })).toBeInTheDocument()
  })

  it('shows an empty state when the workspace has no definitions yet', async () => {
    ;(apiGet as Mock).mockResolvedValue({ definitions: [] })
    render(<DefinitionsList projectId="proj-1" />)
    expect(await screen.findByText('No metric definitions yet')).toBeInTheDocument()
  })

  it('lists a channel it does not know after the known ones', async () => {
    ;(apiGet as Mock).mockResolvedValue({ definitions: [entry({ metricRef: 'NEWSLETTER.opens', label: 'Opens' }), ...DEFINITIONS] })
    render(<DefinitionsList projectId="proj-1" />)
    await screen.findByRole('region', { name: 'Web' })
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(['Web', 'App Store', 'Ads', 'NEWSLETTER'])
  })
})
