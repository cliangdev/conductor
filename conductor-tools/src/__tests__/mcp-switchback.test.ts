import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet, apiPost } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import {
  alternatingWeeks,
  createSwitchbackTest,
  startSwitchbackTest,
  getSwitchbackTest,
  listSwitchbackTests,
} from '../mcp/tools/switchback.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const base = '/api/v2/projects/proj-1/marketing/switchback-tests'

beforeEach(() => vi.clearAllMocks())

const draft = {
  id: 't1',
  name: 'Brand term',
  keywordId: '555',
  keywordText: 'recipe app',
  status: 'DRAFT',
  weeks: [
    { weekStart: '2026-11-09', on: true },
    { weekStart: '2026-11-16', on: false },
  ],
  decisionRule: { metric: 'organic_search_first_time_downloads', minLift: 1, maxCannibalisationPct: 20, incrementalIf: 'Paid is incremental if ...' },
  minN: 20,
  currentWeek: { state: 'not running' },
}

describe('alternatingWeeks', () => {
  it('alternates from ON starting on the given Monday', () => {
    expect(alternatingWeeks('2026-11-09', 4)).toEqual([
      { weekStart: '2026-11-09', on: true },
      { weekStart: '2026-11-16', on: false },
      { weekStart: '2026-11-23', on: true },
      { weekStart: '2026-11-30', on: false },
    ])
  })

  it('refuses a start that is not a Monday, a bad date and a bad count', () => {
    expect(() => alternatingWeeks('2026-11-10', 4)).toThrow('not a Monday')
    expect(() => alternatingWeeks('next monday', 4)).toThrow('yyyy-MM-dd')
    expect(() => alternatingWeeks('2026-11-09', 1)).toThrow('2 to 12')
    expect(() => alternatingWeeks('2026-11-09', 13)).toThrow('2 to 12')
  })
})

describe('createSwitchbackTest', () => {
  it('posts the generated weeks and only the rule fields that were given', async () => {
    mocked(apiPost).mockResolvedValue(draft)

    const out = await createSwitchbackTest(config, {
      name: 'Brand term',
      keywordId: '555',
      keywordText: 'recipe app',
      startWeek: '2026-11-09',
      weekCount: 2,
      minN: 40,
      maxCannibalisationPct: 10,
    })

    expect(apiPost).toHaveBeenCalledWith(
      base,
      {
        name: 'Brand term',
        keywordId: '555',
        keywordText: 'recipe app',
        weeks: [
          { weekStart: '2026-11-09', on: true },
          { weekStart: '2026-11-16', on: false },
        ],
        minN: 40,
        decisionRule: { maxCannibalisationPct: 10 },
      },
      config
    )
    expect(out).toMatchObject({ id: 't1', status: 'DRAFT', thisWeek: 'not running', minN: 20 })
    expect(out['weeks']).toEqual(['2026-11-09:ON', '2026-11-16:OFF'])
  })

  it('sends explicit weeks as given and no rule when none was given', async () => {
    mocked(apiPost).mockResolvedValue(draft)
    const weeks = [
      { weekStart: '2026-11-09', on: false },
      { weekStart: '2026-11-16', on: true },
    ]

    await createSwitchbackTest(config, { name: 'n', keywordId: '1', keywordText: 'k', weeks })

    const body = mocked(apiPost).mock.calls[0]![1] as Record<string, unknown>
    expect(body['weeks']).toEqual(weeks)
    expect(body).not.toHaveProperty('decisionRule')
    expect(body).not.toHaveProperty('minN')
  })

  it('refuses weeks together with startWeek, neither, a half pair and an unknown week state before any call', async () => {
    const common = { name: 'n', keywordId: '1', keywordText: 'k' }
    await expect(
      createSwitchbackTest(config, { ...common, weeks: [{ weekStart: '2026-11-09', on: true }], startWeek: '2026-11-09', weekCount: 2 })
    ).rejects.toThrow('not both')
    await expect(createSwitchbackTest(config, common)).rejects.toThrow('startWeek together with weekCount')
    await expect(createSwitchbackTest(config, { ...common, startWeek: '2026-11-09' })).rejects.toThrow('startWeek together with weekCount')
    await expect(
      createSwitchbackTest(config, { ...common, weeks: [{ weekStart: '2026-11-09' } as never, { weekStart: '2026-11-16', on: false }] })
    ).rejects.toThrow('on (true or false)')
    expect(apiPost).not.toHaveBeenCalled()
  })
})

describe('startSwitchbackTest and getSwitchbackTest', () => {
  it('starts by id and reports this week\'s state', async () => {
    mocked(apiPost).mockResolvedValue({
      ...draft,
      status: 'RUNNING',
      currentWeek: { state: 'ON', weekStart: '2026-11-09', weekEnd: '2026-11-15' },
    })

    const out = await startSwitchbackTest(config, { id: 't 1' })

    expect(apiPost).toHaveBeenCalledWith(`${base}/t%201/start`, {}, config)
    expect(out['status']).toBe('RUNNING')
    expect(out['thisWeek']).toBe('ON (2026-11-09 to 2026-11-15)')
  })

  it('shows what a waiting test is missing and omits result and verdict until settled', async () => {
    mocked(apiGet).mockResolvedValue({ ...draft, status: 'RUNNING', waitingFor: 'Waiting for settled data: Apple Ads first-time downloads on 2026-11-22' })

    const out = await getSwitchbackTest(config, { id: 't1' })

    expect(apiGet).toHaveBeenCalledWith(`${base}/t1`, config)
    expect(out['waitingFor']).toContain('2026-11-22')
    expect(out).not.toHaveProperty('result')
    expect(out).not.toHaveProperty('verdict')
  })

  it('compacts a settled result with the n reached, the change id and the weekly table', async () => {
    mocked(apiGet).mockResolvedValue({
      ...draft,
      status: 'INCONCLUSIVE',
      verdict: 'inconclusive',
      changeWorkItemId: 'w9',
      result: {
        weeks: [{ weekStart: '2026-11-09', on: true, searchDownloads: 8, adsNewDownloads: 4, organic: 4, totalInstalls: 8 }],
        onWeeks: { weeks: 1, meanOrganic: 4, meanTotalInstalls: 8 },
        offWeeks: { weeks: 1, meanOrganic: 4, meanTotalInstalls: 4 },
        n: 12,
        minN: 20,
        lift: 4,
        organicChangePct: null,
        verdict: 'inconclusive',
        reason: 'Inconclusive for "recipe app": n reached 12 of min 20 first-time search downloads, so no verdict was drawn.',
      },
    })

    const out = await getSwitchbackTest(config, { id: 't1' })

    expect(out['verdict']).toBe('inconclusive')
    expect(out['changeWorkItemId']).toBe('w9')
    const result = out['result'] as Record<string, unknown>
    expect(result['n']).toBe(12)
    expect(result['reason']).toContain('n reached 12 of min 20')
    expect(result).not.toHaveProperty('organicChangePct')
    expect(result['weeks']).toEqual(['2026-11-09 ON: search 8, ads 4, organic 4, total 8'])
  })

  it('keeps an organic change of exactly 0 (known) distinct from an unknown one', async () => {
    mocked(apiGet).mockResolvedValue({
      ...draft,
      status: 'SETTLED',
      verdict: 'worked',
      result: { weeks: [], n: 100, minN: 20, lift: 5, organicChangePct: 0, verdict: 'worked', reason: 'r' },
    })

    const out = await getSwitchbackTest(config, { id: 't1' })

    expect((out['result'] as Record<string, unknown>)['organicChangePct']).toBe(0)
  })
})

describe('listSwitchbackTests', () => {
  it('lists each test briefly with its state this week and any verdict', async () => {
    mocked(apiGet).mockResolvedValue({
      tests: [
        { ...draft, status: 'RUNNING', currentWeek: { state: 'OFF', weekStart: '2026-11-16', weekEnd: '2026-11-22' } },
        { ...draft, id: 't2', status: 'SETTLED', verdict: 'worked' },
      ],
    })

    const out = await listSwitchbackTests(config)

    expect(apiGet).toHaveBeenCalledWith(base, config)
    expect(out['tests']).toEqual([
      { id: 't1', name: 'Brand term', keyword: 'recipe app', status: 'RUNNING', thisWeek: 'OFF' },
      { id: 't2', name: 'Brand term', keyword: 'recipe app', status: 'SETTLED', thisWeek: 'not running', verdict: 'worked' },
    ])
  })
})

describe('tool definitions', () => {
  const names = ['create_switchback_test', 'start_switchback_test', 'get_switchback_test', 'list_switchback_tests']

  it('registers all four tools', () => {
    expect(TOOLS.map((t) => t.name)).toEqual(expect.arrayContaining(names))
  })

  it('says a person applies the switch and the test settles itself, and names the read-back companion', () => {
    const create = TOOLS.find((t) => t.name === 'create_switchback_test')!
    expect(create.description).toMatch(/person switches the keyword/)
    expect(create.description).toContain('get_switchback_test')
    const start = TOOLS.find((t) => t.name === 'start_switchback_test')!
    expect(start.description).toMatch(/settles itself/)
    expect(start.description).toContain('get_switchback_test')
  })

  it('declares the required parameters', () => {
    const required = (n: string) => (TOOLS.find((t) => t.name === n)!.inputSchema as { required?: string[] }).required
    expect(required('create_switchback_test')).toEqual(['name', 'keywordId', 'keywordText'])
    expect(required('start_switchback_test')).toEqual(['id'])
    expect(required('get_switchback_test')).toEqual(['id'])
    expect(required('list_switchback_tests')).toBeUndefined()
  })
})
