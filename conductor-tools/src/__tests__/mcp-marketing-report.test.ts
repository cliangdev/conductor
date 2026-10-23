import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet, apiPut, apiDelete } from '../mcp/api.js'
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

  it('defaults to 28d, caps keywords at 25 and ads at 20, and reports the totals', async () => {
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
    expect(out['totalRows']).toEqual({ keywords: 30, ads: 22 })
    expect((out['keywords'] as Record<string, unknown>[])[0]).toEqual({ keywordId: 'k0', text: 'kw 0', judgeable: false, spend: 1.2346 })
    // emptied sections stay present; the empty changes object is dropped
    expect(out['sources']).toEqual([])
    expect(out['changes']).toEqual({ open: [], dueForReview: [], reviewedInWindow: [] })
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
