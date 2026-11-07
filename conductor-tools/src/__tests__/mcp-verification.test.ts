import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

import { apiGet, apiPost } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import { getVerificationLedger, recordPlatformRead } from '../mcp/tools/verification.js'

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

const row = (over: Record<string, unknown> = {}) => ({
  channel: 'ADS',
  metric: 'installs',
  metricRef: 'ADS.installs',
  label: 'Installs',
  unit: 'COUNT',
  definition: { source: 'Apple Ads campaign report, tapInstalls', numerator: 'tap_installs' },
  windowFrom: '2026-09-29',
  windowTo: '2026-10-26',
  conductorValue: 7,
  backend: {
    value: 23,
    conductorValue: 7,
    daysCompared: 7,
    source: 'Rexipe',
    from: '2026-09-29',
    to: '2026-10-05',
    matches: false,
  },
  platformRead: null,
  verdict: 'CONNECTOR',
  mismatch: true,
  readStale: false,
  trustNote: 'The product backend differs from the connector.',
  ...over,
})

const ledger = () => ({
  window: { from: '2026-09-29', to: '2026-10-26' },
  rows: [
    row(),
    row({
      channel: 'SEARCH',
      metric: 'clicks',
      metricRef: 'SEARCH.clicks',
      label: 'Clicks',
      conductorValue: 3.0000000001,
      backend: null,
      platformRead: {
        id: 'r1',
        value: 3,
        from: '2026-09-29',
        to: '2026-10-26',
        sourceName: 'Search Console UI',
        note: null,
        recordedBy: 'u1',
        recordedAt: '2026-10-29T12:00:00Z',
        conductorValueOverReadDays: 3,
        matches: true,
      },
      verdict: 'BOTH',
      mismatch: false,
      trustNote: 'Conductor agrees with the Search Console UI read.',
    }),
    row({
      channel: 'SOCIAL',
      metric: 'followers',
      metricRef: 'SOCIAL.followers',
      label: 'Followers',
      conductorValue: null,
      backend: null,
      verdict: 'UNCHECKED',
      mismatch: false,
      trustNote: 'Only the connector reports this.',
    }),
    row({
      channel: 'STORE',
      metric: 'downloads',
      metricRef: 'STORE.downloads',
      label: 'Downloads',
      conductorValue: 30,
      backend: null,
      platformRead: {
        id: 'r2',
        value: 30,
        from: '2026-09-29',
        to: '2026-10-26',
        sourceName: 'App Store Connect',
        recordedAt: '2026-10-01T12:00:00Z',
        conductorValueOverReadDays: 30,
        matches: true,
      },
      verdict: 'BOTH',
      mismatch: false,
      readStale: true,
      trustNote: 'Conductor agrees with the App Store Connect read. The read is 28 days old; log a new one.',
    }),
  ],
  summary: { both: 2, connector: 1, backend: 0, platform: 0, unchecked: 1, mismatched: 1, staleReads: 1 },
})

describe('getVerificationLedger', () => {
  it('defaults to 28d and returns compact rows without nulls, never turning unknown into zero', async () => {
    mocked(apiGet).mockResolvedValue(ledger())

    const out = (await getVerificationLedger(config, {})) as Record<string, any>

    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/marketing/verification?window=28d', config)
    expect(out['window']).toEqual({ from: '2026-09-29', to: '2026-10-26' })
    expect(out['summary']).toEqual(ledger().summary)
    const ads = out['rows'][0]
    expect(ads).toMatchObject({
      metric: 'ADS.installs',
      value: 7,
      verdict: 'CONNECTOR',
      mismatch: true,
      backend: { value: 23, conductorValue: 7, days: 7, source: 'Rexipe', matches: false },
    })
    expect(ads).not.toHaveProperty('read')
    expect(ads).not.toHaveProperty('definition')
    // rounded, and an unknown stays absent
    expect(out['rows'][1].value).toBe(3)
    const social = out['rows'][2]
    expect(social.verdict).toBe('UNCHECKED')
    expect(social).not.toHaveProperty('value')
    expect(social).not.toHaveProperty('mismatch')
    expect(social).not.toHaveProperty('backend')
  })

  it('shows a platform read with the figure Conductor had over its days', async () => {
    mocked(apiGet).mockResolvedValue(ledger())

    const out = (await getVerificationLedger(config, {})) as Record<string, any>

    expect(out['rows'][1].read).toEqual({
      value: 3,
      from: '2026-09-29',
      to: '2026-10-26',
      source: 'Search Console UI',
      conductorValue: 3,
      matches: true,
    })
    expect(out['rows'][3].stale).toBe(true)
  })

  it('passes a fixed window and refuses anything else', async () => {
    mocked(apiGet).mockResolvedValue(ledger())

    await getVerificationLedger(config, { window: '2026-09-29..2026-10-26' })
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/marketing/verification?window=2026-09-29..2026-10-26',
      config
    )
    await getVerificationLedger(config, { window: '90d' })
    expect(apiGet).toHaveBeenLastCalledWith('/api/v2/projects/proj-1/marketing/verification?window=90d', config)
    await expect(getVerificationLedger(config, { window: '30d' })).rejects.toThrow(/Unknown window/)
    await expect(getVerificationLedger(config, { window: 'last week' })).rejects.toThrow(/YYYY-MM-DD\.\.YYYY-MM-DD/)
    expect(apiGet).toHaveBeenCalledTimes(2)
  })

  it('keeps one channel, case-insensitively, and refuses an unknown one', async () => {
    mocked(apiGet).mockResolvedValue(ledger())

    const out = (await getVerificationLedger(config, { channel: 'search' })) as Record<string, any>

    expect(out['rows'].map((r: any) => r.metric)).toEqual(['SEARCH.clicks'])
    expect(out['shown']).toBe(1)
    expect(out['of']).toBe(4)
    await expect(getVerificationLedger(config, { channel: 'nope' })).rejects.toThrow(/Unknown channel/)
  })

  it('onlyProblems keeps what is unverified or has a stale read, and says how many it hid', async () => {
    mocked(apiGet).mockResolvedValue(ledger())

    const out = (await getVerificationLedger(config, { onlyProblems: true })) as Record<string, any>

    expect(out['rows'].map((r: any) => r.metric)).toEqual(['ADS.installs', 'SOCIAL.followers', 'STORE.downloads'])
    expect(out['shown']).toBe(3)
    expect(out['of']).toBe(4)
    // the summary is always the whole ledger's
    expect(out['summary']).toEqual(ledger().summary)
  })

  it('does not report shown and of when nothing was filtered out', async () => {
    mocked(apiGet).mockResolvedValue(ledger())

    const out = (await getVerificationLedger(config, {})) as Record<string, any>

    expect(out).not.toHaveProperty('shown')
  })

  it('keeps window.to null while no channel has data', async () => {
    mocked(apiGet).mockResolvedValue({ window: { from: '2026-09-29' }, rows: [], summary: ledger().summary })

    const out = (await getVerificationLedger(config, {})) as Record<string, any>

    expect(out['window']).toEqual({ from: '2026-09-29', to: null })
    expect(out['rows']).toEqual([])
  })
})

describe('recordPlatformRead', () => {
  const ok = {
    channel: 'ads',
    metric: 'installs',
    from: '2026-09-29',
    to: '2026-10-26',
    value: 23,
    sourceName: 'Apple Ads console',
  }

  it('POSTs the read and returns the recomputed row with its verdict', async () => {
    mocked(apiPost).mockResolvedValue(
      row({
        verdict: 'BACKEND',
        platformRead: {
          id: 'r9',
          value: 23,
          from: '2026-09-29',
          to: '2026-10-26',
          sourceName: 'Apple Ads console',
          recordedAt: '2026-10-29T12:00:00Z',
          conductorValueOverReadDays: 7,
          matches: false,
        },
        trustNote: 'Trust the product backend.',
      })
    )

    const out = (await recordPlatformRead(config, { ...ok, note: ' weekly read ' })) as Record<string, any>

    const [path, body] = mocked(apiPost).mock.calls[0]!
    expect(path).toBe('/api/v2/projects/proj-1/marketing/verification/reads')
    expect(body).toEqual({
      channel: 'ADS',
      metric: 'installs',
      from: '2026-09-29',
      to: '2026-10-26',
      value: 23,
      sourceName: 'Apple Ads console',
      note: 'weekly read',
    })
    expect(out['recorded']).toBe(true)
    expect(out['row']).toMatchObject({ metric: 'ADS.installs', verdict: 'BACKEND', mismatch: true })
    expect(out['row'].read).toMatchObject({ value: 23, conductorValue: 7, matches: false })
  })

  it('passes the server\'s refusal of an unsupported window through unchanged, so the supported lengths are named', async () => {
    const detail =
      'WEB.visitors is a distinct count, so it can only be read over a window of 7, 28 or 90 days; this read covers 14 days'
    mocked(apiPost).mockRejectedValue(new Error(detail))

    await expect(
      recordPlatformRead(config, { ...ok, channel: 'web', metric: 'visitors', from: '2026-10-13', to: '2026-10-26' })
    ).rejects.toThrow(detail)
  })

  it('sends no note when none was given', async () => {
    mocked(apiPost).mockResolvedValue(row())

    await recordPlatformRead(config, ok)

    expect(mocked(apiPost).mock.calls[0]![1]).not.toHaveProperty('note')
  })

  it('refuses a read that cannot be right before calling the API', async () => {
    await expect(recordPlatformRead(config, { ...ok, channel: 'nope' })).rejects.toThrow(/Unknown channel/)
    await expect(recordPlatformRead(config, { ...ok, metric: ' ' })).rejects.toThrow(/metric/)
    await expect(recordPlatformRead(config, { ...ok, from: '29 Sep' })).rejects.toThrow(/from/)
    await expect(recordPlatformRead(config, { ...ok, to: 'yesterday' })).rejects.toThrow(/to/)
    await expect(recordPlatformRead(config, { ...ok, from: '2026-10-26', to: '2026-09-29' })).rejects.toThrow(/first day/)
    await expect(recordPlatformRead(config, { ...ok, value: -1 })).rejects.toThrow(/value/)
    await expect(recordPlatformRead(config, { ...ok, value: Number.NaN })).rejects.toThrow(/value/)
    await expect(recordPlatformRead(config, { ...ok, sourceName: '  ' })).rejects.toThrow(/sourceName/)
    expect(apiPost).not.toHaveBeenCalled()
  })
})

describe('tool registry', () => {
  it('registers both tools and explains the five verdicts and what a read is', () => {
    const ledgerTool = TOOLS.find((t) => t.name === 'get_verification_ledger')!
    const recordTool = TOOLS.find((t) => t.name === 'record_platform_read')!
    expect(ledgerTool).toBeDefined()
    expect(recordTool).toBeDefined()
    for (const verdict of ['BOTH', 'CONNECTOR', 'BACKEND', 'PLATFORM', 'UNCHECKED']) {
      expect(ledgerTool.description).toContain(verdict)
    }
    expect(recordTool.description).toMatch(/platform's own UI or API/)
    expect(recordTool.description).toMatch(/exact day range/)
    // an action names its read-back companion
    expect(recordTool.description).toMatch(/get_verification_ledger/)
  })

  it('requires what a read needs and nothing for the ledger', () => {
    const ledgerSchema = TOOLS.find((t) => t.name === 'get_verification_ledger')!.inputSchema as {
      properties: Record<string, unknown>
      required?: string[]
    }
    const recordSchema = TOOLS.find((t) => t.name === 'record_platform_read')!.inputSchema as {
      properties: Record<string, unknown>
      required: string[]
    }
    expect(ledgerSchema.required ?? []).toEqual([])
    expect(Object.keys(ledgerSchema.properties)).toEqual(['window', 'channel', 'onlyProblems'])
    expect(recordSchema.required).toEqual(['channel', 'metric', 'from', 'to', 'value', 'sourceName'])
    expect(Object.keys(recordSchema.properties)).toEqual([...recordSchema.required, 'note'])
  })
})
