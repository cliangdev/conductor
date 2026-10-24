import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  isClientError: (err: unknown) => err instanceof Error && /API error 4\d\d\b/.test(err.message),
  ApiError: class ApiError extends Error {
    status: number
    constructor(status: number, message: string) {
      super(message)
      this.name = 'ApiError'
      this.status = status
    }
  },
}))

import { apiGet, ApiError } from '../mcp/api.js'
import { getMarketingInsights, bucketSeries } from '../mcp/tools/insights.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}

const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

function fixture() {
  return {
    window: { from: '2026-08-16T00:00:00Z', to: '2026-09-15T00:00:00Z', days: 30 },
    totals: { key: 'all', posts: 12, views: 1000, likes: 100, comments: 10, shares: 5, saves: 2, engagementRate: 0.11700001, medianViews: 80, avgViewPct: null },
    byPlatform: [
      { key: 'instagram', label: 'Instagram', posts: 8, views: 800, likes: 90, comments: 8, shares: 4, saves: 2, engagementRate: 0.13, medianViews: 90, avgViewPct: null },
      { key: 'facebook', label: 'Facebook', posts: 4, views: 200, likes: 10, comments: 2, shares: 1, saves: 0, engagementRate: null, medianViews: null, avgViewPct: null },
    ],
    byFormat: [
      { key: 'FEED', posts: 10, views: 900, likes: 95, comments: 9, shares: 5, saves: 2, engagementRate: 0.1233333, medianViews: 85, avgViewPct: null },
    ],
    byHour: [
      { key: '9', posts: 3, views: 300, likes: 40, comments: 4, shares: 2, saves: 1, engagementRate: 0.2, medianViews: 90, avgViewPct: null },
      { key: '14', posts: 1, views: 50, likes: 20, comments: 2, shares: 1, saves: 0, engagementRate: 0.5, medianViews: 50, avgViewPct: null },
      { key: '18', posts: 5, views: 400, likes: 30, comments: 3, shares: 1, saves: 0, engagementRate: 0.1, medianViews: 60, avgViewPct: null },
      { key: '20', posts: 2, views: 100, likes: 15, comments: 1, shares: 0, saves: 0, engagementRate: 0.16, medianViews: 40, avgViewPct: null },
    ],
    byWeekday: [
      { key: 'MONDAY', posts: 2, views: 200, likes: 20, comments: 2, shares: 1, saves: 0, engagementRate: 0.115, medianViews: 90, avgViewPct: null },
    ],
    topPosts: Array.from({ length: 7 }, (_, i) => ({
      workItemId: `w${i}`,
      displayId: `MK-${i}`,
      title: `Post ${i}`,
      captionExcerpt: 'Hello world',
      targetId: `t${i}`,
      platform: 'instagram',
      format: 'FEED',
      accountLabel: '@acme',
      permalink: `https://instagram.com/p/${i}`,
      firedAt: '2026-09-01T00:00:00Z',
      views: 100 - i,
      likes: 10,
      comments: 1,
      shares: 1,
      saves: 0,
      engagementRate: 0.12345678 - i * 0.001,
      avgViewPct: null,
      observedAt: '2026-09-02T00:00:00Z',
    })),
    bottomPosts: Array.from({ length: 5 }, (_, i) => ({
      workItemId: `bw${i}`,
      displayId: `MK-b${i}`,
      title: null,
      captionExcerpt: null,
      targetId: `bt${i}`,
      platform: 'facebook',
      format: null,
      accountLabel: null,
      permalink: null,
      firedAt: null,
      views: 5 + i,
      likes: 0,
      comments: 0,
      shares: 0,
      saves: 0,
      engagementRate: 0.01,
      avgViewPct: null,
      observedAt: '2026-09-02T00:00:00Z',
    })),
    movers: [
      { metric: 'views', current: 1000, previous: 800, deltaPct: 25.00001 },
      { metric: 'engagementRate', current: 0.11700001, previous: 0.1, deltaPct: null },
    ],
    coverage: { platforms: ['instagram', 'facebook'], notes: ['TikTok reconnect needed for insights.'] },
    creatives: [
      { creativeId: 'c12b', label: '12b', headline: 'Hook B', posts: 3, views: 900, engagementRate: 0.199999, avgViewPct: null, views72h: 800, byPlatform: [] },
      { creativeId: 'c12a', label: '12a', headline: 'Hook A', posts: 2, views: 400, engagementRate: 0.1, avgViewPct: null, views72h: null, byPlatform: [] },
    ],
  }
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('get_marketing_insights', () => {
  it('trims the response: top 3 hour/weekday groups with >=2 posts, top 5 / bottom 3 posts, nulls omitted, rates rounded to 4 decimals', async () => {
    mocked(apiGet).mockResolvedValue(fixture())

    const result = await getMarketingInsights(config, {})

    expect(mocked(apiGet).mock.calls[0]![0]).toBe('/api/v2/projects/proj-1/marketing/insights')

    expect(result['window']).toEqual({ from: '2026-08-16T00:00:00Z', to: '2026-09-15T00:00:00Z', days: 30 })

    const totals = result['totals'] as Record<string, unknown>
    expect(totals['engagementRate']).toBe(0.117)
    expect(totals).not.toHaveProperty('avgViewPct')

    const byPlatform = result['byPlatform'] as Record<string, unknown>[]
    expect(byPlatform[1]).not.toHaveProperty('engagementRate')
    expect(byPlatform[1]).not.toHaveProperty('medianViews')

    // byHour: '14' has only 1 post, excluded despite the highest rate; the remaining three (all groups
    // with >=2 posts) come back sorted best-rate-first.
    const bestHours = result['bestHoursUtc'] as Record<string, unknown>[]
    expect(bestHours).toHaveLength(3)
    expect(bestHours.map((g) => g['key'])).toEqual(['9', '20', '18'])

    const bestWeekdays = result['bestWeekdays'] as Record<string, unknown>[]
    expect(bestWeekdays).toHaveLength(1)
    expect(bestWeekdays[0]!['key']).toBe('MONDAY')

    const topPosts = result['topPosts'] as Record<string, unknown>[]
    expect(topPosts).toHaveLength(5)
    expect(Object.keys(topPosts[0]!).sort()).toEqual(
      ['captionExcerpt', 'displayId', 'engagementRate', 'format', 'permalink', 'platform', 'title', 'views'].sort()
    )
    expect(topPosts[0]!['engagementRate']).toBe(0.1235)

    const bottomPosts = result['bottomPosts'] as Record<string, unknown>[]
    expect(bottomPosts).toHaveLength(3)
    expect(bottomPosts[0]).not.toHaveProperty('title')
    expect(bottomPosts[0]).not.toHaveProperty('permalink')
    expect(bottomPosts[0]).not.toHaveProperty('format')

    const movers = result['movers'] as Record<string, unknown>[]
    expect(movers[0]!['deltaPct']).toBe(25)
    expect(movers[1]).not.toHaveProperty('deltaPct')

    expect(result['coverage']).toEqual({ platforms: ['instagram', 'facebook'], notes: ['TikTok reconnect needed for insights.'] })

    const topCreatives = result['topCreatives'] as Record<string, unknown>[]
    expect(topCreatives).toHaveLength(2)
    expect(topCreatives[0]).toEqual({ creativeId: 'c12b', label: '12b', headline: 'Hook B', posts: 3, views: 900, engagementRate: 0.2, views72h: 800 })
    expect(topCreatives[1]).not.toHaveProperty('avgViewPct')
    expect(topCreatives[1]).not.toHaveProperty('views72h')
  })

  it('passes window and platform through as query params', async () => {
    mocked(apiGet).mockResolvedValue(fixture())

    await getMarketingInsights(config, { window: '7d', platform: 'instagram' })

    const url = String(mocked(apiGet).mock.calls[0]![0])
    expect(url).toContain('/api/v2/projects/proj-1/marketing/insights?')
    expect(url).toContain('window=7d')
    expect(url).toContain('platform=instagram')
  })

  it('propagates an API error for the dispatcher to turn into an error response', async () => {
    mocked(apiGet).mockRejectedValue(new ApiError(404, 'Project not found'))

    await expect(getMarketingInsights(config, {})).rejects.toMatchObject({ status: 404, message: 'Project not found' })
  })
})

describe('get_marketing_insights include channels', () => {
  const metric = (series: { day: string; value: number }[] = []) => ({
    metric: 'visitors', label: 'Visitors', unit: 'COUNT', value: 120.123456, asOf: '2026-10-13', previous: 100, change: 20.1, changePct: 0.2012,
    noise: false, provisionalDays: 1, target: { value: 150, direction: 'UP' }, series,
  })
  const overview = () => ({
    window: { from: '2026-09-17', to: '2026-10-14', previousFrom: '2026-08-20', previousTo: '2026-09-16' },
    funnel: [
      { key: 'visits', label: 'Visits', value: 120, previous: 100, change: 20, source: 'WEB', path: 'WEB', available: true },
      { key: 'signups', label: 'Signups', value: 30, previous: 40, source: 'PRODUCT', path: 'WEB', conversionFromKey: 'visits', available: true },
      { key: 'installs', label: 'Installs', source: 'STORE', available: false, unavailableReason: 'Not connected' },
    ],
    channels: [
      { channel: 'WEB', label: 'Web', status: 'LIVE', dataCompleteThrough: '2026-10-13', connections: [{ id: 'c1' }], metrics: [metric([{ day: '2026-10-13', value: 1 }])] },
      { channel: 'ADS', label: 'Ads', status: 'NOT_CONNECTED', connections: [], connectHint: { connectorId: 'apple-search-ads', connectors: [{ connectorId: 'apple-search-ads', name: 'Apple Search Ads' }] }, metrics: [metric()] },
    ],
  })
  const days = Array.from({ length: 90 }, (_, i) => ({ day: `d${i}`, value: i, provisional: i === 89 }))
  const detail = () => ({
    window: { from: '2026-07-17', to: '2026-10-14', previousFrom: 'a', previousTo: 'b' },
    channel: { channel: 'WEB', label: 'Web', status: 'LIVE', dataCompleteThrough: '2026-10-13', connections: [], metrics: [metric(days)] },
    dimensions: [
      {
        key: 'referrers', label: 'Referrers', columns: [{ key: 'visits', label: 'Visits', unit: 'COUNT' }],
        rows: Array.from({ length: 15 }, (_, i) => ({ key: `r${i}`, label: `ref${i}`, values: [i] })),
      },
    ],
  })

  it('default output has no marketing section and does not call the overview', async () => {
    mocked(apiGet).mockResolvedValue(fixture())
    const result = await getMarketingInsights(config, { include: ['posts'] })
    expect(result).toHaveProperty('totals')
    expect(result).not.toHaveProperty('marketing')
    expect(mocked(apiGet)).toHaveBeenCalledTimes(1)
    expect(mocked(apiGet).mock.calls[0]![0]).toBe('/api/v2/projects/proj-1/marketing/insights')
  })

  it('channels only: compact channels and funnel, no posts, no series', async () => {
    mocked(apiGet).mockResolvedValue(overview())
    const result = await getMarketingInsights(config, { include: ['channels'], window: '7d' })
    expect(mocked(apiGet).mock.calls[0]![0]).toBe('/api/v2/projects/proj-1/marketing/overview?window=7d')
    expect(result).not.toHaveProperty('totals')
    const m = result['marketing'] as Record<string, any>
    expect(m.funnel).toEqual([
      { key: 'visits', label: 'Visits', path: 'WEB', value: 120, previous: 100, changePct: 0.2 },
      { key: 'signups', label: 'Signups', path: 'WEB', value: 30, previous: 40, changePct: -0.25, conversionFromKey: 'visits', conversionPct: 0.25 },
    ])
    expect(m.unavailableStages).toEqual([{ key: 'installs', reason: 'Not connected' }])
    const web = m.channels[0]
    expect(web.dataCompleteThrough).toBe('2026-10-13')
    expect(web.metrics[0]).toEqual({
      metric: 'visitors', label: 'Visitors', unit: 'COUNT', value: 120.1235, asOf: '2026-10-13', previous: 100, changePct: 0.2012, noise: false,
      target: { value: 150, direction: 'UP' },
    })
    expect(m.channels[1]).toEqual({ channel: 'ADS', label: 'Ads', status: 'NOT_CONNECTED', connect: 'Apple Search Ads' })
  })

  it('PRODUCT: stages in funnel order with flags and a read-only target, other metrics apart, nothing for other channels', async () => {
    const o = overview()
    const stage = (key: string, label: string, order: number, extra: Record<string, unknown> = {}) => ({
      metric: key, label, unit: 'COUNT', value: 100 - order, previous: 90, changePct: 0.1, noise: false, funnelOrder: order, series: [], ...extra,
    })
    o.channels.push({
      channel: 'PRODUCT', label: 'In-app', status: 'LIVE', dataCompleteThrough: '2026-10-13', connections: [],
      metrics: [
        stage('paid', 'Paid', 5, { insufficientData: true, noise: true }),
        { metric: 'cost_per_trial', label: 'Cost per trial', unit: 'CURRENCY', value: 4.2, previous: 4.8, changePct: -0.125, noise: false, series: [] },
        stage('trial_started', 'Trials started', 4, {
          // the two trailing provisional days are after dataCompleteThrough: not part of the value
          provisionalDays: 2,
          series: [
            { day: '2026-10-12', value: 1, provisional: false },
            { day: '2026-10-13', value: 1, provisional: false },
            { day: '2026-10-14', value: 1, provisional: true },
            { day: '2026-10-15', value: 1, provisional: true },
          ],
          target: { value: 200, direction: 'UP', readOnly: true, source: 'Set in Rexipe backend' },
        }),
        stage('first_open', 'First opens', 1, {
          // a restated day inside the window does make the value provisional
          series: [
            { day: '2026-10-12', value: 1, provisional: true },
            { day: '2026-10-13', value: 1, provisional: false },
          ],
        }),
      ],
    } as never)
    mocked(apiGet).mockResolvedValue(o)
    const result = await getMarketingInsights(config, { include: ['channels'], window: '7d' })
    const product = (result['marketing'] as Record<string, any>).channels[2]
    expect(product.stages.map((s: any) => s.metric)).toEqual(['first_open', 'trial_started', 'paid'])
    expect(product.stages[0]).toMatchObject({ provisional: true })
    expect(product.stages[1]).not.toHaveProperty('provisional')
    expect(product.stages[1]).toMatchObject({
      target: { value: 200, direction: 'UP', readOnly: true, setIn: 'Set in Rexipe backend' },
    })
    expect(product.stages[1]).not.toHaveProperty('smallSample')
    expect(product.stages[2]).toMatchObject({ smallSample: true, noise: true })
    expect(product.stages[2]).not.toHaveProperty('provisional')
    expect(product.metrics.map((m: any) => m.metric)).toEqual(['cost_per_trial'])
    // other channels keep their shape: no stages, no flags
    const web = (result['marketing'] as Record<string, any>).channels[0]
    expect(web).not.toHaveProperty('stages')
    expect(web.metrics[0]).not.toHaveProperty('provisional')
  })

  it('passes status, missPct, sampleSize, area and cohort basis through; omits NO_TARGET and EVENT', async () => {
    const o = overview()
    o.funnel.push({ key: 'product_first_open', label: 'First opens', value: 70, source: 'PRODUCT', path: 'PRODUCT', available: true, basis: 'COHORT' } as never)
    ;(o as Record<string, unknown>)['funnelBasis'] = 'COHORT'
    const m = (key: string, extra: Record<string, unknown>) => ({
      metric: key, label: key, unit: 'COUNT', value: 10, previous: 9, changePct: 0.1, noise: false, series: [], ...extra,
    })
    o.channels.push({
      channel: 'PRODUCT', label: 'In-app', status: 'LIVE', dataCompleteThrough: '2026-10-13', connections: [],
      metrics: [
        m('retention_d7', {
          unit: 'PERCENT', value: 0.21, status: 'WATCH', missPct: 0.16, sampleSize: 240, area: 'RETENTION', basis: 'EVENT',
          target: { value: 0.25, direction: 'UP', window: '28d' },
        }),
        m('first_open', { funnelOrder: 1, basis: 'COHORT', area: 'ACQUISITION', status: 'NO_TARGET', missPct: null }),
      ],
    } as never)
    mocked(apiGet).mockResolvedValue(o)
    const result = await getMarketingInsights(config, { include: ['channels'], window: '7d' })
    const marketing = result['marketing'] as Record<string, any>
    const product = marketing.channels[2]
    expect(marketing.funnelBasis).toBe('COHORT')
    expect(marketing.funnel.find((s: any) => s.key === 'product_first_open')).toMatchObject({ basis: 'COHORT' })
    expect(marketing.funnel[0]).not.toHaveProperty('basis')
    expect(product.metrics[0]).toMatchObject({
      status: 'WATCH', missPct: 0.16, sampleSize: 240, area: 'RETENTION', target: { value: 0.25, window: '28d' },
    })
    expect(product.metrics[0]).not.toHaveProperty('basis')
    expect(product.stages[0]).toMatchObject({ basis: 'COHORT', area: 'ACQUISITION' })
    expect(product.stages[0]).not.toHaveProperty('status')
    expect(product.stages[0]).not.toHaveProperty('missPct')
  })

  it('passes description, headline and the target watchBandPct through', async () => {
    const o = overview()
    o.channels.push({
      channel: 'PRODUCT', label: 'In-app', status: 'LIVE', dataCompleteThrough: '2026-10-13', connections: [],
      metrics: [{
        metric: 'retention_d7', label: 'D7 retention', unit: 'PERCENT', value: 0.21, noise: false, series: [],
        description: 'Share of first-openers back on day 7', headline: 1,
        target: { value: 0.25, direction: 'UP', window: '28d', effectiveValue: 0.25, watchBandPct: 15 },
      }],
    } as never)
    mocked(apiGet).mockResolvedValue(o)
    const result = await getMarketingInsights(config, { include: ['channels'], window: '7d' })
    const metric = ((result['marketing'] as Record<string, any>).channels[2]).metrics[0]
    expect(metric).toMatchObject({
      description: 'Share of first-openers back on day 7', headline: 1,
      target: { value: 0.25, window: '28d', effectiveValue: 0.25, watchBandPct: 15 },
    })
  })

  it('PRODUCT not connected points at the connector to add', async () => {
    const o = overview()
    o.channels.push({
      channel: 'PRODUCT', label: 'In-app', status: 'NOT_CONNECTED', connections: [],
      connectHint: { connectors: [{ connectorId: 'product-metrics', name: 'Product metrics' }] }, metrics: [],
    } as never)
    mocked(apiGet).mockResolvedValue(o)
    const result = await getMarketingInsights(config, { include: ['channels'], window: '7d' })
    expect((result['marketing'] as Record<string, any>).channels[2]).toEqual({
      channel: 'PRODUCT', label: 'In-app', status: 'NOT_CONNECTED', connect: 'Product metrics',
    })
  })

  it('MISMATCH: keeps the metrics and adds both values and the gap', async () => {
    const o = overview()
    o.channels.push({
      channel: 'STORE', label: 'App Store', status: 'MISMATCH', connections: [], metrics: [metric()],
      mismatch: { metric: 'downloads', label: 'Downloads', referenceValue: 1190, channelValue: 1204, gapPct: 0.01163, from: '2026-09-01', to: '2026-09-12' },
    } as never)
    mocked(apiGet).mockResolvedValue(o)
    const result = await getMarketingInsights(config, { include: ['channels'], window: '7d' })
    const store = (result['marketing'] as Record<string, any>).channels[2]
    expect(store.status).toBe('MISMATCH')
    expect(store.mismatch).toEqual({ metric: 'downloads', conductor: 1204, reference: 1190, gapPct: 0.0116, from: '2026-09-01', to: '2026-09-12' })
    expect(store.metrics).toHaveLength(1)
  })

  it('accepts PRODUCT as the channel to drill into', async () => {
    mocked(apiGet).mockImplementation(async (path: string) => (path.includes('/channels/') ? detail() : overview()))
    await getMarketingInsights(config, { include: ['channels'], channel: 'PRODUCT' })
    expect(mocked(apiGet).mock.calls.map((c) => c[0])).toContain('/api/v2/projects/proj-1/marketing/channels/PRODUCT?window=28d')
  })

  it('maps 30d to the 28d overview window', async () => {
    mocked(apiGet).mockResolvedValue(overview())
    await getMarketingInsights(config, { include: ['channels'], window: '30d' })
    expect(mocked(apiGet).mock.calls[0]![0]).toContain('overview?window=28d')
    await getMarketingInsights(config, { include: ['channels'] })
    expect(mocked(apiGet).mock.calls[1]![0]).toContain('overview?window=28d')
  })

  it('both: posts keys plus a marketing section', async () => {
    mocked(apiGet).mockImplementation(async (path: string) => (path.includes('/overview') ? overview() : fixture()))
    const result = await getMarketingInsights(config, { include: ['posts', 'channels'] })
    expect(result).toHaveProperty('totals')
    expect(result).toHaveProperty('marketing')
  })

  it('channel detail: series downsampled to 14 points, dimension rows capped at 10', async () => {
    mocked(apiGet).mockImplementation(async (path: string) => (path.includes('/channels/') ? detail() : overview()))
    const result = await getMarketingInsights(config, { include: ['channels'], channel: 'WEB', window: '30d' })
    const calls = mocked(apiGet).mock.calls.map((c) => c[0])
    expect(calls).toContain('/api/v2/projects/proj-1/marketing/channels/WEB?window=28d')
    const d = (result['marketing'] as Record<string, any>).channelDetail
    const series = d.metrics[0].series
    expect(series).toHaveLength(13)
    expect(series[0]).toEqual({ from: 'd0', to: 'd5', value: 15, provisional: false })
    expect(series[1]).toEqual({ from: 'd6', to: 'd12', value: 63, provisional: false })
    expect(series[12]).toEqual({ from: 'd83', to: 'd89', value: 602, provisional: true })
    expect(d.dimensions[0].rows).toHaveLength(10)
    expect(d.dimensions[0].totalRows).toBe(15)
    expect(d.dimensions[0].columns).toEqual(['visits'])
  })

  it('90d with a channel reads only the detail endpoint', async () => {
    mocked(apiGet).mockResolvedValue(detail())
    const result = await getMarketingInsights(config, { include: ['channels'], channel: 'WEB', window: '90d' })
    expect(mocked(apiGet)).toHaveBeenCalledTimes(1)
    expect(mocked(apiGet).mock.calls[0]![0]).toBe('/api/v2/projects/proj-1/marketing/channels/WEB?window=90d')
    expect(result['marketing']).toHaveProperty('channelDetail')
  })

  it('rejects invalid combinations with a plain message and no request', async () => {
    await expect(getMarketingInsights(config, { include: ['channels'], window: '90d' })).rejects.toThrow(/90d is only available for one channel/)
    await expect(getMarketingInsights(config, { channel: 'WEB' })).rejects.toThrow(/needs include/)
    await expect(getMarketingInsights(config, { include: ['channels'], channel: 'TV' })).rejects.toThrow(/Unknown channel/)
    await expect(getMarketingInsights(config, { include: ['channels'], platform: 'instagram' })).rejects.toThrow(/only filters posts/)
    expect(mocked(apiGet)).not.toHaveBeenCalled()
  })
})

describe('bucketSeries', () => {
  const pts = (vals: (number | null)[]) => vals.map((v, i) => ({ day: `d${i}`, value: v, provisional: i === vals.length - 1 }))
  it('means for POSITION, skips null days, and drops empty leading buckets', () => {
    const vals = [...Array(10).fill(null), 2, 4, null, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28]
    const out = bucketSeries(pts(vals), 'POSITION')
    expect(out[0]).toEqual({ from: 'd4', to: 'd10', value: 2, provisional: false })
    expect(out).toHaveLength(3)
    expect(out[2]!['provisional']).toBe(true)
  })
})
