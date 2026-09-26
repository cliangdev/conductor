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
