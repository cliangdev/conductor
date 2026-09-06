import { describe, it, expect, vi, beforeEach } from 'vitest'
import * as fs from 'fs'
import * as path from 'path'
import { fileURLToPath } from 'url'
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
vi.mock('node:fs/promises', () => ({ readFile: vi.fn() }))
vi.mock('../lib/mp4-probe.js', () => ({ probeVideo: vi.fn(async () => ({ width: null, height: null, durationSeconds: null })) }))

import { readFile } from 'node:fs/promises'
import { apiGet, apiPost, apiPatch, apiPut, apiDelete, putBytes } from '../mcp/api.js'
import {
  listPublishTargets,
  setPublishTargets,
  uploadAsset,
  retryFailedPublishTargets,
  completeManualPublish,
} from '../mcp/tools/marketing.js'
import { updateWorkItem } from '../mcp/tools/issues.js'

const config: Config = {
  apiKey: 'k',
  projectId: 'proj-1',
  projectName: 'P',
  email: 'e@x.test',
  apiUrl: 'https://api.test',
  localPath: '/tmp/proj',
}

const mocked = <T>(fn: T) => fn as unknown as ReturnType<typeof vi.fn>

const UPLOAD_TICKET = {
  assetId: 'a1',
  uploadUrl: 'https://bucket.test/signed/a1',
  gcsPath: 'projects/proj-1/a1',
  expiresAt: '2026-01-01T00:00:00Z',
}

function storedAsset(overrides: Record<string, unknown> = {}) {
  return {
    id: 'a1',
    workItemId: 'w1',
    type: 'instagram_post',
    kind: 'file',
    ref: 'projects/proj-1/a1',
    done: false,
    uploadStatus: 'UPLOADED',
    contentType: 'image/png',
    sizeBytes: 3,
    width: 800,
    height: 600,
    ...overrides,
  }
}

describe('upload_asset attaches a local file in one call', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mocked(readFile).mockResolvedValue(Buffer.from([1, 2, 3]))
  })

  it('mints, PUTs the bytes and confirms, returning the stored Asset', async () => {
    mocked(apiPost).mockResolvedValueOnce(UPLOAD_TICKET).mockResolvedValueOnce(undefined)
    mocked(apiGet).mockResolvedValue([storedAsset()])

    const result = await uploadAsset(
      { issueId: 'w1', filePath: '/tmp/hero.png', type: 'instagram_post' },
      config
    )

    expect(apiPost).toHaveBeenNthCalledWith(
      1,
      '/api/v2/projects/proj-1/work-items/w1/assets/uploads',
      {
        type: 'instagram_post',
        label: undefined,
        filename: 'hero.png',
        contentType: 'image/png',
        sizeBytes: 3,
        width: undefined,
        height: undefined,
        durationSeconds: undefined,
      },
      config
    )
    expect(putBytes).toHaveBeenCalledWith(
      'https://bucket.test/signed/a1',
      'image/png',
      expect.anything()
    )
    expect(apiPost).toHaveBeenNthCalledWith(
      2,
      '/api/v2/projects/proj-1/work-items/w1/assets/a1/confirm',
      { sizeBytes: 3 },
      config
    )
    expect(result['id']).toBe('a1')
    expect(result['uploadStatus']).toBe('UPLOADED')
    expect(result['warning']).toBeUndefined()
  })

  it('uploads a video without dimensions but says approval is blocked until they are supplied', async () => {
    mocked(apiPost).mockResolvedValueOnce(UPLOAD_TICKET).mockResolvedValueOnce(undefined)
    mocked(apiGet).mockResolvedValue([
      storedAsset({ contentType: 'video/mp4', width: null, height: null, durationSeconds: null }),
    ])

    const result = await uploadAsset(
      { issueId: 'w1', filePath: '/tmp/clip.mp4', type: 'tiktok_video' },
      config
    )

    expect(result['id']).toBe('a1')
    const warning = String(result['warning'])
    expect(warning).toMatch(/approval/i)
    expect(warning).toMatch(/width/)
    expect(warning).toMatch(/height/)
    expect(warning).toMatch(/durationSeconds/)
  })

  it('passes measured video dimensions through to the mint request and warns about nothing', async () => {
    mocked(apiPost).mockResolvedValueOnce(UPLOAD_TICKET).mockResolvedValueOnce(undefined)
    mocked(apiGet).mockResolvedValue([
      storedAsset({ contentType: 'video/mp4', width: 1080, height: 1920, durationSeconds: 12.5 }),
    ])

    const result = await uploadAsset(
      {
        issueId: 'w1',
        filePath: '/tmp/clip.mp4',
        type: 'tiktok_video',
        width: 1080,
        height: 1920,
        durationSeconds: 12.5,
      },
      config
    )

    expect(mocked(apiPost).mock.calls[0]?.[1]).toMatchObject({
      contentType: 'video/mp4',
      width: 1080,
      height: 1920,
      durationSeconds: 12.5,
    })
    expect(result['warning']).toBeUndefined()
  })

  it('fails with the server message on a disallowed file type and leaves no Asset behind', async () => {
    mocked(apiPost).mockRejectedValueOnce(
      new Error('API error 400: Content type application/zip is not allowed')
    )

    await expect(
      uploadAsset({ issueId: 'w1', filePath: '/tmp/bundle.zip', type: 'instagram_post' }, config)
    ).rejects.toThrow(/Content type application\/zip is not allowed/)

    expect(putBytes).not.toHaveBeenCalled()
    expect(apiDelete).not.toHaveBeenCalled()
    expect(apiPost).toHaveBeenCalledTimes(1)
  })

  it('removes the pending Asset when the byte upload fails', async () => {
    mocked(apiPost).mockResolvedValueOnce(UPLOAD_TICKET)
    mocked(putBytes).mockRejectedValueOnce(new Error('Upload PUT failed 403: expired'))

    await expect(
      uploadAsset({ issueId: 'w1', filePath: '/tmp/hero.png', type: 'instagram_post' }, config)
    ).rejects.toThrow(/expired/)

    expect(apiDelete).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/assets/a1',
      config
    )
  })
})

describe('list_publish_targets discovers accounts and a Post’s selection', () => {
  beforeEach(() => vi.clearAllMocks())

  it('returns the project’s available accounts when no Work Item is given', async () => {
    mocked(apiGet).mockResolvedValueOnce([
      { platform: 'facebook', connectionId: 'c1', label: 'Acme Page', lane: 'NATIVE' },
    ])

    const result = await listPublishTargets({}, config)

    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/proj-1/publish-targets', config)
    expect(apiGet).toHaveBeenCalledTimes(1)
    expect(result['accounts']).toHaveLength(1)
    expect(result['selected']).toBeUndefined()
  })

  it('surfaces a TikTok account’s allowed privacy levels and creator nickname', async () => {
    mocked(apiGet).mockResolvedValueOnce([
      {
        platform: 'tiktok',
        connectorId: 'tiktok',
        connectionId: 'c9',
        label: 'TikTok',
        lane: 'APP_MANAGED',
        privacyLevelOptions: ['PUBLIC_TO_EVERYONE', 'SELF_ONLY'],
        creatorNickname: '@acme',
      },
    ])

    const result = await listPublishTargets({}, config)
    const tiktok = (result['accounts'] as Record<string, unknown>[])[0]!

    expect(tiktok['privacyLevelOptions']).toEqual(['PUBLIC_TO_EVERYONE', 'SELF_ONLY'])
    expect(tiktok['creatorNickname']).toBe('@acme')
  })

  it('surfaces each account’s post formats', async () => {
    mocked(apiGet).mockResolvedValueOnce([
      { platform: 'instagram', connectionId: 'c1', label: '@acme', lane: 'APP_MANAGED', formats: ['feed', 'reel', 'story'] },
      { platform: 'youtube', connectionId: 'c2', label: 'Acme', lane: 'NATIVE', formats: ['feed'] },
    ])

    const result = await listPublishTargets({}, config)
    const [instagram, youtube] = result['accounts'] as Record<string, unknown>[]

    expect(instagram!['formats']).toEqual(['feed', 'reel', 'story'])
    expect(youtube!['formats']).toEqual(['feed'])
  })

  it('also returns the Work Item’s selected targets with their publish outcomes', async () => {
    mocked(apiGet)
      .mockResolvedValueOnce([{ platform: 'tiktok', connectionId: 'c9', lane: 'APP_MANAGED' }])
      .mockResolvedValueOnce([
        {
          id: 't1',
          platform: 'tiktok',
          connectionId: 'c9',
          state: 'PUBLISHED',
          permalink: 'https://tiktok.test/p/1',
          errorMessage: null,
        },
        {
          id: 't2',
          platform: 'facebook',
          connectionId: 'c1',
          state: 'FAILED',
          permalink: null,
          errorMessage: 'Page token expired',
        },
      ])

    const result = await listPublishTargets({ issueId: 'w1' }, config)

    expect(apiGet).toHaveBeenNthCalledWith(
      2,
      '/api/v2/projects/proj-1/work-items/w1/publish-targets',
      config
    )
    const selected = result['selected'] as Record<string, unknown>[]
    expect(selected.map((t) => t['state'])).toEqual(['PUBLISHED', 'FAILED'])
    expect(selected[0]!['permalink']).toBe('https://tiktok.test/p/1')
    expect(selected[1]!['errorMessage']).toBe('Page token expired')
  })
})

describe('set_publish_targets chooses destinations and per-target options', () => {
  beforeEach(() => vi.clearAllMocks())

  it('PUTs the complete selection with per-target publish options', async () => {
    const stored = [
      {
        id: 't1',
        platform: 'tiktok',
        connectionId: 'c9',
        state: 'PENDING',
        publishOptions: { privacyLevel: 'PUBLIC_TO_EVERYONE', disableComment: true },
      },
    ]
    mocked(apiPut).mockResolvedValue(stored)

    const result = await setPublishTargets(
      {
        issueId: 'w1',
        targets: [
          {
            platform: 'tiktok',
            connectionId: 'c9',
            publishOptions: { privacyLevel: 'PUBLIC_TO_EVERYONE', disableComment: true },
          },
        ],
      },
      config
    )

    expect(apiPut).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/publish-targets',
      {
        targets: [
          {
            platform: 'tiktok',
            connectionId: 'c9',
            publishOptions: { privacyLevel: 'PUBLIC_TO_EVERYONE', disableComment: true },
          },
        ],
      },
      config
    )
    expect(result['targets']).toEqual(stored)
  })

  it('passes a per-target format straight through to the PUT body', async () => {
    mocked(apiPut).mockResolvedValue([{ id: 't1', platform: 'facebook', format: 'story', state: 'PENDING' }])

    const result = await setPublishTargets(
      { issueId: 'w1', targets: [{ platform: 'facebook', connectionId: 'c1', format: 'story' }] },
      config
    )

    expect(apiPut).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/publish-targets',
      { targets: [{ platform: 'facebook', connectionId: 'c1', format: 'story' }] },
      config
    )
    expect((result['targets'] as Array<Record<string, unknown>>)[0]!['format']).toBe('story')
  })

  it('sends a per-target caption and its ordered media selection verbatim', async () => {
    mocked(apiPut).mockResolvedValue([])

    await setPublishTargets(
      {
        issueId: 'w1',
        targets: [
          {
            platform: 'instagram',
            connectionId: 'c9',
            captionOverride: 'Square crop for the grid',
            assetIds: ['asset-b', 'asset-a'],
          },
        ],
      },
      config
    )

    expect(apiPut).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/publish-targets',
      {
        targets: [
          {
            platform: 'instagram',
            connectionId: 'c9',
            captionOverride: 'Square crop for the grid',
            // Order is content: Instagram crops the carousel to whichever item comes first.
            assetIds: ['asset-b', 'asset-a'],
          },
        ],
      },
      config
    )
  })

  it('omits caption and media entirely when the caller did not set them', async () => {
    mocked(apiPut).mockResolvedValue([])

    await setPublishTargets(
      { issueId: 'w1', targets: [{ platform: 'facebook', connectionId: 'c9' }] },
      config
    )

    // Sent as absent rather than as explicit nulls, so the server reads "inherit the Post's" from the
    // same shape a client that predates per-target content would send.
    const body = mocked(apiPut).mock.calls[0]![1] as { targets: Record<string, unknown>[] }
    expect(body.targets[0]).not.toHaveProperty('captionOverride')
    expect(body.targets[0]).not.toHaveProperty('assetIds')
  })

  it('exposes what each destination will actually publish on the read-back', async () => {
    mocked(apiGet)
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        {
          id: 't1',
          platform: 'instagram',
          assetIds: ['asset-b'],
          effectiveAssetIds: ['asset-b'],
          effectiveCaption: 'Square crop for the grid',
        },
        {
          id: 't2',
          platform: 'facebook',
          // Null assetIds with a non-empty effective list is the inherit case, and the pair is what
          // tells "chose these" apart from "happens to match the Post right now".
          assetIds: null,
          effectiveAssetIds: ['asset-a', 'asset-b'],
          effectiveCaption: 'The shared caption',
        },
      ])

    const result = await listPublishTargets({ issueId: 'w1' }, config)

    expect(result['selected']).toEqual([
      expect.objectContaining({ effectiveAssetIds: ['asset-b'] }),
      expect.objectContaining({ assetIds: null, effectiveAssetIds: ['asset-a', 'asset-b'] }),
    ])
  })

  it('clears the selection with an empty array', async () => {
    mocked(apiPut).mockResolvedValue([])
    await setPublishTargets({ issueId: 'w1', targets: [] }, config)
    expect(apiPut).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/publish-targets',
      { targets: [] },
      config
    )
  })

  it('rejects a missing selection rather than silently clearing every target', async () => {
    await expect(
      setPublishTargets(
        { issueId: 'w1' } as unknown as Parameters<typeof setPublishTargets>[0],
        config
      )
    ).rejects.toThrow(/targets/)
    expect(apiPut).not.toHaveBeenCalled()
  })
})

describe('retry_failed_publish_targets re-fires only failed targets', () => {
  beforeEach(() => vi.clearAllMocks())

  it('POSTs the retry and reports what moved', async () => {
    mocked(apiPost).mockResolvedValue({
      workItemId: 'w1',
      status: 'SCHEDULED',
      retriedCount: 1,
      targets: [
        { id: 't1', state: 'PUBLISHED', permalink: 'https://tiktok.test/p/1', errorMessage: null },
        { id: 't2', state: 'PENDING', permalink: null, errorMessage: null },
      ],
    })

    const result = await retryFailedPublishTargets({ issueId: 'w1' }, config)

    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/publish-targets/retry',
      undefined,
      config
    )
    expect(result['retriedCount']).toBe(1)
    const targets = result['targets'] as Record<string, unknown>[]
    expect(targets[0]!['state']).toBe('PUBLISHED')
    expect(targets[1]!['state']).toBe('PENDING')
  })
})

describe('update_work_item schedules a Post', () => {
  beforeEach(() => vi.clearAllMocks())

  it('round-trips scheduledFor and scheduleTimezone through the v2 PATCH', async () => {
    mocked(apiPatch).mockResolvedValue({ id: 'w1' })

    const result = await updateWorkItem(
      {
        issueId: 'w1',
        scheduledFor: '2026-09-01T14:00:00Z',
        scheduleTimezone: 'America/New_York',
      },
      config
    )

    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1',
      { scheduledFor: '2026-09-01T14:00:00Z', scheduleTimezone: 'America/New_York' },
      config
    )
    expect(result['scheduledFor']).toBe('2026-09-01T14:00:00Z')
    expect(result['scheduleTimezone']).toBe('America/New_York')
  })

  it('leaves the schedule alone when neither field is given', async () => {
    mocked(apiPatch).mockResolvedValue({ id: 'w1' })
    await updateWorkItem({ issueId: 'w1', title: 'New' }, config)
    expect(apiPatch).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1',
      { title: 'New' },
      config
    )
  })
})

describe('the MANUAL lane is reachable end to end from an agent', () => {
  beforeEach(() => vi.clearAllMocks())

  it('selects a manual destination by naming only its platform', async () => {
    // No account exists for it, so there is no connectionId to send. This is what makes a project with
    // no social integration able to pick a target at all — without one it cannot clear the approval gate.
    mocked(apiPut).mockResolvedValue([{ id: 't1', platform: 'tiktok', lane: 'MANUAL' }])

    await setPublishTargets({ issueId: 'w1', targets: [{ platform: 'tiktok' }] }, config)

    expect(apiPut).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/publish-targets',
      { targets: [{ platform: 'tiktok' }] },
      config
    )
  })

  it('records a manual publish and returns the target as read back', async () => {
    const published = { id: 't1', lane: 'MANUAL', state: 'PUBLISHED', permalink: 'https://tiktok.com/x' }
    mocked(apiPost).mockResolvedValue(published)

    const result = await completeManualPublish(
      { issueId: 'w1', targetId: 't1', permalink: 'https://tiktok.com/x' },
      config
    )

    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/w1/publish-targets/t1/manual-publish',
      { permalink: 'https://tiktok.com/x', publishedAt: null },
      config
    )
    // The action and its verification in one call: an agent never has to guess whether the write landed.
    expect(result['target']).toEqual(published)
  })

  it('passes on when it actually went out, for a link recorded after the fact', async () => {
    mocked(apiPost).mockResolvedValue({ id: 't1' })

    await completeManualPublish(
      {
        issueId: 'w1',
        targetId: 't1',
        permalink: 'https://tiktok.com/x',
        publishedAt: '2026-09-01T09:00:00Z',
      },
      config
    )

    expect(mocked(apiPost).mock.calls[0][1]).toEqual({
      permalink: 'https://tiktok.com/x',
      publishedAt: '2026-09-01T09:00:00Z',
    })
  })

  it('refuses to call the server with no link at all', async () => {
    // With no platform to ask, the link is the only record the destination went out. Caught here rather
    // than round-tripping, so the agent gets the reason and the tool that finds the target id.
    await expect(
      completeManualPublish({ issueId: 'w1', targetId: 't1', permalink: '   ' }, config)
    ).rejects.toThrow(/permalink is required/)
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('trims the link rather than storing the whitespace around a pasted URL', async () => {
    mocked(apiPost).mockResolvedValue({ id: 't1' })

    await completeManualPublish(
      { issueId: 'w1', targetId: 't1', permalink: '  https://tiktok.com/x\n' },
      config
    )

    expect(mocked(apiPost).mock.calls[0][1]).toMatchObject({ permalink: 'https://tiktok.com/x' })
  })

  it('surfaces the server refusal for an automated target rather than swallowing it', async () => {
    // The backend refuses any target that is not MANUAL: its poller will publish it and report the real
    // outcome, and a human declaring it published would strand a post still queued to go out.
    mocked(apiPost).mockRejectedValue(new Error('API error 422: This destination publishes automatically'))

    await expect(
      completeManualPublish({ issueId: 'w1', targetId: 't1', permalink: 'https://x.test/1' }, config)
    ).rejects.toThrow(/publishes automatically/)
  })
})

describe('no MCP tool records TikTok consent or offers a way around it', () => {
  const __dirname = path.dirname(fileURLToPath(import.meta.url))
  const registrySrc = fs.readFileSync(path.join(__dirname, '..', 'mcp', 'index.ts'), 'utf8')
  const toolsSrc = registrySrc.match(/const TOOLS = \[([\s\S]*?)\n\]\n/)![1]!

  it('registers no tool whose name mentions consent', () => {
    const names = [...toolsSrc.matchAll(/name:\s*'([a-z_]+)'/g)].map((m) => m[1]!)
    expect(names.filter((n) => /consent/i.test(n))).toEqual([])
  })

  it('accepts no consent parameter anywhere in the tool schemas', () => {
    expect(toolsSrc.match(/\w*consent\w*:\s*\{/gi) ?? []).toEqual([])
  })

  it('mentions consent only to say a human records it in the UI', () => {
    const consentLines = toolsSrc.split('\n').filter((line) => /consent/i.test(line))
    expect(consentLines.length).toBeGreaterThan(0)
    for (const line of consentLines) {
      expect(line, `consent mentioned without pointing at the human UI step: ${line}`).toMatch(
        /human/i
      )
      expect(line).toMatch(/UI/)
    }
  })
})
