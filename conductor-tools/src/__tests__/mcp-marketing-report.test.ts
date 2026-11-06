import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet, apiPut, apiDelete } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import {
  getMarketingReport,
  setMarketingBenchmark,
  deleteMarketingBenchmark,
  trimReportValue,
} from '../mcp/tools/marketing-report.js'

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

describe('trimReportValue', () => {
  it('drops nulls and empties and rounds', () => {
    expect(trimReportValue({ a: null, b: [], c: {}, d: 0.123456789, e: 1234.5678, f: 'x', g: false })).toEqual({
      d: 0.1235,
      e: 1234.57,
      f: 'x',
      g: false,
    })
  })
})

describe('getMarketingReport', () => {
  const kw = (i: number) => ({ keywordId: `k${i}`, text: `kw ${i}`, judgeable: false, bid: null, spend: 1.23456 })

  it('defaults to 28d, caps keywords at 25 and ads at 20, and reports the ad total', async () => {
    mocked(apiGet).mockResolvedValue({
      window: { from: '2026-09-20', to: '2026-10-17' },
      sources: [],
      headline: [],
      acquisition: [],
      daily: [],
      scorecard: [],
      benchmarks: [],
      keywords: Array.from({ length: 30 }, (_, i) => kw(i)),
      ads: Array.from({ length: 22 }, (_, i) => ({ adId: `a${i}`, name: `ad ${i}` })),
      changes: { open: [], dueForReview: [], reviewedInWindow: [] },
    })
    const out = (await getMarketingReport(config, {})) as Record<string, unknown>
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/report?window=28d', config)
    expect((out['keywords'] as unknown[]).length).toBe(25)
    expect((out['ads'] as unknown[]).length).toBe(20)
    expect(out['totalRows']).toEqual({ ads: 22 })
    expect((out['keywords'] as Record<string, unknown>[])[0]).toEqual({ keywordId: 'k0', text: 'kw 0', judgeable: false, spend: 1.2346 })
    // emptied sections stay present; the empty changes object is dropped
    expect(out['sources']).toEqual([])
    expect(out['changes']).toEqual({ open: [], dueForReview: [], reviewedInWindow: [] })
  })

  it('keeps the RE-99 fields: view-through installs, derivation, comparable and reason, definitions, store, markers', async () => {
    const definition = { source: 'Apple Ads campaign report, tapInstalls', numerator: 'tap_installs', timeZone: 'UTC', denominator: null }
    mocked(apiGet).mockResolvedValue({
      window: { from: '2026-09-29', to: '2026-10-26' },
      sources: [{ channel: 'ADS', status: 'LIVE', timeZone: 'UTC', searchTermTimeZone: 'ORTZ', connector: null }],
      headline: [{ channel: 'ADS', key: 'cost_per_install', value: 7.0809, definition }],
      acquisition: [
        { key: 'apple_ads', installs: 23, viewThroughInstalls: 1, likes: null },
        {
          key: 'app_store_search',
          installs: 6,
          reach: 3696,
          reachLabel: 'Impressions (organic)',
          derived: true,
          derivation: { from: '2026-09-29', to: '2026-10-26', days: 28, grossInstalls: 29, paidInstalls: 23, grossReach: 7101, paidReach: 3405 },
        },
      ],
      benchmarks: [
        { metricRef: 'STORE.conversion_rate', comparable: true, comparedMetricRef: 'STORE.conversion_rate_search', reason: null, judged: true },
        { metricRef: 'ADS.spend', comparable: false, reason: 'definition missing', judged: false },
      ],
      definitions: { 'ADS.installs': definition },
      store: { downloadsOfRecord: 30, analyticsDownloads: 27, coverage: { daysCovered: 28, daysInWindow: 28 } },
      adsCoverage: [{ campaignId: '1', name: 'Brand', spend: 100, keywordSpend: 98, keywordCoveragePct: 0.98, rows: 2 }],
      changeMarkers: [{ day: '2026-10-10', changeId: 'w1', displayId: 'RE-1', title: 'Change', status: 'LIVE' }],
      changes: { open: [], dueForReview: [], reviewedInWindow: [] },
    })
    const out = (await getMarketingReport(config, {})) as Record<string, any>
    expect(out['sources'][0]).toEqual({ channel: 'ADS', status: 'LIVE', timeZone: 'UTC', searchTermTimeZone: 'ORTZ' })
    expect(out['acquisition'][0]).toEqual({ key: 'apple_ads', installs: 23, viewThroughInstalls: 1 })
    expect(out['acquisition'][1].derivation).toEqual({
      from: '2026-09-29',
      to: '2026-10-26',
      days: 28,
      grossInstalls: 29,
      paidInstalls: 23,
      grossReach: 7101,
      paidReach: 3405,
    })
    expect(out['benchmarks'][0]).toEqual({
      metricRef: 'STORE.conversion_rate',
      comparable: true,
      comparedMetricRef: 'STORE.conversion_rate_search',
      judged: true,
    })
    // a comparable: false is not a null, so it stays, and so does the reason that goes with it
    expect(out['benchmarks'][1]).toEqual({ metricRef: 'ADS.spend', comparable: false, reason: 'definition missing', judged: false })
    expect(out['headline'][0].definition).toEqual({ source: definition.source, numerator: 'tap_installs', timeZone: 'UTC' })
    expect(out['definitions']['ADS.installs']).toEqual({ source: definition.source, numerator: 'tap_installs', timeZone: 'UTC' })
    expect(out['store']).toEqual({ downloadsOfRecord: 30, analyticsDownloads: 27, coverage: { daysCovered: 28, daysInWindow: 28 } })
    expect(out['adsCoverage']).toEqual([{ campaignId: '1', name: 'Brand', spend: 100, keywordSpend: 98, keywordCoveragePct: 0.98, rows: 2 }])
    expect(out['changeMarkers']).toEqual([{ day: '2026-10-10', changeId: 'w1', displayId: 'RE-1', title: 'Change', status: 'LIVE' }])
    expect(out['totalRows']).toBeUndefined()
  })

  it('caps the coverage rows and the change markers at 20, keeps the newest markers and reports the totals', async () => {
    const day = (i: number) => `2026-10-${String(i + 1).padStart(2, '0')}`
    mocked(apiGet).mockResolvedValue({
      window: { from: '2026-09-29', to: '2026-10-26' },
      adsCoverage: Array.from({ length: 23 }, (_, i) => ({ campaignId: `c${i}`, name: `campaign ${i}`, rows: 1 })),
      changeMarkers: Array.from({ length: 25 }, (_, i) => ({ day: day(i), changeId: `w${i}`, displayId: `RE-${i}`, title: `t${i}`, status: 'LIVE' })),
    })
    const out = (await getMarketingReport(config, {})) as Record<string, any>
    expect(out['adsCoverage']).toHaveLength(20)
    expect(out['adsCoverage'][0].campaignId).toBe('c0')
    expect(out['changeMarkers']).toHaveLength(20)
    expect(out['changeMarkers'][0].changeId).toBe('w5')
    expect(out['changeMarkers'][19].changeId).toBe('w24')
    expect(out['totalRows']).toEqual({ adsCoverage: 23, changeMarkers: 25 })
  })

  it('keeps empty coverage, markers and definitions as present keys so "none" never reads as missing', async () => {
    mocked(apiGet).mockResolvedValue({ window: { from: '2026-09-29', to: '2026-10-26' }, sources: [] })
    const out = (await getMarketingReport(config, {})) as Record<string, unknown>
    expect(out['adsCoverage']).toEqual([])
    expect(out['changeMarkers']).toEqual([])
    expect(out['definitions']).toEqual({})
    expect(out['store']).toBeUndefined()
  })

  it('keeps all three change buckets when only some have items', async () => {
    mocked(apiGet).mockResolvedValue({
      window: { from: '2026-09-20', to: '2026-10-17' },
      changes: { open: [{ id: 'w1', title: 'Change' }], dueForReview: [], reviewedInWindow: [] },
    })
    const out = (await getMarketingReport(config, {})) as Record<string, any>
    expect(out['changes'].open).toHaveLength(1)
    expect(out['changes'].dueForReview).toEqual([])
    expect(out['changes'].reviewedInWindow).toEqual([])
  })

  it('keeps changes and a null window.to when the report has no data yet', async () => {
    mocked(apiGet).mockResolvedValue({ window: { from: '2026-09-20', to: null }, sources: [] })
    const out = (await getMarketingReport(config, {})) as Record<string, unknown>
    expect(out['window']).toEqual({ from: '2026-09-20', to: null })
    expect(out['changes']).toEqual({ open: [], dueForReview: [], reviewedInWindow: [] })
  })

  it('passes 7d and rejects other windows', async () => {
    mocked(apiGet).mockResolvedValue({ window: { from: '2026-10-10' } })
    await getMarketingReport(config, { window: '7d' })
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/report?window=7d', config)
    await expect(getMarketingReport(config, { window: '30d' })).rejects.toThrow(/Unknown window/)
  })
})

describe('setMarketingBenchmark', () => {
  const ok = {
    metricRef: 'STORE.conversion_rate',
    value: 0.03,
    sourceName: 'Publisher',
    sourceUrl: 'https://example.com/benchmarks',
    checkedOn: '2026-10-22',
  }

  it('PUTs to the encoded metricRef and returns the trimmed result', async () => {
    mocked(apiPut).mockResolvedValue({ metricRef: 'STORE.conversion_rate', value: 0.03, label: null })
    const out = await setMarketingBenchmark(config, { ...ok, segment: 'US' })
    const [path, body] = mocked(apiPut).mock.calls[0]!
    expect(path).toBe('/api/v2/projects/proj-1/marketing/benchmarks/STORE.conversion_rate')
    expect(body).toMatchObject({ value: 0.03, sourceUrl: ok.sourceUrl, checkedOn: '2026-10-22', segment: 'US' })
    expect(out).toEqual({ metricRef: 'STORE.conversion_rate', value: 0.03 })
  })

  it('passes the definition (numerator, denominator, sourceType) to the API', async () => {
    mocked(apiPut).mockResolvedValue({
      metricRef: 'STORE.conversion_rate',
      value: 0.124,
      numerator: 'total_downloads',
      denominator: 'unique_impressions',
      sourceType: 'APP_STORE_SEARCH',
    })
    const out = await setMarketingBenchmark(config, {
      ...ok,
      value: 0.124,
      numerator: 'total_downloads',
      denominator: 'unique_impressions',
      sourceType: 'APP_STORE_SEARCH',
    })
    const [, body] = mocked(apiPut).mock.calls[0]!
    expect(body).toMatchObject({
      numerator: 'total_downloads',
      denominator: 'unique_impressions',
      sourceType: 'APP_STORE_SEARCH',
    })
    expect(out).toMatchObject({ numerator: 'total_downloads', denominator: 'unique_impressions', sourceType: 'APP_STORE_SEARCH' })
  })

  it('still succeeds for an old call without a definition and sends none', async () => {
    mocked(apiPut).mockResolvedValue({ metricRef: 'STORE.conversion_rate', value: 0.03 })
    await expect(setMarketingBenchmark(config, ok)).resolves.toEqual({ metricRef: 'STORE.conversion_rate', value: 0.03 })
    const [, body] = mocked(apiPut).mock.calls[0]!
    expect(JSON.parse(JSON.stringify(body))).not.toHaveProperty('numerator')
    expect(JSON.parse(JSON.stringify(body))).not.toHaveProperty('denominator')
    expect(JSON.parse(JSON.stringify(body))).not.toHaveProperty('sourceType')
  })

  it('describes the vocabulary and the AppFollow example in the tool schema', () => {
    const tool = TOOLS.find((t) => t.name === 'set_marketing_benchmark')!
    const schema = tool.inputSchema as { properties: Record<string, { description: string }>; required: string[] }
    expect(Object.keys(schema.properties)).toEqual(expect.arrayContaining(['numerator', 'denominator', 'sourceType']))
    expect(schema.required).not.toEqual(expect.arrayContaining(['numerator']))
    expect(schema.required).not.toEqual(expect.arrayContaining(['sourceType']))
    expect(schema.properties['numerator']!.description).toMatch(/total_downloads/)
    expect(schema.properties['numerator']!.description).toMatch(/product:<key>/)
    expect(schema.properties['denominator']!.description).toMatch(/unique_impressions/)
    expect(schema.properties['sourceType']!.description).toMatch(/APP_STORE_SEARCH/)
    expect(schema.properties['sourceType']!.description).toMatch(/ALL/)
  })

  it('refuses a benchmark without a real source URL or a date', async () => {
    await expect(setMarketingBenchmark(config, { ...ok, sourceUrl: '' })).rejects.toThrow(/never recorded from memory/)
    await expect(setMarketingBenchmark(config, { ...ok, sourceUrl: 'from memory' })).rejects.toThrow(/sourceUrl/)
    await expect(setMarketingBenchmark(config, { ...ok, checkedOn: 'today' })).rejects.toThrow(/checkedOn/)
    await expect(setMarketingBenchmark(config, { ...ok, metricRef: 'bad' })).rejects.toThrow(/metricRef/)
    expect(apiPut).not.toHaveBeenCalled()
  })
})

describe('deleteMarketingBenchmark', () => {
  it('accepts a metric key with dots, as the backend does, and still rejects bad refs', async () => {
    mocked(apiPut).mockResolvedValue({})
    const ok = { metricRef: 'PRODUCT.trial.rate-v2', value: 0.1, sourceName: 's', sourceUrl: 'https://x.test', checkedOn: '2026-10-01' }
    await expect(setMarketingBenchmark(config, ok)).resolves.toBeDefined()
    await expect(setMarketingBenchmark(config, { ...ok, metricRef: 'STORE.a:b' })).rejects.toThrow(/metricRef/)
  })

  it('DELETEs the metricRef', async () => {
    mocked(apiDelete).mockResolvedValue(undefined)
    expect(await deleteMarketingBenchmark(config, { metricRef: 'PRODUCT.trial_rate' })).toEqual({ deleted: 'PRODUCT.trial_rate' })
    expect(apiDelete).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/benchmarks/PRODUCT.trial_rate', config)
  })
})
