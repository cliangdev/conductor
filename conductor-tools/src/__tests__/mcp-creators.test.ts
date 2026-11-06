import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
}))

import { apiGet } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import { getCreatorResults, MAX_CREATOR_POSTS } from '../mcp/tools/creators.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

const BASE = '/api/v2/projects/proj-1/marketing/creators'

function payload(overrides: Record<string, unknown> = {}) {
  return {
    window: { from: '2026-09-29', to: '2026-10-26' },
    creator: { workItemId: 'wi-1', displayId: 'RE-12', handle: 'jane', status: 'POSTED' },
    postCount: 1,
    posts: [{ workItemId: 'wi-2', displayId: 'RE-20', title: 'Jane unboxing', status: 'PUBLISHED', views: 150 }],
    linkDownloads: { tokens: ['jane-a', 'jane-b'], downloads: 13, belowThreshold: false, note: null },
    offerRedemptions: { code: 'JANE10', value: 6, note: null },
    ...overrides,
  }
}

beforeEach(() => vi.clearAllMocks())

describe('getCreatorResults', () => {
  it('reads the results of a creator by id with no window unless one is given', async () => {
    mocked(apiGet).mockResolvedValue(payload())

    await getCreatorResults(config, { creatorId: 'wi-1' })

    expect(apiGet).toHaveBeenCalledWith(`${BASE}/wi-1/results`, config)
  })

  it('accepts a display id and passes the window as from and to', async () => {
    mocked(apiGet).mockResolvedValue(payload())

    await getCreatorResults(config, { creatorId: ' RE-12 ', from: '2026-09-29', to: '2026-10-26' })

    expect(apiGet).toHaveBeenCalledWith(`${BASE}/RE-12/results?from=2026-09-29&to=2026-10-26`, config)
  })

  it('passes a lone from or a lone to', async () => {
    mocked(apiGet).mockResolvedValue(payload())

    await getCreatorResults(config, { creatorId: 'RE-12', from: '2026-09-29' })
    await getCreatorResults(config, { creatorId: 'RE-12', to: '2026-10-26' })

    expect(apiGet).toHaveBeenNthCalledWith(1, `${BASE}/RE-12/results?from=2026-09-29`, config)
    expect(apiGet).toHaveBeenNthCalledWith(2, `${BASE}/RE-12/results?to=2026-10-26`, config)
  })

  it('returns the creator, posts with views, link downloads and offer redemptions', async () => {
    mocked(apiGet).mockResolvedValue(payload())

    const out = await getCreatorResults(config, { creatorId: 'RE-12' })

    expect(out).toEqual({
      window: { from: '2026-09-29', to: '2026-10-26' },
      creator: { id: 'wi-1', displayId: 'RE-12', handle: 'jane', status: 'POSTED' },
      postCount: 1,
      posts: [{ id: 'wi-2', displayId: 'RE-20', title: 'Jane unboxing', status: 'PUBLISHED', views: 150 }],
      linkDownloads: { tokens: ['jane-a', 'jane-b'], downloads: 13, belowThreshold: false },
      offerRedemptions: { code: 'JANE10', value: 6 },
    })
  })

  it('keeps an unknown figure as an explicit null with its note, never 0', async () => {
    mocked(apiGet).mockResolvedValue(
      payload({
        posts: [{ workItemId: 'wi-2', displayId: 'RE-20', title: 'Draft', status: 'DRAFT', views: null }],
        linkDownloads: {
          tokens: ['jane-a'],
          downloads: null,
          belowThreshold: true,
          note: "Below Apple's reporting threshold: Apple sends no row for these links, which is not 0.",
        },
        offerRedemptions: {
          code: 'JANE10',
          value: null,
          note: 'Your product backend does not send offer_redemptions yet',
        },
      })
    )

    const out = (await getCreatorResults(config, { creatorId: 'RE-12' })) as Record<string, any>

    expect(out['posts'][0]).toHaveProperty('views', null)
    expect(out['linkDownloads']).toEqual({
      tokens: ['jane-a'],
      downloads: null,
      belowThreshold: true,
      note: "Below Apple's reporting threshold: Apple sends no row for these links, which is not 0.",
    })
    expect(out['offerRedemptions']).toEqual({
      code: 'JANE10',
      value: null,
      note: 'Your product backend does not send offer_redemptions yet',
    })
  })

  it('keeps a reported zero as 0', async () => {
    mocked(apiGet).mockResolvedValue(
      payload({
        posts: [{ workItemId: 'wi-2', displayId: 'RE-20', title: 'Flop', status: 'PUBLISHED', views: 0 }],
        offerRedemptions: { code: 'JANE10', value: 0, note: null },
      })
    )

    const out = (await getCreatorResults(config, { creatorId: 'RE-12' })) as Record<string, any>

    expect(out['posts'][0].views).toBe(0)
    expect(out['offerRedemptions'].value).toBe(0)
  })

  it('leaves out a handle or code that is not set instead of printing null', async () => {
    mocked(apiGet).mockResolvedValue(
      payload({
        creator: { workItemId: 'wi-1', displayId: 'RE-12', handle: null, status: 'PROSPECT' },
        offerRedemptions: { code: null, value: null, note: 'This creator has no offer code yet.' },
      })
    )

    const out = (await getCreatorResults(config, { creatorId: 'RE-12' })) as Record<string, any>

    expect(out['creator']).toEqual({ id: 'wi-1', displayId: 'RE-12', status: 'PROSPECT' })
    expect(out['offerRedemptions']).toEqual({ value: null, note: 'This creator has no offer code yet.' })
  })

  it('lists at most 20 posts but reports how many there are', async () => {
    const posts = Array.from({ length: 30 }, (_, i) => ({
      workItemId: `wi-${i}`,
      displayId: `RE-${i}`,
      title: `Post ${i}`,
      status: 'PUBLISHED',
      views: i,
    }))
    mocked(apiGet).mockResolvedValue(payload({ postCount: 31, posts }))

    const out = (await getCreatorResults(config, { creatorId: 'RE-12' })) as Record<string, any>

    expect(MAX_CREATOR_POSTS).toBe(20)
    expect(out['posts']).toHaveLength(20)
    expect(out['postCount']).toBe(31)
    expect(out['posts'][0].displayId).toBe('RE-0')
  })

  it('rejects a blank creator id before calling the API', async () => {
    await expect(getCreatorResults(config, { creatorId: '  ' })).rejects.toThrow(/creatorId/)
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('rejects a date that is not YYYY-MM-DD, or a reversed window, before calling the API', async () => {
    await expect(getCreatorResults(config, { creatorId: 'RE-12', from: '29/09/2026' })).rejects.toThrow(/from/)
    await expect(getCreatorResults(config, { creatorId: 'RE-12', to: '2026-13-40' })).rejects.toThrow(/to/)
    await expect(
      getCreatorResults(config, { creatorId: 'RE-12', from: '2026-10-26', to: '2026-09-29' })
    ).rejects.toThrow(/is after to/)
    expect(apiGet).not.toHaveBeenCalled()
  })
})

describe('get_creator_results tool schema', () => {
  it('is registered, read-only and needs only the creator id', () => {
    const tool = TOOLS.find((t) => t.name === 'get_creator_results')!
    const schema = tool.inputSchema as { properties: Record<string, { description: string }>; required: string[] }

    expect(tool).toBeDefined()
    expect(schema.required).toEqual(['creatorId'])
    expect(Object.keys(schema.properties)).toEqual(['creatorId', 'from', 'to'])
    expect(tool.description).toMatch(/Read-only/)
    expect(tool.description).toMatch(/null/)
  })
})
