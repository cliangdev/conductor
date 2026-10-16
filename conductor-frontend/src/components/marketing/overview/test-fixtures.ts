// Shared fixtures for the marketing overview tests.

import type {
  MarketingChannelDetailResponse,
  MarketingOverviewResponse,
  OverviewChannel,
  OverviewFunnelStage,
  OverviewMismatch,
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
    connectHint: { connectors: [{ connectorId: 'gsc', name: 'Search Console' }] },
    metrics: [metric()],
    ...overrides,
  }
}

/** A channel the platform lists but has no connector for yet (status COMING_SOON). */
export function comingSoonChannel(overrides: Partial<OverviewChannel> = {}): OverviewChannel {
  return channel({
    channel: 'NEWSLETTER' as unknown as OverviewChannel['channel'],
    label: 'Newsletter',
    status: 'COMING_SOON',
    lastFetchedAt: null,
    dataCompleteThrough: null,
    connections: [],
    connectHint: null,
    metrics: [],
    ...overrides,
  })
}

export function overviewResponse(overrides: Partial<MarketingOverviewResponse> = {}): MarketingOverviewResponse {
  return {
    window: { from: '2026-08-18', to: '2026-09-14', dataCompleteThroughMin: '2026-09-14', previousFrom: '2026-07-21', previousTo: '2026-08-17' },
    funnel: [
      { key: 'impressions', label: 'Impressions', value: 10000, previous: 8000, change: 2000, source: 'SEARCH', path: 'WEB', available: true },
      { key: 'clicks', label: 'Clicks', value: 500, previous: 450, change: 50, source: 'SEARCH', path: 'WEB', available: true, conversionFromKey: 'impressions' },
      { key: 'visitors', label: 'Visitors', value: 400, previous: 380, change: 20, source: 'WEB', path: 'WEB', available: true, conversionFromKey: 'clicks' },
      {
        key: 'product_stages',
        label: 'In-product stages',
        value: null,
        previous: null,
        change: null,
        source: 'PRODUCT',
        path: 'PRODUCT',
        available: false,
        unavailableReason: 'No product data source yet',
      },
    ],
    channels: [
      channel({
        channel: 'WEB',
        label: 'Web',
        connectHint: { connectors: [{ connectorId: 'posthog', name: 'Analytics' }] },
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
        connectHint: { connectors: [{ connectorId: 'meta', name: 'Meta' }, { connectorId: 'tiktok', name: 'TikTok' }, { connectorId: 'youtube', name: 'YouTube' }] },
        metrics: [metric({ metric: 'followers', label: 'Followers', value: 3200 })],
      }),
      channel({
        channel: 'STORE',
        label: 'App Store',
        connections: [{ id: 'c2', connectorId: 'app-store-connect', name: 'Rexipe', status: 'LIVE' }],
        connectHint: { connectors: [{ connectorId: 'app-store-connect', name: 'App Store Connect' }] },
        metrics: [
          metric({ metric: 'impressions', label: 'Impressions', value: 52000 }),
          metric({ metric: 'downloads', label: 'Downloads', value: 310 }),
        ],
      }),
      channel({
        channel: 'ADS',
        label: 'Ads',
        status: 'NOT_CONNECTED',
        lastFetchedAt: null,
        connections: [],
        connectHint: { connectors: [{ connectorId: 'apple-search-ads', name: 'Apple Search Ads' }] },
        metrics: [],
      }),
    ],
    ...overrides,
  }
}

export function detailResponse(overrides: Partial<MarketingChannelDetailResponse> = {}): MarketingChannelDetailResponse {
  return {
    window: { from: '2026-08-18', to: '2026-09-14', dataCompleteThroughMin: '2026-09-14', previousFrom: '2026-07-21', previousTo: '2026-08-17' },
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

const READ_ONLY_TARGET = { value: 200, direction: 'UP' as const, readOnly: true, source: 'Set in Rexipe backend' }

/** The In-app channel with live data: stages in funnel order, a read-only target and a small-sample stage. */
export function productChannel(overrides: Partial<OverviewChannel> = {}): OverviewChannel {
  return channel({
    channel: 'PRODUCT',
    label: 'In-app',
    targetsEditable: false,
    connections: [{ id: 'c9', connectorId: 'product-metrics', name: 'Rexipe backend', status: 'LIVE' }],
    connectHint: { connectors: [{ connectorId: 'product-metrics', name: 'Product metrics' }] },
    metrics: [
      metric({ metric: 'trial_started', label: 'Trials started', value: 140, previous: 120, change: 20, changePct: 0.167, funnelOrder: 4, target: READ_ONLY_TARGET, provisionalDays: 2 }),
      metric({ metric: 'paid', label: 'Paid', value: 12, previous: 10, change: 2, changePct: 0.2, funnelOrder: 5, noise: true, insufficientData: true }),
      metric({ metric: 'cost_per_trial', label: 'Cost per trial', unit: 'CURRENCY', goodDirection: 'DOWN', value: 4.2, previous: 4.8, change: -0.6, changePct: -0.125 }),
      metric({ metric: 'first_open', label: 'First opens', value: 900, previous: 800, change: 100, changePct: 0.125, funnelOrder: 1 }),
      metric({ metric: 'onboarding_started', label: 'Onboarding started', value: 560, previous: 500, change: 60, changePct: 0.12, funnelOrder: 2 }),
    ],
    ...overrides,
  })
}

/** Funnel stages of the In-app path, as the API orders them: right after store downloads. */
export function productFunnelStages(): OverviewFunnelStage[] {
  const stage = (key: string, label: string, value: number, from: string): OverviewFunnelStage => ({
    key: `product_${key}`, label, value, previous: value - 10, change: 10, source: 'PRODUCT', path: 'PRODUCT', available: true, conversionFromKey: from,
  })
  return [
    { key: 'store_downloads', label: 'Downloads', value: 1000, previous: 900, change: 100, source: 'STORE', path: 'STORE', available: true },
    stage('first_open', 'First opens', 900, 'store_downloads'),
    stage('onboarding_started', 'Onboarding started', 560, 'product_first_open'),
    stage('trial_started', 'Trials started', 140, 'product_onboarding_started'),
    stage('paid', 'Paid', 12, 'product_trial_started'),
  ]
}

export function mismatch(overrides: Partial<OverviewMismatch> = {}): OverviewMismatch {
  return {
    metric: 'downloads',
    label: 'Downloads',
    referenceValue: 1190,
    channelValue: 1204,
    gapPct: 0.0116,
    from: '2026-09-01',
    to: '2026-09-12',
    channelSource: 'App Store Connect',
    referenceSource: 'Rexipe backend',
    ...overrides,
  }
}
