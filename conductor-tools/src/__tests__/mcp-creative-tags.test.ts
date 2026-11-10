import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  isClientError: () => false,
  ApiError: class ApiError extends Error {
    status = 0
    violations?: unknown[]
  },
}))

import { apiGet, apiPost, apiPatch } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import { createCreative, updateCreative, getCreative } from '../mcp/tools/creatives.js'
import { commitCreativeDraft } from '../mcp/tools/creative-drafts.js'
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
const tags = { hookType: 'question', angle: 'utility-education', hypothesis: 'H3' }

function props(name: string): Record<string, { type?: string; properties?: Record<string, unknown> }> {
  const tool = TOOLS.find((t) => t.name === name)
  if (!tool) throw new Error(`no tool named ${name}`)
  return (tool.inputSchema as { properties: Record<string, { type?: string; properties?: Record<string, unknown> }> }).properties
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('Creative tags', () => {
  it('create_creative, update_creative, preview_creative_draft and commit_creative_draft accept the five tag keys', () => {
    for (const name of ['create_creative', 'update_creative', 'commit_creative_draft', 'preview_creative_draft']) {
      const t = props(name)['tags']!
      expect(t.type).toBe('object')
      expect(Object.keys(t.properties!)).toEqual(['hookType', 'angle', 'format', 'archetype', 'hypothesis'])
    }
  })

  it('create_creative sends tags in the request body, and on a variant too', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'c-1' })
    await createCreative({ headline: 'Hello *there*', tags }, config)
    expect(mocked(apiPost).mock.calls[0]![1]).toMatchObject({ tags })

    mocked(apiPost).mockClear()
    await createCreative({ variantOf: '12a', headline: 'Other', tags }, config)
    expect(mocked(apiPost).mock.calls[0]![0]).toContain('/12a/variants')
    expect(mocked(apiPost).mock.calls[0]![1]).toMatchObject({ tags })
  })

  it('update_creative sends tags, including {} to clear', async () => {
    mocked(apiPatch).mockResolvedValue({ id: 'c-1' })
    await updateCreative({ creativeId: 'c-1', version: 3, tags }, config)
    expect(mocked(apiPatch).mock.calls[0]![1]).toMatchObject({ tags, version: 3 })

    mocked(apiPatch).mockClear()
    await updateCreative({ creativeId: 'c-1', version: 3, tags: {} }, config)
    expect(mocked(apiPatch).mock.calls[0]![1]).toMatchObject({ tags: {} })
  })

  it('get_creative keeps tags in its output', async () => {
    mocked(apiGet).mockImplementation(async (url: string) => {
      if (url.endsWith('/readiness')) return { ready: true, items: [] }
      if (url.endsWith('/performance')) return { family: [] }
      if (url.includes('/experiments')) return []
      if (url.endsWith('/c-1')) return { id: 'c-1', number: 1, tags }
      return [{ id: 'c-1', number: 1, tags }]
    })
    const result = await getCreative({ creativeId: 'c-1' }, config)
    expect(result['tags']).toEqual(tags)
  })

  it('commit_creative_draft carries previewed tags into the create call, and its own tags replace them', async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'draft-tags-'))
    const draft = {
      version: 1,
      createdAt: new Date().toISOString(),
      committed: false,
      projectId: 'proj-1',
      full: false,
      request: { kind: 'STILL', headline: 'Hello *there*', tags },
      localFiles: {},
      localMedia: {},
      specSha256: 'x',
    }
    fs.writeFileSync(path.join(dir, 'draft.json'), JSON.stringify(draft))
    mocked(apiPost).mockImplementation(async (url: string) => {
      if (url.endsWith('/creatives')) return { id: 'c-9', kind: 'STILL', displayId: '9a' }
      return { ok: true, frames: [] }
    })
    mocked(apiGet).mockResolvedValue({ id: 'c-9', version: 1, readiness: {} })

    await commitCreativeDraft({ draftDir: dir }, config, { projectRoot: dir }).catch(() => {})
    const create = mocked(apiPost).mock.calls.find((c) => String(c[0]).endsWith('/creatives'))
    expect(create![1]).toMatchObject({ tags })

    // A fresh draft, committed with its own tags.
    const dir2 = fs.mkdtempSync(path.join(os.tmpdir(), 'draft-tags-'))
    fs.writeFileSync(path.join(dir2, 'draft.json'), JSON.stringify(draft))
    mocked(apiPost).mockClear()
    await commitCreativeDraft({ draftDir: dir2, tags: { hookType: 'stat' } }, config, { projectRoot: dir2 }).catch(() => {})
    const create2 = mocked(apiPost).mock.calls.find((c) => String(c[0]).endsWith('/creatives'))
    expect((create2![1] as { tags: unknown }).tags).toEqual({ hookType: 'stat' })
  })

  it('get_marketing_insights returns byTag and says so in its description', async () => {
    const group = { key: 'question', tagKey: 'hookType', posts: 3, views: 900, likes: 90, comments: 9, shares: 1, saves: 0, engagementRate: 0.11111, medianViews: 300, avgViewPct: null }
    mocked(apiGet).mockResolvedValue({
      window: { from: 'a', to: 'b', days: 30 },
      totals: { key: 'all', posts: 3, views: 900, likes: 90, comments: 9, shares: 1, saves: 0 },
      byPlatform: [], byFormat: [], byHour: [], byWeekday: [], byTag: [group],
      topPosts: [], bottomPosts: [], movers: [], coverage: { platforms: [], notes: [] }, creatives: [],
    })
    const result = (await getMarketingInsights({}, config)) as { byTag: Array<Record<string, unknown>> }
    expect(result.byTag).toEqual([{ key: 'question', tagKey: 'hookType', posts: 3, views: 900, likes: 90, comments: 9, shares: 1, saves: 0, engagementRate: 0.1111, medianViews: 300 }])
    expect(TOOLS.find((t) => t.name === 'get_marketing_insights')!.description).toContain(
      "byTag: the same groups by the posting Creative's tags (hookType, angle, format, archetype, hypothesis)"
    )
  })
})

describe('Tag lift fields', () => {
  it('get_marketing_insights passes the lift fields through byTag and says byTag counts settled posts only', async () => {
    mocked(apiGet).mockResolvedValue({
      window: { from: 'a', to: 'b', days: 30 },
      totals: { key: 'all', posts: 3, views: 900, likes: 0, comments: 0, shares: 0, saves: 0 },
      byPlatform: [], byFormat: [], byHour: [], byWeekday: [], topPosts: [], bottomPosts: [], movers: [],
      coverage: { platforms: [], notes: [] }, creatives: [],
      byTag: [{ key: 'question', tagKey: 'hookType', posts: 3, views: 900, likes: 0, comments: 0, shares: 0, saves: 0, engagementRate: null, unsettledPosts: 2, medianViewLift: 1.23456, beatBaselineShare: 0.66667, medianEngagementRate: 0.05, medianRetentionLift: null }],
    })
    const result = (await getMarketingInsights({}, config)) as { byTag: Array<Record<string, unknown>> }
    expect(result.byTag[0]).toMatchObject({ unsettledPosts: 2, medianViewLift: 1.2346, beatBaselineShare: 0.6667, medianEngagementRate: 0.05 })
    expect(result.byTag[0]).not.toHaveProperty('medianRetentionLift')
    const description = TOOLS.find((t) => t.name === 'get_marketing_insights')!.description
    expect(description).toContain('only settled posts (TikTok/Instagram/YouTube 72 h old, Facebook 7 d)')
    expect(description).toContain('1.0 is typical')
  })

  it('get_creative keeps the lift fields of each variant and documents them', async () => {
    mocked(apiGet).mockImplementation(async (url: string) => {
      if (url.endsWith('/readiness')) return { ready: true, items: [] }
      if (url.endsWith('/performance')) return { family: [{ creativeId: 'c-1', label: '1a', posts: 2, views: 10, settledPosts: 1, medianViewLift: 2.5, medianRetentionLift: 1.1, beatBaselineShare: 1 }] }
      if (url.includes('/experiments')) return []
      if (url.endsWith('/c-1')) return { id: 'c-1', number: 1 }
      return [{ id: 'c-1', number: 1 }]
    })
    const result = await getCreative({ creativeId: 'c-1' }, config)
    expect((result['performance'] as Array<Record<string, unknown>>)[0]).toMatchObject({ settledPosts: 1, medianViewLift: 2.5, medianRetentionLift: 1.1, beatBaselineShare: 1 })
    expect(TOOLS.find((t) => t.name === 'get_creative')!.description).toContain('medianViewLift')
  })
})
