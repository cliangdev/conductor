import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
}))

import { apiGet, apiPost, apiPut } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import {
  getAiVisibility,
  setAiQuestions,
  runAiVisibilityCheck,
  MAX_EXCERPT,
} from '../mcp/tools/ai-visibility.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const base = '/api/v2/projects/proj-1/marketing/ai-visibility'

beforeEach(() => vi.clearAllMocks())

describe('getAiVisibility', () => {
  const payload = {
    brandTerms: ['Rexipe'],
    questions: [
      {
        id: 'q1',
        question: 'Best app to save recipes from TikTok?',
        active: true,
        assistants: [
          {
            assistant: 'claude',
            latest: {
              weekStart: '2026-11-09',
              named: false,
              rankInAnswer: null,
              excerpt: 'Paprika and Mela are popular.',
              model: 'claude-opus-4-8',
            },
            trend: [
              { weekStart: '2026-11-02', named: false },
              { weekStart: '2026-11-09', named: false },
            ],
          },
          {
            assistant: 'openai',
            latest: {
              weekStart: '2026-11-09',
              named: true,
              rankInAnswer: 2,
              excerpt: 'x'.repeat(500),
              model: null,
            },
            trend: [
              { weekStart: '2026-11-02', named: false },
              { weekStart: '2026-11-09', named: true },
            ],
          },
        ],
      },
      { id: 'q2', question: 'Best meal planner?', active: true, assistants: [] },
      {
        id: 'q3',
        question: 'Old question',
        active: false,
        assistants: [
          {
            assistant: 'claude',
            latest: { weekStart: '2026-11-02', named: true, excerpt: 'Rexipe.' },
            trend: [{ weekStart: '2026-11-02', named: true }],
          },
        ],
      },
    ],
    skipped: [{ assistant: 'gemini', reason: 'no credential' }],
  }

  it('reads the AI-visibility endpoint, with the window only when asked', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    await getAiVisibility(config, {})
    expect(apiGet).toHaveBeenLastCalledWith(base, config)
    await getAiVisibility(config, { weeks: 4 })
    expect(apiGet).toHaveBeenLastCalledWith(`${base}?weeks=4`, config)
  })

  it('rejects a window outside 1 to 52 weeks before calling the API', async () => {
    for (const weeks of [0, 53, 1.5, -1, Number.NaN]) {
      await expect(getAiVisibility(config, { weeks })).rejects.toThrow(/weeks/)
    }
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('keeps named false as an explicit false, never dropping it', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getAiVisibility(config, {})) as Record<string, any>
    const claude = out['questions'][0].assistants[0]
    expect(claude).toHaveProperty('named', false)
    expect(claude.trend).toEqual(['2026-11-02:not-named', '2026-11-09:not-named'])
  })

  it('flattens the latest result into each assistant, with the rank only when there is one', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getAiVisibility(config, {})) as Record<string, any>
    const [claude, openai] = out['questions'][0].assistants
    expect(claude).toEqual({
      assistant: 'claude',
      week: '2026-11-09',
      named: false,
      excerpt: 'Paprika and Mela are popular.',
      model: 'claude-opus-4-8',
      trend: ['2026-11-02:not-named', '2026-11-09:not-named'],
    })
    expect(claude).not.toHaveProperty('rank')
    expect(openai.rank).toBe(2)
    expect(openai.trend).toEqual(['2026-11-02:not-named', '2026-11-09:named'])
    expect(openai).not.toHaveProperty('model')
  })

  it('shortens a long excerpt', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getAiVisibility(config, {})) as Record<string, any>
    const excerpt = out['questions'][0].assistants[1].excerpt as string
    expect(excerpt.length).toBe(MAX_EXCERPT + 3)
    expect(excerpt.endsWith('...')).toBe(true)
  })

  it('shows a question not yet checked with an empty assistant list, and flags an inactive one', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getAiVisibility(config, {})) as Record<string, any>
    expect(out['questions'][1]).toEqual({ id: 'q2', question: 'Best meal planner?', assistants: [] })
    expect(out['questions'][2].active).toBe(false)
    expect(out['questions'][0]).not.toHaveProperty('active')
  })

  it('reports assistants without a credential as skipped and the brand terms in force', async () => {
    mocked(apiGet).mockResolvedValue(payload)
    const out = (await getAiVisibility(config, {})) as Record<string, any>
    expect(out['brandTerms']).toEqual(['Rexipe'])
    expect(out['skipped']).toEqual([{ assistant: 'gemini', reason: 'no credential' }])
  })

  it('leaves skipped out when every assistant has a credential', async () => {
    mocked(apiGet).mockResolvedValue({ ...payload, skipped: [] })
    const out = (await getAiVisibility(config, {})) as Record<string, any>
    expect(out).not.toHaveProperty('skipped')
  })

  it('tolerates a response with missing sections', async () => {
    mocked(apiGet).mockResolvedValue({})
    const out = (await getAiVisibility(config, {})) as Record<string, any>
    expect(out['brandTerms']).toEqual([])
    expect(out['questions']).toEqual([])
  })
})

describe('setAiQuestions', () => {
  const stored = {
    questions: [
      { id: 'q1', question: 'Best recipe app?', active: true },
      { id: 'q2', question: 'Old one', active: false },
    ],
  }

  it('PUTs the question list and returns the active questions and the dropped ones', async () => {
    mocked(apiPut).mockResolvedValue(stored)
    const out = await setAiQuestions(config, { questions: ['  Best recipe app? '] })
    expect(apiPut).toHaveBeenCalledTimes(1)
    expect(mocked(apiPut).mock.calls[0]).toEqual([`${base}/questions`, { questions: ['Best recipe app?'] }, config])
    expect(out).toEqual({ questions: ['Best recipe app?'], inactive: ['Old one'] })
  })

  it('leaves the brand terms alone when none are given', async () => {
    mocked(apiPut).mockResolvedValue(stored)
    await setAiQuestions(config, { questions: ['Best recipe app?'] })
    expect(mocked(apiPut).mock.calls.map((c) => c[0])).toEqual([`${base}/questions`])
  })

  it('also replaces the brand terms when given, and returns the terms now in force', async () => {
    mocked(apiPut)
      .mockResolvedValueOnce(stored)
      .mockResolvedValueOnce({ brandTerms: ['Rexipe', 'Rexipe app'] })
    const out = await setAiQuestions(config, { questions: ['Best recipe app?'], brandTerms: ['Rexipe', 'Rexipe app'] })
    expect(mocked(apiPut).mock.calls[1]).toEqual([`${base}/brand-terms`, { brandTerms: ['Rexipe', 'Rexipe app'] }, config])
    expect(out['brandTerms']).toEqual(['Rexipe', 'Rexipe app'])
  })

  it('passes an empty brand-term list through, which goes back to the workspace name', async () => {
    mocked(apiPut).mockResolvedValueOnce(stored).mockResolvedValueOnce({ brandTerms: ['P'] })
    const out = await setAiQuestions(config, { questions: ['Best recipe app?'], brandTerms: [] })
    expect(mocked(apiPut).mock.calls[1]![1]).toEqual({ brandTerms: [] })
    expect(out['brandTerms']).toEqual(['P'])
  })

  it('passes an empty question list through, which switches every question off', async () => {
    mocked(apiPut).mockResolvedValue({ questions: [{ id: 'q1', question: 'Best recipe app?', active: false }] })
    const out = await setAiQuestions(config, { questions: [] })
    expect(mocked(apiPut).mock.calls[0]![1]).toEqual({ questions: [] })
    expect(out).toEqual({ questions: [], inactive: ['Best recipe app?'] })
  })

  it('validates everything before changing anything', async () => {
    const bad: Array<[Parameters<typeof setAiQuestions>[1], RegExp]> = [
      [{ questions: undefined as never }, /questions/],
      [{ questions: ['ok', ' '] }, /blank/],
      [{ questions: ['q'.repeat(401)] }, /400/],
      [{ questions: Array.from({ length: 21 }, (_, i) => `Question ${i}?`) }, /20/],
      [{ questions: ['ok'], brandTerms: 'Rexipe' as never }, /brandTerms/],
      [{ questions: ['ok'], brandTerms: Array.from({ length: 11 }, (_, i) => `t${i}`) }, /10/],
      [{ questions: ['ok'], brandTerms: ['x'.repeat(81)] }, /80/],
    ]
    for (const [params, message] of bad) {
      await expect(setAiQuestions(config, params)).rejects.toThrow(message)
    }
    expect(apiPut).not.toHaveBeenCalled()
  })
})

describe('runAiVisibilityCheck', () => {
  it('POSTs the run and returns a compact summary', async () => {
    mocked(apiPost).mockResolvedValue({
      weekStart: '2026-11-02',
      recorded: 3,
      alreadyRecorded: 1,
      skipped: [{ assistant: 'gemini', reason: 'no credential' }],
      errors: [{ assistant: 'claude', question: 'Best recipe app?', reason: 'Claude request failed (429)' }],
    })
    const out = await runAiVisibilityCheck(config)
    expect(mocked(apiPost).mock.calls[0]![0]).toBe(`${base}/run`)
    expect(out).toEqual({
      week: '2026-11-02',
      recorded: 3,
      alreadyRecorded: 1,
      skipped: [{ assistant: 'gemini', reason: 'no credential' }],
      errors: [{ assistant: 'claude', question: 'Best recipe app?', reason: 'Claude request failed (429)' }],
    })
  })

  it('keeps zero counts and leaves empty skipped and errors out', async () => {
    mocked(apiPost).mockResolvedValue({ weekStart: '2026-11-02', recorded: 0, alreadyRecorded: 4, skipped: [], errors: [] })
    const out = await runAiVisibilityCheck(config)
    expect(out).toEqual({ week: '2026-11-02', recorded: 0, alreadyRecorded: 4 })
  })
})

describe('tool registration', () => {
  const names = ['get_ai_visibility', 'set_ai_questions', 'run_ai_visibility_check']
  const tool = (name: string) => TOOLS.find((t) => t.name === name)!

  it('registers all three tools once', () => {
    for (const n of names) expect(TOOLS.filter((t) => t.name === n)).toHaveLength(1)
  })

  it('declares the required params of each tool', () => {
    const required = (n: string) => (tool(n).inputSchema as { required?: string[] }).required ?? []
    expect(required('get_ai_visibility')).toEqual([])
    expect(required('set_ai_questions')).toEqual(['questions'])
    expect(required('run_ai_visibility_check')).toEqual([])
  })

  it('every documented parameter is one the tool function reads (no false promises)', () => {
    const props = (n: string) => Object.keys((tool(n).inputSchema as { properties: object }).properties)
    expect(props('get_ai_visibility')).toEqual(['weeks'])
    expect(props('set_ai_questions')).toEqual(['questions', 'brandTerms'])
    expect(props('run_ai_visibility_check')).toEqual([])
  })

  it('names the read-back companion: get_ai_visibility after each mutating or dispatching tool', () => {
    expect(tool('set_ai_questions').description).toMatch(/get_ai_visibility/)
    expect(tool('run_ai_visibility_check').description).toMatch(/get_ai_visibility/)
  })

  it('says a run needs the Admin role and that an assistant without a credential is skipped', () => {
    expect(tool('run_ai_visibility_check').description).toMatch(/Admin/)
    expect(tool('get_ai_visibility').description).toMatch(/skipped/)
  })

  it('keeps descriptions lean', () => {
    for (const n of names) expect(tool(n).description.length, n).toBeLessThan(700)
  })
})
