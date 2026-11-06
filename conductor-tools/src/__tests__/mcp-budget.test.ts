import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet, apiPut, apiPost, apiDelete } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import { getBudgetPacing, setMarketingBudget, setStopRule, removeStopRule } from '../mcp/tools/budget.js'

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

const day = (n: number, extra: Record<string, unknown> = {}) => ({
  day: `2026-11-${String(n).padStart(2, '0')}`,
  spend: 5,
  cumulativeSpend: n * 5,
  expectedSpend: 5.33 * n,
  ...extra,
})

const rule = (over: Record<string, unknown> = {}) => ({
  ruleId: 'r1',
  channel: 'ADS',
  scope: 'channel',
  metric: 'cost_per_install',
  comparator: 'GT',
  threshold: 10,
  minSample: 20,
  windowDays: 7,
  windowFrom: '2026-11-09',
  windowTo: '2026-11-15',
  observed: 12.3456789,
  sample: 25,
  tripped: false,
  belowMinSample: false,
  ...over,
})

const window = (over: Record<string, unknown> = {}) => ({
  from: '2026-11-09',
  to: '2026-11-15',
  spend: 70,
  installs: 14,
  cpi: 5,
  ...over,
})

const pacing = (over: Record<string, unknown> = {}) => ({
  month: '2026-11',
  capAmount: 160,
  currency: 'USD',
  through: '2026-11-15',
  daysElapsed: 15,
  daysInMonth: 30,
  mtdSpend: 80,
  provisionalSpend: 14,
  expectedSpend: 80,
  pacePct: 1,
  projectedSpend: 160,
  daily: Array.from({ length: 15 }, (_, i) => day(i + 1)),
  channels: [
    {
      channel: 'ADS',
      through: '2026-11-15',
      mtdSpend: 80,
      provisionalSpend: 14,
      installs: 21,
      cpi: 3.81,
      compliant: true,
      daily: Array.from({ length: 15 }, (_, i) => day(i + 1)),
      rules: [rule()],
      marginalCpi: {
        last7: window(),
        prior7: window({ from: '2026-11-02', to: '2026-11-08', installs: 7, cpi: 10 }),
        monthToDate: window({ from: '2026-11-01', spend: 80, installs: 21, cpi: 3.81 }),
      },
    },
  ],
  ...over,
})

describe('getBudgetPacing', () => {
  it('reads the current month by default and keeps cap, pace and projection', async () => {
    mocked(apiGet).mockResolvedValue(pacing())

    const out = (await getBudgetPacing(config, {})) as Record<string, any>

    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/budget', config)
    expect(out).toMatchObject({
      month: '2026-11',
      cap: 160,
      currency: 'USD',
      through: '2026-11-15',
      daysElapsed: 15,
      daysInMonth: 30,
      mtdSpend: 80,
      provisionalSpend: 14,
      expectedSpend: 80,
      pacePct: 1,
      projectedSpend: 160,
      nonCompliantChannels: [],
    })
    expect(out['capNote']).toBeUndefined()
  })

  it('passes the month and rejects one that is not YYYY-MM without calling the API', async () => {
    mocked(apiGet).mockResolvedValue(pacing())
    await getBudgetPacing(config, { month: '2026-10' })
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/budget?month=2026-10', config)
    vi.clearAllMocks()
    for (const bad of ['2026-13', '2026-1', 'November', '']) {
      await expect(getBudgetPacing(config, { month: bad })).rejects.toThrow(/YYYY-MM/)
    }
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('shows no cap as null with no pace, never as 0', async () => {
    mocked(apiGet).mockResolvedValue(
      pacing({ capAmount: null, currency: null, expectedSpend: null, pacePct: null })
    )

    const out = (await getBudgetPacing(config, {})) as Record<string, any>

    expect(out['cap']).toBeNull()
    expect(out['currency']).toBeNull()
    expect(out['pacePct']).toBeNull()
    expect(out['expectedSpend']).toBeNull()
    expect(out['capNote']).toMatch(/no cap set/)
    expect(out['mtdSpend']).toBe(80)
    expect(out['projectedSpend']).toBe(160)
  })

  it('names the channels without a stop rule', async () => {
    const base = pacing() as any
    mocked(apiGet).mockResolvedValue({
      ...base,
      channels: [
        { ...base.channels[0], compliant: false, complianceReason: 'no stop rule', rules: [] },
        { ...base.channels[0], channel: 'SOCIAL' },
      ],
    })

    const out = (await getBudgetPacing(config, {})) as Record<string, any>

    expect(out['nonCompliantChannels']).toEqual(['ADS'])
    expect(out['channels'][0]).toMatchObject({ channel: 'ADS', compliant: false, complianceReason: 'no stop rule', rules: [] })
    expect(out['channels'][1]).toMatchObject({ channel: 'SOCIAL', compliant: true })
    expect(out['channels'][1]).not.toHaveProperty('complianceReason')
  })

  it('lists tripped rules with the figures an alert needs, and states for every rule', async () => {
    const base = pacing() as any
    mocked(apiGet).mockResolvedValue({
      ...base,
      channels: [
        {
          ...base.channels[0],
          rules: [
            rule({ ruleId: 'tripped', tripped: true, observed: 20, note: 'Pause the ad group' }),
            rule({ ruleId: 'small', belowMinSample: true, sample: 15 }),
            rule({ ruleId: 'nodata', observed: null, sample: null, detail: 'no data to compute this metric over the window' }),
            rule({ ruleId: 'fine', observed: 5 }),
          ],
        },
      ],
    })

    const out = (await getBudgetPacing(config, {})) as Record<string, any>

    expect(out['trippedRules']).toEqual([
      {
        ruleId: 'tripped',
        channel: 'ADS',
        scope: 'channel',
        metric: 'cost_per_install',
        comparator: 'GT',
        threshold: 10,
        observed: 20,
        sample: 25,
        windowFrom: '2026-11-09',
        windowTo: '2026-11-15',
        note: 'Pause the ad group',
      },
    ])
    const states = Object.fromEntries(out['channels'][0].rules.map((r: any) => [r.ruleId, r.state]))
    expect(states).toEqual({ tripped: 'TRIPPED', small: 'BELOW_MIN_SAMPLE', nodata: 'NO_DATA', fine: 'OK' })
    expect(out['channels'][0].rules[2]).toMatchObject({ observed: null, sample: null })
  })

  it('shows marginal cost per install for the last 7, the prior 7 and month-to-date', async () => {
    mocked(apiGet).mockResolvedValue(pacing())

    const out = (await getBudgetPacing(config, {})) as Record<string, any>
    const m = out['channels'][0].marginalCpi

    expect(m.last7).toEqual({ from: '2026-11-09', to: '2026-11-15', spend: 70, installs: 14, cpi: 5 })
    expect(m.prior7).toMatchObject({ from: '2026-11-02', to: '2026-11-08', installs: 7, cpi: 10 })
    expect(m.monthToDate).toMatchObject({ from: '2026-11-01', spend: 80, installs: 21, cpi: 3.81 })
  })

  it('reads a window with zero installs as "no installs", not as a cost of 0', async () => {
    const base = pacing() as any
    mocked(apiGet).mockResolvedValue({
      ...base,
      channels: [
        {
          ...base.channels[0],
          marginalCpi: {
            ...base.channels[0].marginalCpi,
            last7: window({ installs: 0, cpi: null }),
            prior7: window({ spend: null, installs: null, cpi: null }),
          },
        },
      ],
    })

    const m = ((await getBudgetPacing(config, {})) as Record<string, any>)['channels'][0].marginalCpi

    expect(m.last7).toMatchObject({ spend: 70, installs: 0, cpi: null, cpiNote: 'no installs' })
    expect(m.prior7).toMatchObject({ spend: null, installs: null, cpi: null, cpiNote: 'no data' })
  })

  it('says installs are not reported when only spend is', async () => {
    const base = pacing() as any
    mocked(apiGet).mockResolvedValue({
      ...base,
      channels: [{ ...base.channels[0], installs: null, cpi: null }],
    })

    const channel = ((await getBudgetPacing(config, {})) as Record<string, any>)['channels'][0]

    expect(channel.installs).toBeNull()
    expect(channel.cpi).toBeNull()
    expect(channel.cpiNote).toBe('installs not reported')
  })

  it('keeps only the last 7 days of the daily series and drops the per-channel series', async () => {
    mocked(apiGet).mockResolvedValue(
      pacing({ daily: [...Array.from({ length: 14 }, (_, i) => day(i + 1)), day(15, { spend: null, provisionalSpend: 7 })] })
    )

    const out = (await getBudgetPacing(config, {})) as Record<string, any>

    expect(out['daily']).toHaveLength(7)
    expect(out['daily'][0].day).toBe('2026-11-09')
    expect(out['daily'][6]).toMatchObject({ day: '2026-11-15', spend: null, provisionalSpend: 7, cumulativeSpend: 75 })
    expect(out['channels'][0]).not.toHaveProperty('daily')
  })

  it('omits the marginal view for a channel with no settled spend yet', async () => {
    const base = pacing() as any
    mocked(apiGet).mockResolvedValue({ ...base, channels: [{ ...base.channels[0], marginalCpi: null, through: null }] })

    const channel = ((await getBudgetPacing(config, {})) as Record<string, any>)['channels'][0]

    expect(channel).not.toHaveProperty('marginalCpi')
    expect(channel.through).toBeNull()
  })
})

describe('setMarketingBudget', () => {
  it('PUTs the cap to the month and returns the stored cap', async () => {
    mocked(apiPut).mockResolvedValue({ month: '2026-11', capAmount: 160, currency: 'USD', updatedAt: '2026-11-05T00:00:00Z' })

    const out = await setMarketingBudget(config, { month: '2026-11', capAmount: 160 })

    expect(apiPut).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/budget/2026-11',
      { capAmount: 160, currency: undefined },
      config
    )
    expect(out).toEqual({ month: '2026-11', capAmount: 160, currency: 'USD' })
  })

  it('passes a currency through', async () => {
    mocked(apiPut).mockResolvedValue({ month: '2026-11', capAmount: 150, currency: 'EUR' })
    await setMarketingBudget(config, { month: '2026-11', capAmount: 150, currency: 'EUR' })
    expect(mocked(apiPut).mock.calls[0]![1]).toEqual({ capAmount: 150, currency: 'EUR' })
  })

  it('refuses a zero, negative or non-numeric cap, a bad month and a bad currency without calling the API', async () => {
    await expect(setMarketingBudget(config, { month: '2026-11', capAmount: 0 })).rejects.toThrow(/greater than 0/)
    await expect(setMarketingBudget(config, { month: '2026-11', capAmount: -5 })).rejects.toThrow(/greater than 0/)
    await expect(setMarketingBudget(config, { month: '2026-11', capAmount: Number.NaN })).rejects.toThrow(/greater than 0/)
    await expect(setMarketingBudget(config, { month: '2026-11', capAmount: '160' as unknown as number })).rejects.toThrow(
      /greater than 0/
    )
    await expect(setMarketingBudget(config, { month: '2026-13', capAmount: 160 })).rejects.toThrow(/YYYY-MM/)
    await expect(setMarketingBudget(config, { month: '2026-11', capAmount: 160, currency: 'DOLLARS' })).rejects.toThrow(
      /currency/
    )
    expect(apiPut).not.toHaveBeenCalled()
  })
})

describe('setStopRule', () => {
  const ok = { channel: 'ADS', metric: 'cost_per_install', comparator: 'GT', threshold: 10 }

  it('POSTs the rule and returns it with a ruleId', async () => {
    mocked(apiPost).mockResolvedValue({
      id: 'rule-9',
      channel: 'ADS',
      scope: 'keyword:7',
      metric: 'cost_per_install',
      comparator: 'GT',
      threshold: 10,
      minSample: 20,
      windowDays: 14,
      note: 'Pause it',
      createdAt: '2026-11-05T00:00:00Z',
    })

    const out = await setStopRule(config, { ...ok, minSample: 20, windowDays: 14, scope: 'keyword:7', note: 'Pause it' })

    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/stop-rules',
      {
        channel: 'ADS',
        scope: 'keyword:7',
        metric: 'cost_per_install',
        comparator: 'GT',
        threshold: 10,
        minSample: 20,
        windowDays: 14,
        note: 'Pause it',
      },
      config
    )
    expect(out).toEqual({
      ruleId: 'rule-9',
      channel: 'ADS',
      scope: 'keyword:7',
      metric: 'cost_per_install',
      comparator: 'GT',
      threshold: 10,
      minSample: 20,
      windowDays: 14,
      note: 'Pause it',
    })
  })

  it('sends no optional field it was not given', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'r', channel: 'ADS', scope: 'channel', metric: 'spend', comparator: 'LT', threshold: 1, minSample: 0, windowDays: 7 })

    const out = await setStopRule(config, { channel: 'ADS', metric: 'spend', comparator: 'LT', threshold: 1 })

    const body = JSON.parse(JSON.stringify(mocked(apiPost).mock.calls[0]![1]))
    expect(body).toEqual({ channel: 'ADS', metric: 'spend', comparator: 'LT', threshold: 1 })
    expect(out).not.toHaveProperty('note')
  })

  it('refuses a rule the backend would refuse, without calling the API', async () => {
    await expect(setStopRule(config, { ...ok, channel: ' ' })).rejects.toThrow(/channel/)
    await expect(setStopRule(config, { ...ok, metric: 'roas' })).rejects.toThrow(/metric/)
    await expect(setStopRule(config, { ...ok, comparator: 'EQ' })).rejects.toThrow(/comparator/)
    await expect(setStopRule(config, { ...ok, comparator: 'gt' })).rejects.toThrow(/comparator/)
    await expect(setStopRule(config, { ...ok, threshold: -1 })).rejects.toThrow(/threshold/)
    await expect(setStopRule(config, { ...ok, threshold: Number.NaN })).rejects.toThrow(/threshold/)
    await expect(setStopRule(config, { ...ok, minSample: -1 })).rejects.toThrow(/minSample/)
    await expect(setStopRule(config, { ...ok, minSample: 2.5 })).rejects.toThrow(/minSample/)
    await expect(setStopRule(config, { ...ok, windowDays: 0 })).rejects.toThrow(/windowDays/)
    await expect(setStopRule(config, { ...ok, windowDays: 91 })).rejects.toThrow(/windowDays/)
    await expect(setStopRule(config, { ...ok, scope: 'adgroup:5' })).rejects.toThrow(/scope/)
    await expect(setStopRule(config, { ...ok, scope: 'keyword:' })).rejects.toThrow(/scope/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('accepts a threshold of 0 and a campaign scope', async () => {
    mocked(apiPost).mockResolvedValue({ id: 'r', channel: 'ADS', scope: 'campaign:12', metric: 'installs', comparator: 'LT', threshold: 0 })
    await expect(setStopRule(config, { channel: 'ADS', metric: 'installs', comparator: 'LT', threshold: 0, scope: 'campaign:12' })).resolves.toBeDefined()
  })
})

describe('removeStopRule', () => {
  it('DELETEs the rule by id', async () => {
    mocked(apiDelete).mockResolvedValue(undefined)

    expect(await removeStopRule(config, { ruleId: 'rule 9' })).toEqual({ removed: 'rule 9' })
    expect(apiDelete).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/stop-rules/rule%209', config)
  })

  it('refuses a blank ruleId', async () => {
    await expect(removeStopRule(config, { ruleId: ' ' })).rejects.toThrow(/ruleId/)
    expect(apiDelete).not.toHaveBeenCalled()
  })
})

describe('budget tool registry entries', () => {
  const tool = (name: string) => TOOLS.find((t) => t.name === name)!

  it('registers all four tools', () => {
    for (const name of ['get_budget_pacing', 'set_marketing_budget', 'set_stop_rule', 'remove_stop_rule']) {
      expect(tool(name), name).toBeDefined()
    }
  })

  it('says Conductor only proposes and records, and a human applies the change', () => {
    for (const name of ['get_budget_pacing', 'set_stop_rule']) {
      expect(tool(name).description, name).toMatch(/only (proposes and records|evaluates and records)/)
      expect(tool(name).description, name).toMatch(/human/)
    }
  })

  it('names a read-back companion on every mutating tool', () => {
    for (const name of ['set_marketing_budget', 'set_stop_rule', 'remove_stop_rule']) {
      expect(tool(name).description, name).toMatch(/get_budget_pacing/)
    }
  })

  it('declares the required parameters and the rule vocabulary', () => {
    const schema = (name: string) =>
      tool(name).inputSchema as { properties: Record<string, { enum?: string[] }>; required?: string[] }
    expect(schema('get_budget_pacing').required ?? []).toEqual([])
    expect(schema('set_marketing_budget').required).toEqual(['month', 'capAmount'])
    expect(schema('set_stop_rule').required).toEqual(['channel', 'metric', 'comparator', 'threshold'])
    expect(schema('set_stop_rule').properties['comparator']!.enum).toEqual(['GT', 'LT'])
    expect(schema('set_stop_rule').properties['metric']!.enum).toContain('cost_per_install')
    expect(Object.keys(schema('set_stop_rule').properties)).toEqual(
      expect.arrayContaining(['minSample', 'windowDays', 'scope', 'note'])
    )
    expect(schema('remove_stop_rule').required).toEqual(['ruleId'])
  })
})
