import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet, apiPost, apiPut, apiDelete } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import {
  getGrowthSignals,
  setMarketingMoment,
  linkMomentChange,
  addCompetitor,
  removeCompetitor,
  setTrackedKeywords,
  MAX_REVIEW_BODY,
} from '../mcp/tools/growth-signals.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const base = '/api/v2/projects/proj-1/marketing'

beforeEach(() => vi.clearAllMocks())

describe('getGrowthSignals', () => {
  const payload = {
    moments: [
      {
        key: 'thanksgiving-2026',
        name: 'Thanksgiving',
        date: '2026-11-26',
        leadDays: 21,
        status: 'warning',
        surfaces: [
          {
            id: 's1',
            surface: 'App Store promotional text',
            dueOn: '2026-11-12',
            warning: 'App Store promotional text for Thanksgiving was due 2026-11-12 and no change is recorded',
          },
          { id: 's2', surface: 'Email', dueOn: '2026-11-18', changeWorkItemId: 'wi-1', changeDisplayId: 'RE-7' },
        ],
      },
    ],
    ratings: {
      ours: {
        appId: '111',
        name: 'Rexipe',
        country: 'us',
        latest: { capturedOn: '2026-11-02', ratingAvg: 4.6042, ratingCount: 135, price: 0, currency: 'USD' },
        previous: { capturedOn: '2026-10-26', ratingCount: 120 },
        weeklyGrowth: 15,
      },
      competitors: [
        {
          appId: '222',
          name: 'Rival',
          country: 'us',
          latest: { capturedOn: '2026-11-02', ratingCount: 900, subtitle: null },
          previous: null,
          weeklyGrowth: null,
        },
      ],
    },
    reviews: {
      ours: [
        {
          rating: 5,
          title: 'Love it',
          body: 'x'.repeat(500),
          author: 'cookfan',
          reviewedAt: '2026-11-02T04:20:35-07:00',
        },
      ],
      competitors: [{ appId: '222', name: 'Rival', reviews: [{ rating: 2, title: 'Meh', body: 'Slow', reviewedAt: null }] }],
    },
    competitorChanges: [{ appId: '222', name: 'Rival', changes: ['price 0.99 -> 1.99'] }],
    keywordPresence: [{ keyword: 'recipe app', ours: 3, competitors: { '222': 1 } }],
    trackedKeywords: { source: 'PROJECT_LIST', country: 'us', keywords: ['recipe app'] },
  }

  it('reads the growth signals endpoint', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    await getGrowthSignals(config)
    expect(apiGet).toHaveBeenCalledWith(`${base}/growth-signals`, config)
  })

  it('keeps moments, warnings and surface ids so a change can be linked', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getGrowthSignals(config)) as Record<string, any>
    expect(out['moments'][0].status).toBe('warning')
    expect(out['moments'][0].surfaces[0].id).toBe('s1')
    expect(out['moments'][0].surfaces[0].warning).toMatch(/was due 2026-11-12 and no change is recorded/)
    expect(out['moments'][0].surfaces[1]).toEqual({
      id: 's2',
      surface: 'Email',
      dueOn: '2026-11-18',
      changeWorkItemId: 'wi-1',
      changeDisplayId: 'RE-7',
    })
  })

  it('keeps weeklyGrowth as an explicit null until two snapshots exist, never 0', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getGrowthSignals(config)) as Record<string, any>
    expect(out['ratings'].ours.weeklyGrowth).toBe(15)
    expect(out['ratings'].competitors[0]).toHaveProperty('weeklyGrowth', null)
    expect(out['ratings'].competitors[0].previous).toBeUndefined()
  })

  it('drops nulls and rounds numbers', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getGrowthSignals(config)) as Record<string, any>
    expect(out['ratings'].ours.latest.ratingAvg).toBe(4.6042)
    expect(out['ratings'].competitors[0].latest).toEqual({ capturedOn: '2026-11-02', ratingCount: 900 })
  })

  it('shortens review text and shows the day, not the time', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getGrowthSignals(config)) as Record<string, any>
    const review = out['reviews'].ours[0]
    expect(review.body.length).toBe(MAX_REVIEW_BODY + 3)
    expect(review.body.endsWith('...')).toBe(true)
    expect(review.date).toBe('2026-11-02')
    expect(review).not.toHaveProperty('reviewedAt')
    expect(out['reviews'].competitors[0]).toEqual({
      appId: '222',
      name: 'Rival',
      reviews: [{ rating: 2, title: 'Meh', body: 'Slow' }],
    })
  })

  it('passes competitor changes, keyword presence and tracked keywords through', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getGrowthSignals(config)) as Record<string, any>
    expect(out['competitorChanges']).toEqual([{ appId: '222', name: 'Rival', changes: ['price 0.99 -> 1.99'] }])
    expect(out['keywordPresence']).toEqual([{ keyword: 'recipe app', ours: 3, competitors: { '222': 1 } }])
    expect(out['trackedKeywords']).toEqual({ source: 'PROJECT_LIST', country: 'us', keywords: ['recipe app'] })
  })

  it('reads an empty workspace as empty lists, and no own app as an absent ours', async () => {
    mocked(apiGet).mockResolvedValue({
      moments: [],
      ratings: { competitors: [] },
      reviews: { ours: [], competitors: [] },
      competitorChanges: [],
      keywordPresence: [],
      trackedKeywords: { source: 'PROJECT_LIST', country: 'us', keywords: [] },
    })
    const out = (await getGrowthSignals(config)) as Record<string, any>
    expect(out['moments']).toEqual([])
    expect(out['ratings']).toEqual({ competitors: [] })
    expect(out['reviews']).toEqual({ ours: [], competitors: [] })
    expect(out['competitorChanges']).toEqual([])
    expect(out['keywordPresence']).toEqual([])
  })

  it('tolerates a response with missing sections', async () => {
    mocked(apiGet).mockResolvedValue({})
    const out = (await getGrowthSignals(config)) as Record<string, any>
    expect(out['moments']).toEqual([])
    expect(out['ratings']).toEqual({ competitors: [] })
    expect(out['reviews']).toEqual({ ours: [], competitors: [] })
  })
})

describe('setMarketingMoment', () => {
  const ok = {
    key: 'thanksgiving-2026',
    name: 'Thanksgiving',
    date: '2026-11-26',
    surfaces: [{ surface: 'App Store promotional text', dueOn: '2026-11-12' }],
  }

  it('PUTs the moment to its key and returns the trimmed result with surface ids', async () => {
    mocked(apiPut).mockResolvedValue({
      key: 'thanksgiving-2026',
      name: 'Thanksgiving',
      date: '2026-11-26',
      leadDays: 21,
      status: 'upcoming',
      surfaces: [{ id: 's1', surface: 'App Store promotional text', dueOn: '2026-11-12', warning: null }],
    })
    const out = await setMarketingMoment(config, ok)
    const [path, body] = mocked(apiPut).mock.calls[0]!
    expect(path).toBe(`${base}/moments/thanksgiving-2026`)
    expect(body).toMatchObject({ name: 'Thanksgiving', date: '2026-11-26', surfaces: ok.surfaces })
    expect(JSON.parse(JSON.stringify(body))).not.toHaveProperty('leadDays')
    expect(out).toEqual({
      key: 'thanksgiving-2026',
      name: 'Thanksgiving',
      date: '2026-11-26',
      leadDays: 21,
      status: 'upcoming',
      surfaces: [{ id: 's1', surface: 'App Store promotional text', dueOn: '2026-11-12' }],
    })
  })

  it('sends leadDays when given and normalises the key', async () => {
    mocked(apiPut).mockResolvedValue({})
    await setMarketingMoment(config, { ...ok, key: ' Thanksgiving-2026 ', leadDays: 30 })
    const [path, body] = mocked(apiPut).mock.calls[0]!
    expect(path).toBe(`${base}/moments/thanksgiving-2026`)
    expect(body).toMatchObject({ leadDays: 30 })
  })

  it('allows an empty surface list', async () => {
    mocked(apiPut).mockResolvedValue({})
    await setMarketingMoment(config, { ...ok, surfaces: [] })
    expect(mocked(apiPut).mock.calls[0]![1]).toMatchObject({ surfaces: [] })
  })

  it('refuses bad input without calling the API', async () => {
    await expect(setMarketingMoment(config, { ...ok, key: 'Bad Key!' })).rejects.toThrow(/key/)
    await expect(setMarketingMoment(config, { ...ok, name: ' ' })).rejects.toThrow(/name/)
    await expect(setMarketingMoment(config, { ...ok, date: '26 Nov 2026' })).rejects.toThrow(/date/)
    await expect(setMarketingMoment(config, { ...ok, leadDays: -1 })).rejects.toThrow(/leadDays/)
    await expect(setMarketingMoment(config, { ...ok, leadDays: 1.5 })).rejects.toThrow(/leadDays/)
    await expect(
      setMarketingMoment(config, { ...ok, surfaces: [{ surface: 'Email', dueOn: 'soon' }] })
    ).rejects.toThrow(/surfaces\[0\]\.dueOn/)
    await expect(
      setMarketingMoment(config, { ...ok, surfaces: [{ surface: ' ', dueOn: '2026-11-12' }] })
    ).rejects.toThrow(/surfaces\[0\]\.surface/)
    await expect(setMarketingMoment(config, { ...ok, surfaces: undefined as never })).rejects.toThrow(/surfaces/)
    expect(apiPut).not.toHaveBeenCalled()
  })
})

describe('linkMomentChange', () => {
  it('POSTs the work item to the surface and returns the trimmed moment', async () => {
    mocked(apiPost).mockResolvedValue({
      key: 'thanksgiving-2026',
      status: 'scheduled',
      surfaces: [{ id: 's1', surface: 'Email', dueOn: '2026-11-12', changeWorkItemId: 'wi-1', changeDisplayId: 'RE-7' }],
    })
    const out = await linkMomentChange(config, { key: 'thanksgiving-2026', surfaceId: 's1', workItemId: 'RE-7' })
    expect(apiPost).toHaveBeenCalledWith(
      `${base}/moments/thanksgiving-2026/surfaces/s1/change`,
      { workItemId: 'RE-7' },
      config
    )
    expect((out['surfaces'] as Record<string, unknown>[])[0]).toMatchObject({ changeDisplayId: 'RE-7' })
  })

  it('refuses missing ids without calling the API', async () => {
    await expect(linkMomentChange(config, { key: 'k', surfaceId: '', workItemId: 'RE-7' })).rejects.toThrow(/surfaceId/)
    await expect(linkMomentChange(config, { key: 'k', surfaceId: 's1', workItemId: ' ' })).rejects.toThrow(/workItemId/)
    await expect(linkMomentChange(config, { key: '', surfaceId: 's1', workItemId: 'RE-7' })).rejects.toThrow(/key/)
    expect(apiPost).not.toHaveBeenCalled()
  })
})

describe('addCompetitor and removeCompetitor', () => {
  it('POSTs the competitor and returns it', async () => {
    mocked(apiPost).mockResolvedValue({ appId: '1234567890', name: 'Rival', country: 'gb', active: true })
    const out = await addCompetitor(config, { appId: '1234567890', name: ' Rival ', country: 'GB' })
    expect(apiPost).toHaveBeenCalledWith(
      `${base}/competitors`,
      { appId: '1234567890', name: 'Rival', country: 'gb' },
      config
    )
    expect(out).toEqual({ appId: '1234567890', name: 'Rival', country: 'gb', active: true })
  })

  it('sends no country when none is given, so the workspace default applies', async () => {
    mocked(apiPost).mockResolvedValue({})
    await addCompetitor(config, { appId: '1234567890', name: 'Rival' })
    const [, body] = mocked(apiPost).mock.calls[0]!
    expect(JSON.parse(JSON.stringify(body))).toEqual({ appId: '1234567890', name: 'Rival' })
  })

  it('refuses an app id that is not digits and a bad country, never calling the API', async () => {
    await expect(addCompetitor(config, { appId: '12ab', name: 'Rival' })).rejects.toThrow(/appId/)
    await expect(addCompetitor(config, { appId: 'https://evil.example/1', name: 'Rival' })).rejects.toThrow(/appId/)
    await expect(addCompetitor(config, { appId: '123', name: ' ' })).rejects.toThrow(/name/)
    await expect(addCompetitor(config, { appId: '123', name: 'R', country: 'usa' })).rejects.toThrow(/country/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('DELETEs by app id', async () => {
    mocked(apiDelete).mockResolvedValue(undefined)
    expect(await removeCompetitor(config, { appId: '1234567890' })).toEqual({ removed: '1234567890' })
    expect(apiDelete).toHaveBeenCalledWith(`${base}/competitors?appId=1234567890`, config)
  })

  it('refuses to remove a non-numeric app id', async () => {
    await expect(removeCompetitor(config, { appId: '1&appId=2' })).rejects.toThrow(/appId/)
    expect(apiDelete).not.toHaveBeenCalled()
  })
})

describe('setTrackedKeywords', () => {
  it('PUTs the whole list and returns what is stored', async () => {
    mocked(apiPut).mockResolvedValue({ source: 'PROJECT_LIST', country: 'us', keywords: ['recipe app', 'meal planner'] })
    const out = await setTrackedKeywords(config, { keywords: ['Recipe App', 'meal planner'], country: 'US' })
    expect(apiPut).toHaveBeenCalledWith(
      `${base}/tracked-keywords`,
      { keywords: ['Recipe App', 'meal planner'], country: 'us' },
      config
    )
    expect(out).toEqual({ source: 'PROJECT_LIST', country: 'us', keywords: ['recipe app', 'meal planner'] })
  })

  it('an empty list is sent as is, to clear the list', async () => {
    mocked(apiPut).mockResolvedValue({ source: 'PROJECT_LIST', country: 'us', keywords: [] })
    await setTrackedKeywords(config, { keywords: [] })
    expect(mocked(apiPut).mock.calls[0]![1]).toMatchObject({ keywords: [] })
  })

  it('refuses more than 25 keywords, non-strings and a bad country', async () => {
    await expect(setTrackedKeywords(config, { keywords: Array.from({ length: 26 }, (_, i) => `k${i}`) })).rejects.toThrow(/25/)
    await expect(setTrackedKeywords(config, { keywords: [1 as never] })).rejects.toThrow(/keywords/)
    await expect(setTrackedKeywords(config, { keywords: undefined as never })).rejects.toThrow(/keywords/)
    await expect(setTrackedKeywords(config, { keywords: ['a'], country: 'usa' })).rejects.toThrow(/country/)
    expect(apiPut).not.toHaveBeenCalled()
  })
})

describe('tool registration', () => {
  const names = ['get_growth_signals', 'set_marketing_moment', 'link_moment_change', 'add_competitor', 'remove_competitor', 'set_tracked_keywords']
  const tool = (name: string) => TOOLS.find((t) => t.name === name)!

  it('registers all six tools once', () => {
    for (const n of names) expect(TOOLS.filter((t) => t.name === n)).toHaveLength(1)
  })

  it('declares the required params of each tool', () => {
    const required = (n: string) => (tool(n).inputSchema as { required?: string[] }).required ?? []
    expect(required('get_growth_signals')).toEqual([])
    expect(required('set_marketing_moment')).toEqual(['key', 'name', 'date', 'surfaces'])
    expect(required('link_moment_change')).toEqual(['key', 'surfaceId', 'workItemId'])
    expect(required('add_competitor')).toEqual(['appId', 'name'])
    expect(required('remove_competitor')).toEqual(['appId'])
    expect(required('set_tracked_keywords')).toEqual(['keywords'])
  })

  it('every documented parameter is one the tool function reads (no false promises)', () => {
    const props = (n: string) => Object.keys((tool(n).inputSchema as { properties: object }).properties)
    expect(props('get_growth_signals')).toEqual([])
    expect(props('set_marketing_moment')).toEqual(['key', 'name', 'date', 'leadDays', 'surfaces'])
    expect(props('add_competitor')).toEqual(['appId', 'name', 'country'])
    expect(props('set_tracked_keywords')).toEqual(['keywords', 'country'])
  })

  it('each mutating tool names get_growth_signals as its read-back companion', () => {
    for (const n of names.filter((x) => x !== 'get_growth_signals')) {
      expect(tool(n).description, n).toMatch(/get_growth_signals/)
    }
  })

  it('keeps descriptions lean', () => {
    for (const n of names) expect(tool(n).description.length, n).toBeLessThan(700)
  })
})
