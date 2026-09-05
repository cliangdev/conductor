import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Config } from '../mcp/config.js'

vi.mock('../mcp/api.js', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  putBytes: vi.fn(),
  isClientError: (err: unknown) => err instanceof Error && /API error 4\d\d\b/.test(err.message),
}))
vi.mock('../mcp/queue.js', () => ({ queueChange: vi.fn(() => 1) }))
vi.mock('../mcp/files.js', () => ({
  writeIssueFile: vi.fn(),
  readIssueFile: vi.fn(() => null),
  resolveLocalPath: vi.fn(() => '/tmp/proj'),
}))
vi.mock('node:fs/promises', () => ({ readFile: vi.fn(async () => Buffer.from('bytes')), mkdtemp: vi.fn(), writeFile: vi.fn(), rm: vi.fn() }))
vi.mock('../lib/mp4-probe.js', () => ({
  probeVideo: vi.fn(async () => ({ width: 1080, height: 1920, durationSeconds: 12.5 })),
}))

import { apiGet, apiPost, apiPatch, apiPut } from '../mcp/api.js'
import { createPost, getPostStatus, submitPost, listPosts, submitReview, nextQuarterHour } from '../mcp/tools/posts.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}

const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

const ACCOUNTS = [
  { platform: 'instagram', connectionId: 'c-ig', label: '@acme', lane: 'APP_MANAGED', optionKeys: [] },
  { platform: 'facebook', connectionId: 'c-fb', label: 'Acme Page', lane: 'NATIVE', optionKeys: [] },
  { platform: 'tiktok', connectionId: 'c-tt', label: 'Acme Creator', lane: 'APP_MANAGED', optionKeys: ['privacyLevel'] },
  { platform: 'instagram', connectionId: null, label: 'Instagram (manual)', lane: 'MANUAL', optionKeys: [] },
  { platform: 'tiktok', connectionId: null, label: 'TikTok (manual)', lane: 'MANUAL', optionKeys: [] },
]

const WORKFLOWS = [
  { slug: 'ENGINEERING', kind: 'LIFECYCLE', definition: { types: ['PRD'], asset_types: ['github_pr'] } },
  { slug: 'MARKETING', kind: 'LIFECYCLE', definition: { types: ['POST'], asset_types: ['facebook_post', 'instagram_post'] } },
]

function readyPreflight(overrides: Record<string, unknown> = {}) {
  return {
    publishing: true,
    ready: true,
    blockers: [],
    warnings: [],
    nextTransition: { to: 'IN_REVIEW', label: 'Submit for review', requiresReview: false },
    consent: { required: false, verdict: 'NOT_REQUIRED' },
    review: { gated: true, assignedReviewers: 0, satisfied: false, reviewerRole: 'REVIEWER' },
    earliestFireTime: '2026-09-04T12:01:01Z',
    ...overrides,
  }
}

/** Routes every GET by path so a test reads like the server it stands in for. */
function serve(routes: Record<string, unknown | ((path: string) => unknown)>) {
  mocked(apiGet).mockImplementation(async (path: string) => {
    for (const [prefix, value] of Object.entries(routes)) {
      if (path.startsWith(prefix)) return typeof value === 'function' ? (value as (p: string) => unknown)(path) : value
    }
    throw new Error(`unexpected GET ${path}`)
  })
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('create_post', () => {
  it('creates, uploads, targets, schedules and submits in one call, and returns the confirmation table', async () => {
    let status = 'DRAFT'
    let preflightCalls = 0
    serve({
      '/api/v2/projects/proj-1/publish-targets': ACCOUNTS,
      '/api/v1/projects/proj-1/workflows': WORKFLOWS,
      '/api/v2/projects/proj-1/work-items/w1/publish-preflight': () => {
        preflightCalls++
        return readyPreflight(status === 'IN_REVIEW' ? { nextTransition: { to: 'APPROVED', label: 'Approve', requiresReview: true }, review: { gated: true, assignedReviewers: 1, satisfied: false } } : {})
      },
      '/api/v2/projects/proj-1/work-items/w1/publish-targets': [
        { id: 't-ig', platform: 'instagram', label: '@acme', lane: 'APP_MANAGED', state: 'PENDING' },
        { id: 't-tt', platform: 'tiktok', label: null, lane: 'MANUAL', state: 'PENDING' },
      ],
      '/api/v2/projects/proj-1/work-items/w1/assets': [{ id: 'a1', label: 'clip.mp4', contentType: 'video/mp4' }],
      '/api/v2/projects/proj-1/work-items/w1': () => ({ id: 'w1', displayId: 'MK-7', status, scheduledFor: '2026-09-04T12:15:00.000Z', scheduleTimezone: 'Europe/Berlin' }),
      '/api/v1/projects/proj-1/members': [{ userId: 'u-rev', name: 'Rita Reviewer', email: 'rita@x.test', role: 'REVIEWER' }],
    })
    mocked(apiPost).mockImplementation(async (path: string, body: unknown) => {
      if (path.endsWith('/work-items')) return { id: 'w1', displayId: 'MK-7', status: 'DRAFT' }
      if (path.endsWith('/assets/uploads')) return { assetId: 'a1', uploadUrl: 'https://bucket/a1' }
      if (path.endsWith('/confirm')) return undefined
      if (path.endsWith('/reviewers')) return body
      throw new Error(`unexpected POST ${path}`)
    })
    mocked(apiPut).mockResolvedValue([])
    mocked(apiPatch).mockImplementation(async (_path: string, body: Record<string, unknown>) => {
      if (body['status']) status = String(body['status'])
      return {}
    })

    const result = await createPost(
      {
        text: 'Launch day!\nMore below.',
        media: [{ path: '/tmp/clip.mp4' }],
        targets: [
          { platform: 'instagram', account: '@ACME', assetIds: [0] },
          { platform: 'tiktok', options: { privacyLevel: 'PUBLIC_TO_EVERYONE' } },
        ],
        timezone: 'Europe/Berlin',
        reviewers: ['rita reviewer'],
      },
      config
    )

    // The Work Item: caption is the description, title is the first line, type from the Workflow.
    expect(mocked(apiPost).mock.calls[0]![1]).toMatchObject({ workflow: 'MARKETING', type: 'POST', title: 'Launch day!', description: 'Launch day!\nMore below.' })
    // Media measured here and typed from the Workflow.
    expect(mocked(apiPost).mock.calls[1]![1]).toMatchObject({ type: 'facebook_post', width: 1080, height: 1920, durationSeconds: 12.5 })
    // Destinations resolved by label (case-insensitive) and by omission (manual), index → asset id.
    expect(mocked(apiPut).mock.calls[0]![1]).toEqual({
      targets: [
        { platform: 'instagram', connectionId: 'c-ig', captionOverride: null, assetIds: ['a1'], publishOptions: undefined },
        { platform: 'tiktok', connectionId: null, captionOverride: null, assetIds: [], publishOptions: { privacyLevel: 'PUBLIC_TO_EVERYONE' } },
      ],
    })
    // Scheduled on the quarter-hour at or after the server's earliest, in the given zone.
    expect(mocked(apiPatch).mock.calls[0]![1]).toEqual({ scheduledFor: '2026-09-04T12:15:00.000Z', scheduleTimezone: 'Europe/Berlin' })
    // Reviewer assigned by name, then submitted.
    expect(mocked(apiPost).mock.calls.some((c) => String(c[0]).endsWith('/reviewers') && (c[1] as { userId: string }).userId === 'u-rev')).toBe(true)
    expect(mocked(apiPatch).mock.calls[1]![1]).toEqual({ status: 'IN_REVIEW' })

    expect(result['postId']).toBe('w1')
    expect(result['displayId']).toBe('MK-7')
    expect(result['status']).toBe('IN_REVIEW')
    expect(result['targets']).toEqual([
      { targetId: 't-ig', platform: 'instagram', account: '@acme', lane: 'APP_MANAGED', state: 'PENDING', permalink: null, errorMessage: null },
      { targetId: 't-tt', platform: 'tiktok', account: 'manual', lane: 'MANUAL', state: 'PENDING', permalink: null, errorMessage: null },
    ])
    expect(result['reviewers']).toEqual([{ userId: 'u-rev', name: 'Rita Reviewer' }])
    expect(String(result['nextStep'])).toContain('Waiting for a reviewer')
    expect(preflightCalls).toBeGreaterThanOrEqual(2)
  })

  it('refuses an unknown account before creating anything, naming the ones it knows', async () => {
    serve({ '/api/v2/projects/proj-1/publish-targets': ACCOUNTS, '/api/v1/projects/proj-1/workflows': WORKFLOWS })

    await expect(createPost({ text: 'hi', targets: [{ platform: 'facebook', account: 'Nope Page' }] }, config))
      .rejects.toThrow(/No facebook account named "Nope Page".*Acme Page/)
    await expect(createPost({ text: 'hi', targets: [{ platform: 'mastodon' }] }, config))
      .rejects.toThrow(/not a platform this project can publish to/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('leaves a Post in Draft with its blockers when the gate is not satisfied, and never transitions', async () => {
    serve({
      '/api/v2/projects/proj-1/publish-targets': ACCOUNTS,
      '/api/v1/projects/proj-1/workflows': WORKFLOWS,
      '/api/v2/projects/proj-1/work-items/w1/publish-preflight': readyPreflight({
        ready: false,
        blockers: [{ code: 'NO_MEDIA', message: 'no uploaded media file is attached — upload at least one image or video' }],
      }),
      '/api/v2/projects/proj-1/work-items/w1/publish-targets': [],
      '/api/v2/projects/proj-1/work-items/w1/assets': [],
      '/api/v2/projects/proj-1/work-items/w1': { id: 'w1', displayId: 'MK-8', status: 'DRAFT' },
    })
    mocked(apiPost).mockResolvedValue({ id: 'w1', displayId: 'MK-8', status: 'DRAFT' })
    mocked(apiPut).mockResolvedValue([])
    mocked(apiPatch).mockResolvedValue({})

    const result = await createPost({ text: 'hi', targets: [{ platform: 'instagram', account: 'c-ig' }] }, config)

    expect(result['status']).toBe('DRAFT')
    expect(result['blockers']).toEqual([expect.objectContaining({ code: 'NO_MEDIA' })])
    expect(String(result['nextStep'])).toContain('Fix the blockers')
    expect(mocked(apiPatch).mock.calls.every((c) => !(c[1] as Record<string, unknown>)['status'])).toBe(true)
  })

  it('needs `workflow` when more than one Workflow publishes', async () => {
    serve({
      '/api/v2/projects/proj-1/publish-targets': ACCOUNTS,
      '/api/v1/projects/proj-1/workflows': [...WORKFLOWS, { slug: 'MARKETING_AUTOPILOT', kind: 'LIFECYCLE', definition: { types: ['POST'], asset_types: ['instagram_post'] } }],
    })
    await expect(createPost({ text: 'hi', targets: [{ platform: 'instagram' }] }, config))
      .rejects.toThrow(/Several Workflows publish here: MARKETING, MARKETING_AUTOPILOT/)
  })
})

describe('get_post_status / submit_post / list_posts / submit_review', () => {
  it('get_post_status reads everything live from the server', async () => {
    serve({
      '/api/v2/projects/proj-1/work-items/w1/publish-preflight': readyPreflight({ nextTransition: null }),
      '/api/v2/projects/proj-1/work-items/w1/publish-targets': [
        { id: 't1', platform: 'facebook', label: 'Acme Page', lane: 'NATIVE', state: 'PUBLISHED', permalink: 'https://fb/1' },
        { id: 't2', platform: 'instagram', label: '@acme', lane: 'APP_MANAGED', state: 'FAILED', errorMessage: 'token expired' },
      ],
      '/api/v2/projects/proj-1/work-items/w1/assets': [],
      '/api/v2/projects/proj-1/work-items/w1': { id: 'w1', displayId: 'MK-7', status: 'FAILED', scheduledFor: '2026-09-04T12:15:00Z' },
    })

    const result = await getPostStatus({ postId: 'w1' }, config)

    expect(result['status']).toBe('FAILED')
    expect((result['targets'] as Array<Record<string, unknown>>)[1]).toMatchObject({ state: 'FAILED', errorMessage: 'token expired' })
    expect(String(result['nextStep'])).toContain('Scheduled')
  })

  it('submit_post assigns reviewers and takes the move the gate names', async () => {
    let status = 'DRAFT'
    serve({
      '/api/v2/projects/proj-1/work-items/w1/publish-preflight': () => readyPreflight(status === 'IN_REVIEW' ? { nextTransition: { to: 'APPROVED', label: 'Approve', requiresReview: true } } : {}),
      '/api/v2/projects/proj-1/work-items/w1/publish-targets': [],
      '/api/v2/projects/proj-1/work-items/w1/assets': [],
      '/api/v2/projects/proj-1/work-items/w1': () => ({ id: 'w1', status }),
      '/api/v1/projects/proj-1/members': [
        { userId: 'u-c', name: 'Carl Creator', email: 'carl@x.test', role: 'CREATOR' },
        { userId: 'u-r', name: 'Rita', email: 'rita@x.test', role: 'REVIEWER' },
      ],
    })
    mocked(apiPost).mockResolvedValue({})
    mocked(apiPatch).mockImplementation(async (_p: string, body: Record<string, unknown>) => {
      status = String(body['status'])
      return {}
    })

    const result = await submitPost({ postId: 'w1', reviewers: ['rita@x.test', 'Carl Creator'] }, config)

    expect(result['status']).toBe('IN_REVIEW')
    expect(result['reviewers']).toEqual([{ userId: 'u-r', name: 'Rita' }])
    expect((result['warnings'] as string[]).join(' ')).toContain('Carl Creator holds the CREATOR role')
  })

  it('list_posts spans every publishing Workflow and filters by window and platform', async () => {
    serve({
      '/api/v2/projects/proj-1/publish-targets': ACCOUNTS,
      '/api/v1/projects/proj-1/workflows': WORKFLOWS,
      '/api/v2/projects/proj-1/work-items/w1/publish-targets': [{ id: 't1', platform: 'facebook', label: 'Acme Page', state: 'PUBLISHED', permalink: 'https://fb/1' }],
      '/api/v2/projects/proj-1/work-items/w2/publish-targets': [{ id: 't2', platform: 'instagram', label: '@acme', state: 'PENDING' }],
      '/api/v2/projects/proj-1/work-items/w3/publish-targets': [],
      '/api/v2/projects/proj-1/work-items?': [
        { id: 'w1', displayId: 'MK-1', title: 'One', status: 'PUBLISHED', scheduledFor: '2026-09-01T10:00:00Z' },
        { id: 'w2', displayId: 'MK-2', title: 'Two', status: 'SCHEDULED', scheduledFor: '2026-09-10T10:00:00Z' },
        { id: 'w3', displayId: 'MK-3', title: 'Undated', status: 'DRAFT', scheduledFor: null },
      ],
    })

    const all = await listPosts({}, config)
    expect((all['posts'] as Array<Record<string, unknown>>).map((p) => p['displayId'])).toEqual(['MK-2', 'MK-1', 'MK-3'])

    const windowed = await listPosts({ since: '2026-09-05T00:00:00Z' }, config)
    expect((windowed['posts'] as Array<Record<string, unknown>>).map((p) => p['displayId'])).toEqual(['MK-2'])

    const facebookOnly = await listPosts({ platform: 'facebook' }, config)
    expect((facebookOnly['posts'] as Array<Record<string, unknown>>).map((p) => p['displayId'])).toEqual(['MK-1'])
    expect(mocked(apiGet).mock.calls.some((c) => String(c[0]).includes('workflow=MARKETING'))).toBe(true)
    expect(mocked(apiGet).mock.calls.some((c) => String(c[0]).includes('workflow=ENGINEERING'))).toBe(false)
  })

  it('submit_review maps the verdict, relays autoTransition, and surfaces a 403 as an error', async () => {
    serve({
      '/api/v2/projects/proj-1/work-items/w1/publish-preflight': readyPreflight({ nextTransition: null }),
      '/api/v2/projects/proj-1/work-items/w1/publish-targets': [],
      '/api/v2/projects/proj-1/work-items/w1/assets': [],
      '/api/v2/projects/proj-1/work-items/w1': { id: 'w1', status: 'SCHEDULED' },
    })
    mocked(apiPost).mockResolvedValue({ id: 'r1', verdict: 'APPROVED', autoTransition: { applied: true, fromStatus: 'IN_REVIEW', toStatus: 'SCHEDULED' } })

    const result = await submitReview({ postId: 'w1', verdict: 'approve', summary: 'ship it' }, config)
    expect(mocked(apiPost).mock.calls[0]![1]).toEqual({ verdict: 'APPROVED', body: 'ship it' })
    expect(result['autoTransition']).toMatchObject({ applied: true, toStatus: 'SCHEDULED' })
    expect(result['status']).toBe('SCHEDULED')

    mocked(apiPost).mockRejectedValue(new Error('API error 403: You are not an assigned reviewer'))
    const refused = await submitReview({ postId: 'w1', verdict: 'request_changes' }, config)
    expect(String(refused['error'])).toContain('not an assigned reviewer')

    await expect(submitReview({ postId: 'w1', verdict: 'maybe' }, config)).rejects.toThrow(/verdict must be/)
  })
})

describe('nextQuarterHour', () => {
  it('rounds up to the next quarter-hour at or after the given instant', () => {
    expect(nextQuarterHour(new Date('2026-09-04T12:01:01Z')).toISOString()).toBe('2026-09-04T12:15:00.000Z')
    expect(nextQuarterHour(new Date('2026-09-04T12:15:00Z')).toISOString()).toBe('2026-09-04T12:15:00.000Z')
    expect(nextQuarterHour(new Date('2026-09-04T12:46:00Z')).toISOString()).toBe('2026-09-04T13:00:00.000Z')
  })
})
