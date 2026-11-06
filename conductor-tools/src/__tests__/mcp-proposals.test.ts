import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPostWithStatus: vi.fn(),
}))

import { apiPost, apiPostWithStatus } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import { proposeMarketingChange, installWeeklyGrowthWorkflow } from '../mcp/tools/proposals.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

const PROPOSALS = '/api/v2/projects/proj-1/marketing/proposals'
const INSTALL = '/api/v2/projects/proj-1/marketing/weekly-growth/install'
const WINDOW = '2026-09-29..2026-10-26'

beforeEach(() => vi.clearAllMocks())

const ok = () => ({
  title: '  Raise the Search bid ',
  type: 'ADS',
  hypothesis: ' A higher bid wins more brand searches. ',
  reviewOn: '2026-11-26',
  signals: [
    { metricRef: 'ADS.installs', window: WINDOW, value: 23, note: ' tap installs ' },
    { metricRef: 'STORE.downloads', window: '28d', value: 30 },
  ],
})

describe('proposeMarketingChange', () => {
  it('POSTs the proposal trimmed, with no empty note or run id, and reports a PLANNED change awaiting a person', async () => {
    mocked(apiPost).mockResolvedValue({ workItemId: 'wi-1', displayId: 'RE-12', proposalsThisWeek: 2, weeklyLimit: 3 })

    const out = (await proposeMarketingChange(config, ok())) as Record<string, any>

    const [url, body] = mocked(apiPost).mock.calls[0]!
    expect(url).toBe(PROPOSALS)
    expect(body).toEqual({
      title: 'Raise the Search bid',
      type: 'ADS',
      hypothesis: 'A higher bid wins more brand searches.',
      reviewOn: '2026-11-26',
      signals: [
        { metricRef: 'ADS.installs', window: WINDOW, value: 23, note: 'tap installs' },
        { metricRef: 'STORE.downloads', window: '28d', value: 30 },
      ],
    })
    expect(body.signals[1]).not.toHaveProperty('note')
    expect(body).not.toHaveProperty('runId')
    expect(out).toMatchObject({
      proposed: true,
      workItemId: 'wi-1',
      displayId: 'RE-12',
      status: 'PLANNED',
      proposalsThisWeek: 2,
      weeklyLimit: 3,
    })
    expect(out['next']).toMatch(/person/)
    expect(out['next']).toMatch(/live/)
  })

  it('sends the run id when there is one', async () => {
    mocked(apiPost).mockResolvedValue({ workItemId: 'wi-1', displayId: 'RE-12', proposalsThisWeek: 1, weeklyLimit: 3 })

    await proposeMarketingChange(config, { ...ok(), runId: ' run-7 ' })

    expect(mocked(apiPost).mock.calls[0]![1]).toMatchObject({ runId: 'run-7' })
  })

  it('refuses a proposal that cannot be right before calling the API', async () => {
    const bad = (over: Record<string, unknown>) => proposeMarketingChange(config, { ...ok(), ...over } as never)

    await expect(bad({ title: '  ' })).rejects.toThrow(/title/)
    await expect(bad({ type: '' })).rejects.toThrow(/type/)
    await expect(bad({ hypothesis: ' ' })).rejects.toThrow(/hypothesis/)
    await expect(bad({ reviewOn: 'next month' })).rejects.toThrow(/reviewOn/)
    await expect(bad({ reviewOn: '2026-02-31' })).rejects.toThrow(/reviewOn/)
    await expect(bad({ signals: undefined })).rejects.toThrow(/exactly 2 signals/)
    await expect(bad({ signals: [ok().signals[0]] })).rejects.toThrow(/exactly 2 signals/)
    await expect(bad({ signals: [...ok().signals, { metricRef: 'WEB.sessions', window: '28d', value: 68 }] })).rejects.toThrow(
      /exactly 2 signals/
    )
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('refuses a signal with no usable metric, window or value, never reading a missing value as zero', async () => {
    const withSignal = (i: number, over: Record<string, unknown>) => {
      const signals = ok().signals.map((s, idx) => (idx === i ? { ...s, ...over } : s))
      return proposeMarketingChange(config, { ...ok(), signals } as never)
    }

    await expect(withSignal(0, { metricRef: 'installs' })).rejects.toThrow(/signals\[0\].metricRef/)
    await expect(withSignal(1, { metricRef: ' ' })).rejects.toThrow(/signals\[1\].metricRef/)
    await expect(withSignal(0, { window: 'last month' })).rejects.toThrow(/signals\[0\].window/)
    await expect(withSignal(0, { value: undefined })).rejects.toThrow(/signals\[0\].value/)
    await expect(withSignal(0, { value: null })).rejects.toThrow(/signals\[0\].value/)
    await expect(withSignal(1, { value: '23' })).rejects.toThrow(/signals\[1\].value/)
    await expect(withSignal(1, { value: Number.NaN })).rejects.toThrow(/signals\[1\].value/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('refuses the same metric twice, even with the channel written differently', async () => {
    const signals = [
      { metricRef: 'ADS.installs', window: '28d', value: 23 },
      { metricRef: 'ads.installs', window: WINDOW, value: 23 },
    ]

    await expect(proposeMarketingChange(config, { ...ok(), signals })).rejects.toThrow(/different metrics/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('lets the server name a refused metric and its verdict', async () => {
    mocked(apiPost).mockRejectedValue(new Error('ADS.installs is UNCHECKED: no second source'))

    await expect(proposeMarketingChange(config, ok())).rejects.toThrow('ADS.installs is UNCHECKED: no second source')
  })
})

describe('installWeeklyGrowthWorkflow', () => {
  it('reports a new, disabled workflow and what to do next', async () => {
    mocked(apiPostWithStatus).mockResolvedValue({
      status: 201,
      data: { workflowId: 'wf-1', name: 'Weekly growth review', enabled: false, created: true },
    })

    const out = (await installWeeklyGrowthWorkflow(config)) as Record<string, any>

    expect(mocked(apiPostWithStatus).mock.calls[0]![0]).toBe(INSTALL)
    expect(out).toMatchObject({ installed: true, workflowId: 'wf-1', name: 'Weekly growth review', enabled: false })
    expect(out['next']).toMatch(/disabled/)
    expect(out['next']).toMatch(/runtime target/)
    expect(out['next']).toMatch(/enable/)
  })

  it('reports an existing workflow as already there and leaves its state alone', async () => {
    mocked(apiPostWithStatus).mockResolvedValue({
      status: 200,
      data: { workflowId: 'wf-1', name: 'Weekly growth review', enabled: true, created: false },
    })

    const out = (await installWeeklyGrowthWorkflow(config)) as Record<string, any>

    expect(out).toMatchObject({ installed: false, workflowId: 'wf-1', enabled: true })
    expect(out['next']).toMatch(/already/)
    expect(out['next']).not.toMatch(/runtime target/)
  })

  it('says an already-installed workflow that is still off is waiting to be enabled', async () => {
    mocked(apiPostWithStatus).mockResolvedValue({
      status: 200,
      data: { workflowId: 'wf-1', name: 'Weekly growth review', enabled: false, created: false },
    })

    const out = (await installWeeklyGrowthWorkflow(config)) as Record<string, any>

    expect(out['next']).toMatch(/already/)
    expect(out['next']).toMatch(/disabled/)
  })
})

describe('tool registry', () => {
  const propose = () => TOOLS.find((t) => t.name === 'propose_marketing_change')!
  const install = () => TOOLS.find((t) => t.name === 'install_weekly_growth_workflow')!

  it('registers both, and the propose tool says it refuses unverified evidence and more than 3 a week and that a person approves', () => {
    expect(propose()).toBeDefined()
    expect(install()).toBeDefined()
    expect(propose().description).toMatch(/refuses/)
    expect(propose().description).toMatch(/UNCHECKED/)
    expect(propose().description).toMatch(/MISMATCH/)
    expect(propose().description).toMatch(/3 a week|3 per week|three a week/)
    expect(propose().description).toMatch(/human|person/)
    expect(propose().description).toMatch(/LIVE/)
    // an action names its read-back companion
    expect(propose().description).toMatch(/get_marketing_report|get_work_item/)
    expect(install().description).toMatch(/disabled/)
    expect(install().description).toMatch(/runtime target/)
    expect(install().description).toMatch(/Admin/)
  })

  it('requires what a proposal needs, and nothing to install', () => {
    const schema = propose().inputSchema as { properties: Record<string, any>; required: string[] }
    expect(schema.required).toEqual(['title', 'type', 'hypothesis', 'reviewOn', 'signals'])
    expect(Object.keys(schema.properties)).toEqual([...schema.required, 'runId'])
    expect(schema.properties['signals'].minItems).toBe(2)
    expect(schema.properties['signals'].maxItems).toBe(2)
    expect(schema.properties['signals'].items.required).toEqual(['metricRef', 'window', 'value'])
    const installSchema = install().inputSchema as { properties: Record<string, unknown>; required?: string[] }
    expect(Object.keys(installSchema.properties)).toEqual([])
    expect(installSchema.required ?? []).toEqual([])
  })

  it('every tool the weekly workflow template is allowed to call is a registered tool', () => {
    const here = path.dirname(fileURLToPath(import.meta.url))
    const template = path.join(here, '..', '..', '..', 'conductor-backend', 'src', 'main', 'resources', 'marketing', 'weekly-growth.yaml')
    const yaml = fs.readFileSync(template, 'utf8')
    const allowed = /allowed_tools:\s*"([^"]+)"/.exec(yaml)?.[1]
    expect(allowed, 'weekly-growth.yaml has an allowed_tools list').toBeDefined()
    const names = allowed!.split(',').map((t) => t.trim().replace(/^mcp__conductor__/, ''))
    expect(names).toContain('propose_marketing_change')
    const registered = new Set(TOOLS.map((t) => t.name))
    expect(names.filter((n) => !registered.has(n))).toEqual([])
  })
})
