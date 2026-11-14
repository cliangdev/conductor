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
      expect(Object.keys(t.properties!)).toEqual(['hookType', 'visualHook', 'soundHook', 'angle', 'format', 'archetype', 'hypothesis', 'lessons', 'previewRounds'])
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
    expect(TOOLS.find((t) => t.name === 'get_marketing_insights')!.description).toContain('Add "tags" to include for byTag')
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
    expect(description).toContain('byTag (90-day settled posts vs this account')
    expect(description).toContain('byTagWindow and playbookEffect')
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
    expect(TOOLS.find((t) => t.name === 'get_creative')!.description).toContain('settled-post lift and a server verdict')
  })
})

describe('Verdicts, evidence window and playbook effect', () => {
  it('get_marketing_insights passes the verdict fields, byTagWindow and playbookEffect through', async () => {
    mocked(apiGet).mockResolvedValue({
      window: { from: 'a', to: 'b', days: 7 },
      totals: { key: 'all', posts: 0, views: 0, likes: 0, comments: 0, shares: 0, saves: 0 },
      byPlatform: [], byFormat: [], byHour: [], byWeekday: [], topPosts: [], bottomPosts: [], movers: [],
      coverage: { platforms: [], notes: [] }, creatives: [],
      byTagWindow: { from: 'x', to: 'y', days: 90 },
      playbookEffect: { withLessons: { posts: 4, medianViewLift: 1.31234 }, withoutLessons: { posts: 9, medianViewLift: null } },
      byTag: [{ key: '#3', tagKey: 'lessons', posts: 4, views: 400, likes: 0, comments: 0, shares: 0, saves: 0, unsettledPosts: 1,
        medianViewLift: 1.3, beatBaselineShare: 0.75, withBaselinePosts: 4, beatBaselinePosts: 3, withRetentionBaselinePosts: 0,
        beatRetentionBaselinePosts: 0, viewVerdict: 'WORKS', retentionVerdict: 'NOT_ENOUGH_DATA', verdict: 'WORKS', verdictMetric: 'VIEW_LIFT' }],
    })
    const result = (await getMarketingInsights({ window: '7d' }, config)) as Record<string, unknown>
    expect(result['byTagWindow']).toEqual({ from: 'x', to: 'y', days: 90 })
    expect(result['playbookEffect']).toEqual({ withLessons: { posts: 4, medianViewLift: 1.3123 }, withoutLessons: { posts: 9 } })
    expect((result['byTag'] as Array<Record<string, unknown>>)[0]).toMatchObject({
      tagKey: 'lessons', key: '#3', withBaselinePosts: 4, beatBaselinePosts: 3, withRetentionBaselinePosts: 0,
      beatRetentionBaselinePosts: 0, viewVerdict: 'WORKS', retentionVerdict: 'NOT_ENOUGH_DATA', verdict: 'WORKS', verdictMetric: 'VIEW_LIFT',
    })
    const description = TOOLS.find((t) => t.name === 'get_marketing_insights')!.description
    expect(description).toContain('server verdict')
    expect(description).toContain('playbookEffect')
    expect(description.length).toBeLessThan(5000)
  })

  it('get_creative keeps the per-variant verdict fields', async () => {
    mocked(apiGet).mockImplementation(async (url: string) => {
      if (url.endsWith('/readiness')) return { ready: true, items: [] }
      if (url.endsWith('/performance')) return { family: [{ creativeId: 'c-1', label: '1a', posts: 3, views: 10, withBaselinePosts: 3, beatBaselinePosts: 2, viewVerdict: 'WORKS', retentionVerdict: 'NOT_ENOUGH_DATA' }] }
      if (url.includes('/experiments')) return []
      if (url.endsWith('/c-1')) return { id: 'c-1', number: 1 }
      return [{ id: 'c-1', number: 1 }]
    })
    const result = await getCreative({ creativeId: 'c-1' }, config)
    expect((result['performance'] as Array<Record<string, unknown>>)[0]).toMatchObject({ withBaselinePosts: 3, beatBaselinePosts: 2, viewVerdict: 'WORKS', retentionVerdict: 'NOT_ENOUGH_DATA' })
  })

  it('passes the hook fields through byTag and get_creative, and the tags schema carries visualHook and soundHook', async () => {
    mocked(apiGet).mockResolvedValue({
      window: { from: 'a', to: 'b', days: 7 },
      totals: { key: 'all', posts: 0, views: 0, likes: 0, comments: 0, shares: 0, saves: 0 },
      byPlatform: [], byFormat: [], byHour: [], byWeekday: [], topPosts: [], bottomPosts: [], movers: [],
      coverage: { platforms: [], notes: [] }, creatives: [],
      byTag: [{ key: 'result-first', tagKey: 'visualHook', posts: 4, views: 400, likes: 0, comments: 0, shares: 0, saves: 0,
        medianHookLift: 1.31234, medianHoldLift: null, withHookBaselinePosts: 4, beatHookBaselinePosts: 3,
        hookVerdict: 'WORKS', verdict: 'WORKS', verdictMetric: 'HOOK_LIFT' }],
    })
    const insights = (await getMarketingInsights({ window: '7d', tags: true }, config)) as Record<string, unknown>
    const row = (insights['byTag'] as Array<Record<string, unknown>>)[0]!
    expect(row).toMatchObject({ tagKey: 'visualHook', medianHookLift: 1.3123, hookVerdict: 'WORKS', verdictMetric: 'HOOK_LIFT', withHookBaselinePosts: 4 })
    expect(row).not.toHaveProperty('medianHoldLift')

    mocked(apiGet).mockImplementation(async (url: string) => {
      if (url.endsWith('/readiness')) return { ready: true, items: [] }
      if (url.endsWith('/performance')) return { family: [{ creativeId: 'c-1', label: '1a', posts: 3, views: 10, hookRate: 0.61234, holdRate: null, medianHookLift: 1.2, hookVerdict: 'NO_DIFFERENCE' }] }
      if (url.includes('/experiments')) return []
      if (url.endsWith('/c-1')) return { id: 'c-1', number: 1 }
      return [{ id: 'c-1', number: 1 }]
    })
    const creative = await getCreative({ creativeId: 'c-1' }, config)
    expect((creative['performance'] as Array<Record<string, unknown>>)[0]).toMatchObject({ hookRate: 0.6123, medianHookLift: 1.2, hookVerdict: 'NO_DIFFERENCE' })

    const tags = (TOOLS.find((t) => t.name === 'create_creative')!.inputSchema.properties as Record<string, any>)['tags']
    expect(Object.keys(tags.properties)).toEqual(expect.arrayContaining(['visualHook', 'soundHook']))
    expect(TOOLS.find((t) => t.name === 'list_top_posts')!.inputSchema.properties!['metric']!.description).toContain('hook_rate')
    expect(TOOLS.find((t) => t.name === 'get_post_analytics')!.description).toContain('TikTok reports neither')
  })

  it('create_creative sends lessons and previewRounds', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'c-1' })
    await createCreative({ headline: 'Hi *there*', tags: { lessons: '#3,#12', previewRounds: '2' } }, config)
    expect(mocked(apiPost).mock.calls[0]![1]).toMatchObject({ tags: { lessons: '#3,#12', previewRounds: '2' } })
  })
})

describe('Insights filters', () => {
  it('get_marketing_insights offers brandKitId and sends it with platform to the API', async () => {
    const props = (TOOLS.find((t) => t.name === 'get_marketing_insights')!.inputSchema as { properties: Record<string, { type?: string }> }).properties
    expect(props['brandKitId']!.type).toBe('string')
    mocked(apiGet).mockResolvedValue({
      window: { from: 'a', to: 'b', days: 30 },
      totals: { key: 'all', posts: 0, views: 0, likes: 0, comments: 0, shares: 0, saves: 0 },
      byPlatform: [], byFormat: [], byHour: [], byWeekday: [], byTag: [], topPosts: [], bottomPosts: [], movers: [],
      coverage: { platforms: [], notes: [] }, creatives: [],
    })
    await getMarketingInsights(config, { platform: 'tiktok', brandKitId: 'kit-1' })
    const url = String(mocked(apiGet).mock.calls[0]![0])
    expect(url).toContain('platform=tiktok')
    expect(url).toContain('brandKitId=kit-1')
  })
})

describe('Opt-in tag data', () => {
  const base = {
    window: { from: 'a', to: 'b', days: 30 },
    totals: { key: 'all', posts: 0, views: 0, likes: 0, comments: 0, shares: 0, saves: 0 },
    byPlatform: [], byFormat: [], byHour: [], byWeekday: [], topPosts: [], bottomPosts: [], movers: [],
    coverage: { platforms: [], notes: [] }, creatives: [],
  }

  it('offers include "tags", sends it only when asked, and adds no tag keys when the API sent none', async () => {
    const include = (TOOLS.find((t) => t.name === 'get_marketing_insights')!.inputSchema as { properties: Record<string, { items: { enum: string[] } }> }).properties['include']!
    expect(include.items.enum).toContain('tags')

    mocked(apiGet).mockResolvedValue(base)
    const plain = await getMarketingInsights(config, {})
    expect(String(mocked(apiGet).mock.calls[0]![0])).not.toContain('include=')
    expect(plain).not.toHaveProperty('byTag')
    expect(plain).not.toHaveProperty('byTagWindow')
    expect(plain).not.toHaveProperty('playbookEffect')

    mocked(apiGet).mockClear()
    mocked(apiGet).mockResolvedValue({ ...base, byTag: [], byTagWindow: { from: 'x', to: 'y', days: 90 }, playbookEffect: { withLessons: { posts: 0 }, withoutLessons: { posts: 0 } } })
    const tagged = await getMarketingInsights(config, { include: ['tags'] })
    expect(String(mocked(apiGet).mock.calls[0]![0])).toContain('include=tags')
    expect(tagged).toHaveProperty('byTag')
    expect(tagged).toHaveProperty('playbookEffect')
  })
})
