// Shared fixtures for the marketing overview tests.

import type {
  MarketingChannelDetailResponse,
  MarketingOverviewResponse,
  OverviewChannel,
  OverviewMetric,
  OverviewSeriesPoint,
} from './types'

export function series(values: (number | null)[], provisionalLast = 0): OverviewSeriesPoint[] {
  return values.map((value, i) => ({
    day: `2026-09-${String(i + 1).padStart(2, '0')}`,
    value,
    provisional: i >= values.length - provisionalLast,
  }))
}

export function metric(overrides: Partial<OverviewMetric> = {}): OverviewMetric {
  return {
    metric: 'clicks',
    label: 'Clicks',
    unit: 'COUNT',
    goodDirection: 'UP',
    value: 1200,
    previous: 1000,
    change: 200,
    changePct: 0.2,
    noise: false,
    target: null,
    series: series([10, 12, 11, 14, 15], 1),
    ...overrides,
  }
}

export function channel(overrides: Partial<OverviewChannel> = {}): OverviewChannel {
  return {
    channel: 'SEARCH',
    label: 'Search',
    status: 'LIVE',
    lastFetchedAt: new Date(Date.now() - 2 * 3600_000).toISOString(),
    dataCompleteThrough: '2026-09-14',
    connections: [{ id: 'c1', connectorId: 'gsc', name: 'Site search', status: 'LIVE' }],
    connectHint: { connectorId: 'gsc' },
    metrics: [metric()],
    ...overrides,
  }
}

export function overviewResponse(overrides: Partial<MarketingOverviewResponse> = {}): MarketingOverviewResponse {
  return {
    window: { from: '2026-08-18', to: '2026-09-14', previousFrom: '2026-07-21', previousTo: '2026-08-17' },
    funnel: [
      { key: 'impressions', label: 'Impressions', value: 10000, previous: 8000, change: 2000, source: 'SEARCH', available: true },
      { key: 'clicks', label: 'Clicks', value: 500, previous: 450, change: 50, source: 'SEARCH', available: true },
      { key: 'visitors', label: 'Visitors', value: 400, previous: 380, change: 20, source: 'WEB', available: true },
      {
        key: 'product_stages',
        label: 'In-product stages',
        value: null,
        previous: null,
        change: null,
        source: 'PRODUCT',
        available: false,
        unavailableReason: 'No product data source yet',
      },
    ],
    channels: [
      channel({
        channel: 'WEB',
        label: 'Web',
        connectHint: { connectorId: 'posthog' },
        metrics: [metric({ metric: 'visitors', label: 'Visitors', value: 400 })],
      }),
      channel({
        metrics: [
          metric(),
          metric({
            metric: 'ctr',
            label: 'Click-through rate',
            unit: 'PERCENT',
            value: 0.031,
            previous: 0.03,
            change: 0.001,
            changePct: 0.033,
            noise: true,
            target: { value: 0.04, direction: 'UP' },
          }),
          metric({ metric: 'position', label: 'Average position', unit: 'POSITION', goodDirection: 'DOWN', value: 8.2, previous: 9.1, change: -0.9, changePct: -0.099 }),
        ],
      }),
      channel({
        channel: 'SOCIAL',
        label: 'Social',
        connectHint: { connectorId: 'meta' },
        metrics: [metric({ metric: 'followers', label: 'Followers', value: 3200 })],
      }),
      channel({ channel: 'STORE', label: 'App Store', status: 'COMING_SOON', connections: [], connectHint: null, metrics: [] }),
      channel({
        channel: 'ADS',
        label: 'Apple Ads',
        status: 'NOT_CONNECTED',
        lastFetchedAt: null,
        connections: [],
        connectHint: { connectorId: 'apple-search-ads' },
        metrics: [],
      }),
    ],
    ...overrides,
  }
}

export function detailResponse(overrides: Partial<MarketingChannelDetailResponse> = {}): MarketingChannelDetailResponse {
  return {
    window: { from: '2026-08-18', to: '2026-09-14', previousFrom: '2026-07-21', previousTo: '2026-08-17' },
    channel: channel({
      metrics: [
        metric(),
        metric({ metric: 'ctr', label: 'Click-through rate', unit: 'PERCENT', value: 0.031, changePct: 0.033, target: { value: 0.04, direction: 'UP' } }),
      ],
    }),
    dimensions: [
      {
        key: 'queries',
        label: 'Top queries',
        columns: [
          { key: 'clicks', label: 'Clicks', unit: 'COUNT' },
          { key: 'position', label: 'Average position', unit: 'POSITION' },
        ],
        rows: [
          { key: 'a', label: 'alpha query', values: [10, 3.2] },
          { key: 'b', label: 'beta query', values: [50, 7.5] },
          { key: 'c', label: 'gamma query', values: [30, 1.4] },
        ],
      },
      {
        key: 'referrers',
        label: 'Top referrers',
        columns: [{ key: 'sessions', label: 'Sessions', unit: 'COUNT' }],
        rows: [],
      },
    ],
    topPosts: null,
    ...overrides,
  }
}
