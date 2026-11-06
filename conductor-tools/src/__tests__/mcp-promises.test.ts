import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  isClientError: (err: unknown) => err instanceof Error && /API error 4\d\d\b/.test(err.message),
}))

import { apiGet, apiPut, apiDelete } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import { tagMessage, untagMessage, listPromises, formatRate } from '../mcp/tools/promises.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const PROMISES = '/api/v2/projects/proj-1/marketing/promises'
const TAGS = '/api/v2/projects/proj-1/marketing/message-tags'

beforeEach(() => vi.clearAllMocks())

const promise = (over: Record<string, unknown> = {}) => ({
  message: 'save-from-tiktok',
  targets: { KEYWORD: 2, POST: 1 },
  smallN: false,
  ...over,
})

describe('tool registry', () => {
  const tool = (name: string) => TOOLS.find((t) => t.name === name)

  it('registers the three promise tools', () => {
    expect(tool('tag_message')).toBeDefined()
    expect(tool('untag_message')).toBeDefined()
    expect(tool('list_promises')).toBeDefined()
  })

  it('requires message, targetKind and targetRef to tag, and lists the five kinds', () => {
    const t = tool('tag_message')!
    expect((t.inputSchema as { required: string[] }).required).toEqual(['message', 'targetKind', 'targetRef'])
    const kind = (t.inputSchema as { properties: Record<string, { enum?: string[] }> }).properties['targetKind']!
    expect(kind.enum).toEqual(['CREATIVE', 'PRODUCT_PAGE', 'KEYWORD', 'PAYWALL_HEADLINE', 'POST'])
  })

  it('names list_promises as the read-back of both mutating tools and keeps descriptions lean', () => {
    expect(tool('tag_message')!.description).toContain('list_promises')
    expect(tool('untag_message')!.description).toContain('list_promises')
    for (const name of ['tag_message', 'untag_message', 'list_promises']) {
      expect(tool(name)!.description!.length).toBeLessThan(900)
    }
  })

  it('takes no required parameter to list', () => {
    expect((tool('list_promises')!.inputSchema as { required?: string[] }).required ?? []).toEqual([])
  })
})

describe('formatRate', () => {
  it('reads a rate with its range, n and small-sample flag', () => {
    expect(formatRate({ value: 1 / 6, n: 6, low: 0.03, high: 0.564, smallN: true })).toBe(
      '17% (3–56%, n=6, small sample)'
    )
  })

  it('leaves the flag off a sample of 20 or more', () => {
    expect(formatRate({ value: 0.15, n: 2000, low: 0.135, high: 0.1663, smallN: false })).toBe('15% (14–17%, n=2000)')
  })

  it('is null when there is no rate, never 0%', () => {
    expect(formatRate(null)).toBeNull()
    expect(formatRate(undefined)).toBeNull()
  })

  it('shows a reported zero as 0%', () => {
    expect(formatRate({ value: 0, n: 10, low: 0, high: 0.2775, smallN: true })).toBe('0% (0–28%, n=10, small sample)')
  })
})

describe('tag_message', () => {
  it('upserts the tag by target and returns it with the read-back step', async () => {
    mocked(apiPut).mockResolvedValue({
      id: 't1',
      message: 'save-from-tiktok',
      targetKind: 'KEYWORD',
      targetRef: '42',
      label: 'meal planner',
      createdAt: '2026-11-05T10:00:00Z',
    })

    const result = await tagMessage(config, {
      message: 'Save-From-TikTok',
      targetKind: 'keyword',
      targetRef: ' 42 ',
      label: 'meal planner',
    })

    expect(apiPut).toHaveBeenCalledWith(
      TAGS,
      { message: 'Save-From-TikTok', targetKind: 'KEYWORD', targetRef: '42', label: 'meal planner' },
      config
    )
    expect(result['message']).toBe('save-from-tiktok')
    expect(result['targetKind']).toBe('KEYWORD')
    expect(result['targetRef']).toBe('42')
    expect(String(result['nextStep'])).toContain('list_promises')
  })

  it('sends no label when none is given', async () => {
    mocked(apiPut).mockResolvedValue({ id: 't1', message: 'm', targetKind: 'POST', targetRef: 'p1', createdAt: 'x' })

    await tagMessage(config, { message: 'm', targetKind: 'POST', targetRef: 'p1' })

    expect(mocked(apiPut).mock.calls[0]![1]).toEqual({ message: 'm', targetKind: 'POST', targetRef: 'p1', label: undefined })
  })

  it('refuses a blank message, an unknown kind and a blank ref before calling the API', async () => {
    await expect(tagMessage(config, { message: ' ', targetKind: 'KEYWORD', targetRef: '1' })).rejects.toThrow(/message/)
    await expect(tagMessage(config, { message: 'm', targetKind: 'BILLBOARD', targetRef: '1' })).rejects.toThrow(
      /targetKind/
    )
    await expect(tagMessage(config, { message: 'm', targetKind: 'KEYWORD', targetRef: '  ' })).rejects.toThrow(
      /targetRef/
    )
    await expect(
      tagMessage(config, { message: 'x'.repeat(121), targetKind: 'KEYWORD', targetRef: '1' })
    ).rejects.toThrow(/message/)
    await expect(
      tagMessage(config, { message: 'm', targetKind: 'KEYWORD', targetRef: '1', label: 'x'.repeat(201) })
    ).rejects.toThrow(/label/)
    expect(apiPut).not.toHaveBeenCalled()
  })

  it('relays the backend refusal', async () => {
    mocked(apiPut).mockRejectedValue(new Error('Post not found: p9'))
    await expect(tagMessage(config, { message: 'm', targetKind: 'POST', targetRef: 'p9' })).rejects.toThrow(
      'Post not found: p9'
    )
  })
})

describe('untag_message', () => {
  it('deletes by target with an encoded query and says it is safe to repeat', async () => {
    mocked(apiDelete).mockResolvedValue(undefined)

    const result = await untagMessage(config, { targetKind: 'paywall_headline', targetRef: 'Cook smarter: try it free' })

    expect(apiDelete).toHaveBeenCalledWith(
      `${TAGS}?targetKind=PAYWALL_HEADLINE&targetRef=${encodeURIComponent('Cook smarter: try it free')}`,
      config
    )
    expect(result['targetKind']).toBe('PAYWALL_HEADLINE')
    expect(String(result['nextStep'])).toContain('list_promises')
  })

  it('refuses an unknown kind and a blank ref before calling the API', async () => {
    await expect(untagMessage(config, { targetKind: 'BILLBOARD', targetRef: '1' })).rejects.toThrow(/targetKind/)
    await expect(untagMessage(config, { targetKind: 'KEYWORD', targetRef: '' })).rejects.toThrow(/targetRef/)
    expect(apiDelete).not.toHaveBeenCalled()
  })
})

describe('list_promises', () => {
  it('asks for the default window when none is given', async () => {
    mocked(apiGet).mockResolvedValue({ from: '2026-10-05', to: '2026-11-01', promises: [] })

    const result = await listPromises(config, {})

    expect(apiGet).toHaveBeenCalledWith(PROMISES, config)
    expect(result['from']).toBe('2026-10-05')
    expect(result['to']).toBe('2026-11-01')
    expect(result['promises']).toEqual([])
  })

  it('passes the window through', async () => {
    mocked(apiGet).mockResolvedValue({ from: '2026-09-29', to: '2026-10-26', promises: [] })

    await listPromises(config, { from: '2026-09-29', to: '2026-10-26' })

    expect(apiGet).toHaveBeenCalledWith(`${PROMISES}?from=2026-09-29&to=2026-10-26`, config)
  })

  it('refuses a malformed date before calling the API', async () => {
    await expect(listPromises(config, { from: '29/09/2026' })).rejects.toThrow(/from/)
    await expect(listPromises(config, { to: 'yesterday' })).rejects.toThrow(/to/)
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('shows installs, intent mix, trial rate and D7 with ranges at small n', async () => {
    mocked(apiGet).mockResolvedValue({
      from: '2026-09-29',
      to: '2026-10-26',
      promises: [
        promise({
          installs: 31,
          installsNote: 'note',
          intentMix: [
            { pick: 'save_recipes', count: 12, share: 0.48 },
            { pick: 'plan_meals', count: 13, share: 0.52 },
          ],
          intentN: 25,
          trialRate: { value: 1 / 6, n: 6, low: 0.03, high: 0.564, smallN: true },
          d7: { value: 0.2, n: 200, low: 0.15, high: 0.26, smallN: false },
          smallN: true,
          outcomesNote: 'why',
        }),
      ],
    })

    const result = await listPromises(config, {})

    const [p] = result['promises'] as Record<string, unknown>[]
    expect(p!['message']).toBe('save-from-tiktok')
    expect(p!['targets']).toEqual({ KEYWORD: 2, POST: 1 })
    expect(p!['installs']).toBe(31)
    expect(p!['installsNote']).toBe('note')
    expect(p!['intentMix']).toEqual(['save_recipes 48%', 'plan_meals 52%'])
    expect(p!['intentN']).toBe(25)
    expect(p!['trialRate']).toBe('17% (3–56%, n=6, small sample)')
    expect(p!['d7']).toBe('20% (15–26%, n=200)')
    expect(p!['smallSample']).toBe(true)
    expect(p!['outcomesNote']).toBe('why')
  })

  it('keeps absent figures null, never 0', async () => {
    mocked(apiGet).mockResolvedValue({ from: 'a', to: 'b', promises: [promise()] })

    const result = await listPromises(config, {})

    const [p] = result['promises'] as Record<string, unknown>[]
    expect(p!['installs']).toBeNull()
    expect(p!['intentMix']).toBeNull()
    expect(p!['trialRate']).toBeNull()
    expect(p!['d7']).toBeNull()
    expect(p!['smallSample']).toBe(false)
    expect(p).not.toHaveProperty('installsNote')
    expect(p).not.toHaveProperty('outcomesNote')
  })

  it('says how to start when nothing is tagged', async () => {
    mocked(apiGet).mockResolvedValue({ from: 'a', to: 'b', promises: [] })

    const result = await listPromises(config, {})

    expect(String(result['note'])).toContain('tag_message')
  })
})
