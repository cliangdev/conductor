import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  isClientError: (err: unknown) => err instanceof Error && /API error 4\d\d\b/.test(err.message),
}))

import { apiGet } from '../mcp/api.js'
import { getMarketingDefinitions } from '../mcp/tools/marketing-definitions.js'
import { getMarketingInsights } from '../mcp/tools/insights.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

beforeEach(() => vi.clearAllMocks())

const payload = () => ({
  definitions: [
    {
      metricRef: 'ADS.installs',
      channel: 'ADS',
      key: 'installs',
      label: 'Installs',
      unit: 'COUNT',
      aggregation: 'SUM',
      definition: {
        source: 'Apple Ads campaign report, tapInstalls (tap new downloads + tap redownloads)',
        numerator: 'tap_installs',
        denominator: null,
        sourceType: null,
        exclusions: null,
        timeZone: 'UTC',
      },
    },
    {
      metricRef: 'STORE.conversion_rate_search',
      channel: 'STORE',
      key: 'conversion_rate_search',
      label: 'Search conversion rate',
      unit: 'PERCENT',
      aggregation: 'DERIVED',
      definition: {
        source: 'App Store Connect Analytics: search total downloads over unique impressions',
        numerator: 'total_downloads',
        denominator: 'unique_impressions',
        sourceType: 'APP_STORE_SEARCH',
        exclusions: null,
        timeZone: null,
      },
    },
    {
      metricRef: 'PRODUCT.trial_started',
      channel: 'PRODUCT',
      key: 'trial_started',
      label: 'Trials started',
      unit: 'COUNT',
      aggregation: 'SUM',
      definition: {
        source: 'Product metrics feed (Rexipe)',
        numerator: 'product:trial_started',
        denominator: null,
        sourceType: null,
        exclusions: 'Likely App Review installs',
        timeZone: null,
      },
    },
  ],
})

describe('get_marketing_definitions', () => {
  it('reads the definitions endpoint and returns one compact object per metric with nulls dropped', async () => {
    mocked(apiGet).mockResolvedValue(payload())

    const out = await getMarketingDefinitions(config, {})

    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/definitions', config)
    const defs = out['definitions'] as Record<string, any>[]
    expect(defs).toHaveLength(3)
    expect(defs[0]).toEqual({
      metricRef: 'ADS.installs',
      label: 'Installs',
      unit: 'COUNT',
      aggregation: 'SUM',
      definition: {
        source: 'Apple Ads campaign report, tapInstalls (tap new downloads + tap redownloads)',
        numerator: 'tap_installs',
        timeZone: 'UTC',
      },
    })
    expect(defs[1]!.definition).toEqual({
      source: 'App Store Connect Analytics: search total downloads over unique impressions',
      numerator: 'total_downloads',
      denominator: 'unique_impressions',
      sourceType: 'APP_STORE_SEARCH',
    })
    expect(defs[2]!.definition.exclusions).toBe('Likely App Review installs')
    expect(defs[2]!.definition.numerator).toBe('product:trial_started')
  })

  it('filters to one channel, whatever the case', async () => {
    mocked(apiGet).mockResolvedValue(payload())

    const out = await getMarketingDefinitions(config, { channel: 'store' })

    expect((out['definitions'] as Record<string, any>[]).map((d) => d.metricRef)).toEqual([
      'STORE.conversion_rate_search',
    ])
  })

  it('returns an empty list, not an error, for a channel with no metrics yet', async () => {
    mocked(apiGet).mockResolvedValue({ definitions: payload().definitions.filter((d) => d.channel !== 'PRODUCT') })

    const out = await getMarketingDefinitions(config, { channel: 'PRODUCT' })

    expect(out['definitions']).toEqual([])
  })

  it('rejects an unknown channel before calling the API', async () => {
    await expect(getMarketingDefinitions(config, { channel: 'EMAIL' })).rejects.toThrow(/Unknown channel "EMAIL"/)
    expect(apiGet).not.toHaveBeenCalled()
  })
})

describe('get_marketing_insights keeps each metric definition and note', () => {
  const definition = {
    source: 'PostHog: distinct persons with a page view in the window',
    numerator: 'visitors',
    exclusions: 'Internal-users cohort and excluded share-page paths, when set on the connection',
  }
  const note = 'Unique visitors are stored for 7, 28 and 90 days'
  const metrics = [
    { metric: 'visitors', label: 'Visitors', unit: 'COUNT', value: null, noise: false, series: [], note, definition },
    { metric: 'sessions', label: 'Sessions', unit: 'COUNT', value: 68, noise: false, series: [] },
  ]
  const channel = {
    channel: 'WEB',
    label: 'Web',
    status: 'LIVE',
    dataCompleteThrough: '2026-10-26',
    connections: [],
    metrics,
  }

  it('on the channel overview', async () => {
    mocked(apiGet).mockResolvedValue({
      window: { from: '2026-09-29', to: '2026-10-26' },
      funnel: [],
      channels: [channel],
    })

    const result = await getMarketingInsights(config, { include: ['channels'], window: '30d' })

    const web = (result['marketing'] as Record<string, any>).channels[0]
    expect(web.metrics[0]).toMatchObject({ metric: 'visitors', note, definition })
    expect(web.metrics[0]).not.toHaveProperty('value')
    expect(web.metrics[1]).not.toHaveProperty('definition')
    expect(web.metrics[1]).not.toHaveProperty('note')
  })

  it('on one channel in detail', async () => {
    mocked(apiGet).mockImplementation(async (path: string) =>
      path.includes('/channels/WEB')
        ? { window: { from: '2026-09-29', to: '2026-10-26' }, channel, dimensions: [] }
        : { window: { from: '2026-09-29', to: '2026-10-26' }, funnel: [], channels: [channel] }
    )

    const result = await getMarketingInsights(config, { include: ['channels'], window: '30d', channel: 'WEB' })

    const detail = (result['marketing'] as Record<string, any>).channelDetail
    expect(detail.metrics[0]).toMatchObject({ note, definition })
  })
})
