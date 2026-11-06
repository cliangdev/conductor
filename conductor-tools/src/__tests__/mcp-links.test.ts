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

import { apiGet, apiPost } from '../mcp/api.js'
import { TOOLS } from '../mcp/index.js'
import { createTrackedLink, listTrackedLinks } from '../mcp/tools/links.js'
import { getPostStatus } from '../mcp/tools/posts.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}
const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>
const BASE = '/api/v2/projects/proj-1/marketing/links'

beforeEach(() => vi.clearAllMocks())

const link = (over: Record<string, unknown> = {}) => ({
  id: 'l1',
  kind: 'BIO',
  family: 'social-bios',
  token: 'ig-bio',
  url: 'https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio',
  label: 'Instagram bio',
  createdAt: '2026-11-05T10:00:00Z',
  ...over,
})

describe('tool registry', () => {
  const tool = (name: string) => TOOLS.find((t) => t.name === name)

  it('registers create_tracked_link and list_tracked_links', () => {
    expect(tool('create_tracked_link')).toBeDefined()
    expect(tool('list_tracked_links')).toBeDefined()
  })

  it('requires kind, family and token to create a link, and names its read-back companion', () => {
    const t = tool('create_tracked_link')!
    expect((t.inputSchema as { required: string[] }).required).toEqual(['kind', 'family', 'token'])
    expect(t.description).toContain('list_tracked_links')
  })

  it('lists the five kinds and keeps descriptions lean', () => {
    const t = tool('create_tracked_link')!
    const kind = (t.inputSchema as { properties: Record<string, { enum?: string[] }> }).properties['kind']!
    expect(kind.enum).toEqual(['POST', 'BIO', 'CREATOR', 'EMAIL', 'OTHER'])
    expect(t.description!.length).toBeLessThan(900)
    expect(tool('list_tracked_links')!.description!.length).toBeLessThan(900)
  })
})

describe('create_tracked_link', () => {
  it('returns the App Store link the backend built', async () => {
    mocked(apiPost).mockResolvedValue(link())

    const result = await createTrackedLink(config, {
      kind: 'BIO',
      family: 'social-bios',
      token: 'ig-bio',
      label: 'Instagram bio',
    })

    expect(apiPost).toHaveBeenCalledWith(
      BASE,
      { kind: 'BIO', family: 'social-bios', token: 'ig-bio', label: 'Instagram bio', workItemId: undefined },
      config
    )
    expect(result['url']).toBe('https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio')
    expect(result['token']).toBe('ig-bio')
    expect(result['family']).toBe('social-bios')
    expect(result['kind']).toBe('BIO')
    expect(String(result['nextStep'])).toContain('list_tracked_links')
  })

  it('sends the Work Item a link belongs to', async () => {
    mocked(apiPost).mockResolvedValue(link({ kind: 'CREATOR', family: 'creators', token: 'cr-ana', workItemId: 'w1' }))

    const result = await createTrackedLink(config, {
      kind: 'creator',
      family: 'creators',
      token: 'cr-ana',
      workItemId: 'w1',
    })

    expect(mocked(apiPost).mock.calls[0]![1]).toMatchObject({ kind: 'CREATOR', workItemId: 'w1' })
    expect(result['workItemId']).toBe('w1')
  })

  it.each([
    ['uppercase letters', 'Bio'],
    ['a leading dash', '-bio'],
    ['an underscore', 'bio_1'],
    ['a space', 'bio 1'],
    ['more than 40 characters', 'a'.repeat(41)],
    ['nothing', ''],
  ])('refuses a token with %s before calling the API', async (_why, token) => {
    await expect(createTrackedLink(config, { kind: 'BIO', family: 'bios', token })).rejects.toThrow(/token/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('accepts a 40 character token', async () => {
    mocked(apiPost).mockResolvedValue(link({ token: 'a'.repeat(40) }))
    await createTrackedLink(config, { kind: 'BIO', family: 'bios', token: 'a'.repeat(40) })
    expect(apiPost).toHaveBeenCalledTimes(1)
  })

  it('refuses a malformed family, an unknown kind and an over-long label before calling the API', async () => {
    await expect(createTrackedLink(config, { kind: 'BIO', family: 'Bad Family', token: 't' })).rejects.toThrow(/family/)
    await expect(createTrackedLink(config, { kind: 'BILLBOARD', family: 'f', token: 't' })).rejects.toThrow(/kind/)
    await expect(
      createTrackedLink(config, { kind: 'BIO', family: 'f', token: 't', label: 'x'.repeat(201) })
    ).rejects.toThrow(/label/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('needs the Post for a POST link', async () => {
    await expect(createTrackedLink(config, { kind: 'POST', family: 'posts', token: 'p-1' })).rejects.toThrow(
      /workItemId/
    )
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('lets the backend refuse a project with no provider token and says what it said', async () => {
    mocked(apiPost).mockRejectedValue(new Error('Add the App Store Connect provider token'))
    await expect(createTrackedLink(config, { kind: 'BIO', family: 'bios', token: 'ig-bio' })).rejects.toThrow(
      'Add the App Store Connect provider token'
    )
  })
})

describe('list_tracked_links', () => {
  it('lists the newest links compactly with the total', async () => {
    mocked(apiGet).mockResolvedValue({ links: [link(), link({ id: 'l2', token: 'tt-bio', label: null })], total: 2 })

    const result = await listTrackedLinks(config, {})

    expect(apiGet).toHaveBeenCalledWith(`${BASE}?limit=50`, config)
    expect(result['total']).toBe(2)
    expect(result['links']).toEqual([
      {
        id: 'l1',
        kind: 'BIO',
        family: 'social-bios',
        token: 'ig-bio',
        url: 'https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio',
        label: 'Instagram bio',
      },
      {
        id: 'l2',
        kind: 'BIO',
        family: 'social-bios',
        token: 'tt-bio',
        url: 'https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio',
      },
    ])
    expect(result['performance']).toBeUndefined()
  })

  it('filters by kind and Work Item and says when more links exist than are shown', async () => {
    mocked(apiGet).mockResolvedValue({ links: [link({ workItemId: 'w1' })], total: 80 })

    const result = await listTrackedLinks(config, { kind: 'bio', workItemId: 'w 1' })

    expect(apiGet).toHaveBeenCalledWith(`${BASE}?kind=BIO&workItemId=w%201&limit=50`, config)
    expect((result['links'] as Array<Record<string, unknown>>)[0]!['workItemId']).toBe('w1')
    expect(String(result['note'])).toContain('80')
  })

  it('refuses an unknown kind without calling the API', async () => {
    await expect(listTrackedLinks(config, { kind: 'BILLBOARD' })).rejects.toThrow(/kind/)
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('adds family performance and keeps a family Apple reported nothing for as null, never 0', async () => {
    mocked(apiGet).mockImplementation(async (path: string) => {
      if (path.startsWith(`${BASE}/performance`)) {
        return {
          from: '2026-09-29',
          to: '2026-10-26',
          families: [
            { family: 'posts', kind: 'POST', tokens: 12, downloads: 31, belowThreshold: false, views: 4800 },
            {
              family: 'social-bios',
              kind: 'BIO',
              tokens: 2,
              belowThreshold: true,
              note: "below Apple's reporting threshold",
            },
          ],
        }
      }
      return { links: [link()], total: 1 }
    })

    const result = await listTrackedLinks(config, { withPerformance: true })

    const performance = result['performance'] as { from: string; to: string; families: Array<Record<string, unknown>> }
    expect(performance.from).toBe('2026-09-29')
    expect(performance.to).toBe('2026-10-26')
    expect(performance.families).toEqual([
      { family: 'posts', kind: 'POST', tokens: 12, downloads: 31, belowThreshold: false, views: 4800 },
      {
        family: 'social-bios',
        kind: 'BIO',
        tokens: 2,
        downloads: null,
        belowThreshold: true,
        note: "below Apple's reporting threshold",
        views: null,
      },
    ])
  })

  it('narrows the performance to the families of the listed links when a filter is given', async () => {
    mocked(apiGet).mockImplementation(async (path: string) => {
      if (path.startsWith(`${BASE}/performance`)) {
        return {
          from: '2026-09-29',
          to: '2026-10-26',
          families: [
            { family: 'posts', kind: 'POST', tokens: 12, downloads: 31, belowThreshold: false, views: 4800 },
            { family: 'creators', kind: 'CREATOR', tokens: 2, downloads: 9, belowThreshold: false },
          ],
        }
      }
      return { links: [link({ kind: 'CREATOR', family: 'creators', token: 'cr-ana' })], total: 1 }
    })

    const result = await listTrackedLinks(config, { kind: 'CREATOR', withPerformance: true })

    const performance = result['performance'] as { families: Array<{ family: string }> }
    expect(performance.families.map((f) => f.family)).toEqual(['creators'])
  })
})

describe('a Post carries its tracked link', () => {
  const route = (links: unknown) =>
    mocked(apiGet).mockImplementation(async (path: string) => {
      if (path.startsWith('/api/v2/projects/proj-1/work-items/w1/publish-preflight')) {
        return {
          publishing: true,
          ready: true,
          blockers: [],
          warnings: [],
          consent: { required: false, verdict: 'NOT_REQUIRED' },
          review: { gated: true, assignedReviewers: 0, satisfied: false },
        }
      }
      if (path.startsWith('/api/v2/projects/proj-1/work-items/w1/publish-targets')) return []
      if (path.startsWith('/api/v2/projects/proj-1/work-items/w1/assets')) return []
      if (path.startsWith('/api/v2/projects/proj-1/work-items/w1')) return { id: 'w1', displayId: 'MK-8', status: 'DRAFT' }
      if (path.startsWith(BASE)) {
        if (links instanceof Error) throw links
        return links
      }
      throw new Error(`unexpected GET ${path}`)
    })

  it('adds the link url to the Post status when one exists', async () => {
    route({ links: [link({ kind: 'POST', family: 'posts', token: 'post-mk-8', workItemId: 'w1' })], total: 1 })

    const result = await getPostStatus({ postId: 'w1' }, config)

    expect(result['trackedLink']).toBe('https://apps.apple.com/app/apple-store/id1?pt=2&ct=ig-bio')
    expect(apiGet).toHaveBeenCalledWith(`${BASE}?kind=POST&workItemId=w1&limit=1`, config)
  })

  it('leaves the field out when the Post has no link', async () => {
    route({ links: [], total: 0 })

    const result = await getPostStatus({ postId: 'w1' }, config)

    expect(result).not.toHaveProperty('trackedLink')
    expect(result['displayId']).toBe('MK-8')
  })

  it('never fails the status read when the links endpoint does', async () => {
    route(new Error('API error 404: not found'))

    const result = await getPostStatus({ postId: 'w1' }, config)

    expect(result).not.toHaveProperty('trackedLink')
    expect(result['displayId']).toBe('MK-8')
  })
})
