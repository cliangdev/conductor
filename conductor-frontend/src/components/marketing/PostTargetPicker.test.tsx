import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'
import {
  PostTargetPicker,
  workflowDeclaresPublishTargets,
  type PublishTargetOption,
  type SelectedPublishTarget,
} from './PostTargetPicker'

const API = 'https://api.test'
const PROJECT = 'project-1'
const WORK_ITEM = 'post-1'

const VIEW: WorkflowView = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'MARKETING',
  defaultView: 'list',
  version: 1,
  types: ['POST'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'in_progress' },
    { id: 'APPROVED', label: 'Approved', category: 'in_progress' },
  ],
  transitions: [
    { from: 'DRAFT', to: 'IN_REVIEW', label: 'Submit' },
    { from: 'IN_REVIEW', to: 'APPROVED', label: 'Approve', requiresReview: true },
  ],
}

function option(overrides: Partial<PublishTargetOption> & Pick<PublishTargetOption, 'platform' | 'connectionId'>): PublishTargetOption {
  return {
    connectorId: overrides.platform === 'facebook' || overrides.platform === 'instagram' ? 'meta' : overrides.platform,
    label: overrides.connectionId ?? 'Manual',
    lane: overrides.platform === 'facebook' || overrides.platform === 'youtube' ? 'NATIVE' : 'APP_MANAGED',
    ...overrides,
  }
}

function tiktokOption(
  connectionId: string,
  overrides: Partial<PublishTargetOption> = {}
): PublishTargetOption {
  return option({
    platform: 'tiktok',
    connectionId,
    label: `@${connectionId}`,
    creatorNickname: connectionId,
    privacyLevelOptions: ['PUBLIC_TO_EVERYONE', 'MUTUAL_FOLLOW_FRIENDS', 'SELF_ONLY'],
    ...overrides,
  })
}

/** A destination a human publishes by hand: no account, no connector, always offered. */
function manualOption(platform: PublishTargetOption['platform']): PublishTargetOption {
  const labels: Record<string, string> = {
    facebook: 'Facebook (manual)',
    instagram: 'Instagram (manual)',
    youtube: 'YouTube (manual)',
    tiktok: 'TikTok (manual)',
  }
  return {
    platform,
    connectorId: null,
    connectionId: null,
    label: labels[platform],
    lane: 'MANUAL',
  }
}

function selection(o: PublishTargetOption, id = `target-${o.platform}-${o.connectionId}`): SelectedPublishTarget {
  return {
    id,
    workItemId: WORK_ITEM,
    platform: o.platform,
    connectorId: o.connectorId,
    connectionId: o.connectionId,
    label: o.label,
    lane: o.lane,
    state: 'PENDING',
  }
}

// ── recorded traffic ────────────────────────────────────────────────────────

let availableTargets: PublishTargetOption[] = []
let selectedTargets: SelectedPublishTarget[] = []
let putBodies: Array<{
  targets: Array<{
    platform: string
    connectionId: string | null
    format?: string
    publishOptions?: unknown
    captionOverride?: string
    assetIds?: string[]
  }>
}> = []
let putRejection: { status: number; detail: string } | null = null
let retryResult: { workItemId: string; status: string; retriedCount: number; targets: SelectedPublishTarget[] } | null = null
let retryRejection: { status: number; detail: string } | null = null
let retryCalls = 0
let manualCalls: Array<{ url: string; body: { permalink: string; publishedAt: string | null } }> = []
let manualRejection: { status: number; detail: string } | null = null
let consentServed: Record<string, unknown> | null = null
let consentPutBodies: Array<{ consented: boolean }> = []

function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body,
  }
}

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (method === 'GET' && url.endsWith(`/projects/${PROJECT}/publish-targets`)) {
    return jsonResponse(200, availableTargets)
  }
  if (method === 'GET' && url.endsWith('/publish-consent')) {
    return jsonResponse(
      200,
      consentServed ?? {
        workItemId: WORK_ITEM,
        required: true,
        valid: false,
        verdict: 'NEVER_GIVEN',
        consentedAt: null,
        consentedByUserId: null,
        consentedByName: null,
      }
    )
  }
  if (method === 'PUT' && url.endsWith('/publish-consent')) {
    const body = JSON.parse(init!.body as string)
    consentPutBodies.push(body)
    consentServed = body.consented
      ? {
          workItemId: WORK_ITEM,
          required: true,
          valid: true,
          verdict: 'VALID',
          consentedAt: '2026-08-30T12:00:00Z',
          consentedByUserId: 'user-1',
          consentedByName: 'Ada Creator',
        }
      : {
          workItemId: WORK_ITEM,
          required: true,
          valid: false,
          verdict: 'NEVER_GIVEN',
          consentedAt: null,
          consentedByUserId: null,
          consentedByName: null,
        }
    return jsonResponse(200, consentServed)
  }
  if (method === 'POST' && url.includes('/manual-publish')) {
    manualCalls.push({ url, body: JSON.parse(String(init?.body ?? '{}')) })
    if (manualRejection) return jsonResponse(manualRejection.status, { detail: manualRejection.detail })
    const targetId = url.split('/publish-targets/')[1].replace('/manual-publish', '')
    const updated: SelectedPublishTarget = {
      ...selectedTargets.find((t) => t.id === targetId)!,
      state: 'PUBLISHED',
      permalink: manualCalls[manualCalls.length - 1].body.permalink,
    }
    selectedTargets = selectedTargets.map((t) => (t.id === targetId ? updated : t))
    return jsonResponse(200, updated)
  }
  if (method === 'POST' && url.endsWith(`/work-items/${WORK_ITEM}/publish-targets/retry`)) {
    retryCalls += 1
    if (retryRejection) return jsonResponse(retryRejection.status, { detail: retryRejection.detail })
    selectedTargets = retryResult!.targets
    return jsonResponse(200, retryResult)
  }
  if (method === 'GET' && url.endsWith(`/work-items/${WORK_ITEM}/publish-targets`)) {
    return jsonResponse(200, selectedTargets)
  }
  if (method === 'PUT' && url.endsWith(`/work-items/${WORK_ITEM}/publish-targets`)) {
    if (putRejection) return jsonResponse(putRejection.status, { detail: putRejection.detail })
    const body = JSON.parse(init!.body as string)
    putBodies.push(body)
    selectedTargets = body.targets.map(
      (t: {
        platform: string
        connectionId: string
        format?: string
        publishOptions?: unknown
        captionOverride?: string
        assetIds?: string[]
      }) => ({
        ...selection(
          option({ platform: t.platform as PublishTargetOption['platform'], connectionId: t.connectionId })
        ),
        format: t.format ?? 'feed',
        publishOptions: t.publishOptions,
        // Echoed the way the server does, so a save round-trips a customisation instead of blanking it.
        captionOverride: t.captionOverride ?? null,
        assetIds: t.assetIds ?? null,
      })
    )
    return jsonResponse(200, selectedTargets)
  }
  throw new Error(`unexpected fetch: ${method} ${url}`)
})

beforeEach(() => {
  availableTargets = []
  selectedTargets = []
  putBodies = []
  putRejection = null
  retryResult = null
  retryRejection = null
  retryCalls = 0
  manualCalls = []
  manualRejection = null
  consentServed = null
  consentPutBodies = []
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function renderPicker(props: Partial<React.ComponentProps<typeof PostTargetPicker>> = {}) {
  return render(
    <PostTargetPicker
      projectId={PROJECT}
      workItemId={WORK_ITEM}
      token="tok"
      status="DRAFT"
      workflowView={VIEW}
      {...props}
    />
  )
}

/** Waits out the two initial GETs. */
async function loaded() {
  await waitFor(() => expect(screen.getByText(/accounts? selected/i)).toBeInTheDocument())
}

const POST_ASSETS = [
  { id: 'asset-a', type: 'instagram_post', label: 'Square', contentType: 'image/jpeg' },
  { id: 'asset-b', type: 'instagram_post', label: 'Portrait', contentType: 'image/jpeg' },
] as React.ComponentProps<typeof PostTargetPicker>['assets']

/** The last PUT body's entry for one platform. */
function lastSelectionFor(platform: string) {
  return putBodies.at(-1)!.targets.find((t) => t.platform === platform)!
}

describe('PostTargetPicker — the by-hand row', () => {
  it('is not offered for a platform that has a connected account', async () => {
    availableTargets = [
      option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' }),
      manualOption('facebook'),
    ]
    selectedTargets = []
    renderPicker({ assets: POST_ASSETS, caption: 'c' })
    await loaded()

    expect(screen.getByText('Acme Page')).toBeInTheDocument()
    expect(screen.queryByText(manualOption('facebook').label)).not.toBeInTheDocument()
  })

  it('stays for a platform with no account, and when it is already on the Post', async () => {
    availableTargets = [
      manualOption('youtube'),
      option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' }),
      manualOption('facebook'),
    ]
    selectedTargets = [selection(manualOption('facebook'))]
    renderPicker({ assets: POST_ASSETS, caption: 'c' })
    await loaded()

    expect(screen.getByText(manualOption('youtube').label)).toBeInTheDocument()
    expect(screen.getByText(manualOption('facebook').label)).toBeInTheDocument()
  })
})

describe('PostTargetPicker — per-destination caption and media', () => {
  it('sends a caption written for one destination, and nothing for the others', async () => {
    availableTargets = [
      option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' }),
      option({ platform: 'instagram', connectionId: 'conn-meta', label: '@acme' }),
    ]
    selectedTargets = [
      selection(option({ platform: 'facebook', connectionId: 'conn-meta' })),
      selection(option({ platform: 'instagram', connectionId: 'conn-meta' })),
    ]
    renderPicker({ assets: POST_ASSETS, caption: 'The shared caption' })
    await loaded()

    fireEvent.click(screen.getAllByRole('button', { name: /customize for this destination/i })[0]!)
    fireEvent.change(screen.getByLabelText(/caption for this destination/i), {
      target: { value: 'Just for Facebook' },
    })

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(lastSelectionFor('facebook').captionOverride).toBe('Just for Facebook')
    // Every selected target rides along on a set-replace, and the untouched one must stay inherited.
    expect(lastSelectionFor('instagram')).not.toHaveProperty('captionOverride')
  })

  it('shows the Post caption as what an uncustomised destination falls back to', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    selectedTargets = [selection(option({ platform: 'facebook', connectionId: 'conn-meta' }))]
    renderPicker({ assets: POST_ASSETS, caption: 'The shared caption' })
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: /customize for this destination/i }))

    expect(screen.getByLabelText(/caption for this destination/i)).toHaveAttribute(
      'placeholder',
      'The shared caption'
    )
    expect(screen.getByText(/using the post's caption/i)).toBeInTheDocument()
    expect(screen.getByText(/using all post media \(2\)/i)).toBeInTheDocument()
  })

  it('sends the chosen media in the order it was arranged', async () => {
    availableTargets = [option({ platform: 'instagram', connectionId: 'conn-meta', label: '@acme' })]
    selectedTargets = [selection(option({ platform: 'instagram', connectionId: 'conn-meta' }))]
    renderPicker({ assets: POST_ASSETS, caption: 'Shared' })
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: /customize for this destination/i }))
    // Unticking the first leaves the second, rather than starting from an empty selection.
    fireEvent.click(screen.getByRole('checkbox', { name: /publish square here/i }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(lastSelectionFor('instagram').assetIds).toEqual(['asset-b'])
  })

  it('reordering a selection is its own save, because order is what the platform sees', async () => {
    availableTargets = [option({ platform: 'instagram', connectionId: 'conn-meta', label: '@acme' })]
    selectedTargets = [
      {
        ...selection(option({ platform: 'instagram', connectionId: 'conn-meta' })),
        assetIds: ['asset-a', 'asset-b'],
      },
    ]
    renderPicker({ assets: POST_ASSETS, caption: 'Shared' })
    await loaded()

    // An already-customised destination opens with its editor showing.
    fireEvent.click(screen.getByRole('button', { name: /move portrait earlier/i }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(lastSelectionFor('instagram').assetIds).toEqual(['asset-b', 'asset-a'])
  })

  it('resets a destination to the Post’s media', async () => {
    availableTargets = [option({ platform: 'instagram', connectionId: 'conn-meta', label: '@acme' })]
    selectedTargets = [
      {
        ...selection(option({ platform: 'instagram', connectionId: 'conn-meta' })),
        assetIds: ['asset-a'],
      },
    ]
    renderPicker({ assets: POST_ASSETS, caption: 'Shared' })
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: /use all post media/i }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    // Absent, not an empty array: inheriting is the absence of a choice.
    expect(lastSelectionFor('instagram')).not.toHaveProperty('assetIds')
  })

  it('opens the editor already showing for a destination that differs from the Post', async () => {
    availableTargets = [option({ platform: 'instagram', connectionId: 'conn-meta', label: '@acme' })]
    selectedTargets = [
      {
        ...selection(option({ platform: 'instagram', connectionId: 'conn-meta' })),
        captionOverride: 'Grid copy',
      },
    ]
    renderPicker({ assets: POST_ASSETS, caption: 'Shared' })
    await loaded()

    // A customisation nobody can see is a customisation nobody remembers making.
    expect(screen.getByRole('button', { name: /customized for this destination/i })).toBeInTheDocument()
    expect(screen.getByLabelText(/caption for this destination/i)).toHaveValue('Grid copy')
  })

  it('disables the editor while the Post is frozen for review', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    selectedTargets = [selection(option({ platform: 'facebook', connectionId: 'conn-meta' }))]
    renderPicker({ assets: POST_ASSETS, caption: 'Shared', status: 'IN_REVIEW' })
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: /customize for this destination/i }))

    // Editing past the gate is refused by the server; disabling says so instead of 400ing.
    expect(screen.getByLabelText(/caption for this destination/i)).toBeDisabled()
  })
})

describe('workflowDeclaresPublishTargets', () => {
  it('is true for a Workflow whose asset types name a publishable platform', () => {
    expect(
      workflowDeclaresPublishTargets({
        ...VIEW,
        assetTypes: ['facebook_post', 'instagram_post', 'youtube_video', 'tiktok_post'],
      })
    ).toBe(true)
  })

  it('is false for a Workflow that only produces engineering assets', () => {
    expect(workflowDeclaresPublishTargets({ ...VIEW, assetTypes: ['github_pr'] })).toBe(false)
  })

  it('is false when the Workflow declares no asset types at all', () => {
    expect(workflowDeclaresPublishTargets(VIEW)).toBe(false)
    expect(workflowDeclaresPublishTargets(undefined)).toBe(false)
  })
})

describe('PostTargetPicker', () => {
  it('groups one row per target under its platform', async () => {
    availableTargets = [
      option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' }),
      option({ platform: 'instagram', connectionId: 'conn-meta', label: '@acme' }),
      option({ platform: 'youtube', connectionId: 'conn-yt', label: 'Acme Channel' }),
    ]
    renderPicker()
    await loaded()

    expect(await screen.findByText('Facebook')).toBeInTheDocument()
    expect(screen.getByText('Instagram')).toBeInTheDocument()
    expect(screen.getByText('YouTube')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Acme Page/ })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /@acme/ })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Acme Channel/ })).toBeInTheDocument()
  })

  it('does not offer a platform the project has no connection for', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    renderPicker()
    await loaded()

    expect(await screen.findByText('Facebook')).toBeInTheDocument()
    expect(screen.queryByText('TikTok')).not.toBeInTheDocument()
    expect(screen.queryByText('Instagram')).not.toBeInTheDocument()
  })

  it('shows two accounts on one platform as separate rows', async () => {
    availableTargets = [
      option({ platform: 'instagram', connectionId: 'conn-a', label: '@acme' }),
      option({ platform: 'instagram', connectionId: 'conn-b', label: '@acme_uk' }),
    ]
    renderPicker()
    await loaded()

    expect(await screen.findAllByRole('checkbox')).toHaveLength(2)
    expect(screen.getByRole('checkbox', { name: /@acme$/ })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /@acme_uk/ })).toBeInTheDocument()
  })

  it('renders an unhealthy target disabled with its health message explained', async () => {
    availableTargets = [
      option({
        platform: 'facebook',
        connectionId: 'conn-meta',
        label: 'Acme Page',
        healthStatus: 'UNHEALTHY',
        healthMessage: 'Session expired, please reconnect',
      }),
    ]
    renderPicker()
    await loaded()

    const checkbox = await screen.findByRole('checkbox', { name: /Acme Page/ })
    expect(checkbox).toBeDisabled()
    expect(screen.getByText('Session expired, please reconnect')).toBeInTheDocument()
    expect(checkbox).toHaveAccessibleDescription('Session expired, please reconnect')
  })

  it('keeps an already-selected unhealthy target actionable so it can be removed', async () => {
    const unhealthy = option({
      platform: 'facebook',
      connectionId: 'conn-meta',
      label: 'Acme Page',
      healthStatus: 'UNHEALTHY',
      healthMessage: 'Session expired',
    })
    availableTargets = [unhealthy]
    selectedTargets = [selection(unhealthy)]
    renderPicker()
    await loaded()

    const checkbox = await screen.findByRole('checkbox', { name: /Acme Page/ })
    expect(checkbox).toBeChecked()
    expect(checkbox).toBeEnabled()
  })

  it('sends the whole selection on every toggle, and only the chosen connection', async () => {
    availableTargets = [
      option({ platform: 'instagram', connectionId: 'conn-a', label: '@acme' }),
      option({ platform: 'instagram', connectionId: 'conn-b', label: '@acme_uk' }),
    ]
    renderPicker()
    await loaded()

    await userEvent.click(await screen.findByRole('checkbox', { name: /@acme_uk/ }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(putBodies[0]).toEqual({
      targets: [{ platform: 'instagram', connectionId: 'conn-b', format: 'feed' }],
    })
    await waitFor(() => expect(screen.getByRole('checkbox', { name: /@acme_uk/ })).toBeChecked())
    expect(screen.getByRole('checkbox', { name: /@acme$/ })).not.toBeChecked()
  })

  it('deselecting a target sends the remaining selection', async () => {
    const facebook = option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })
    const instagram = option({ platform: 'instagram', connectionId: 'conn-meta', label: '@acme' })
    availableTargets = [facebook, instagram]
    selectedTargets = [selection(facebook), selection(instagram)]
    renderPicker()
    await loaded()

    await userEvent.click(await screen.findByRole('checkbox', { name: /@acme/ }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(putBodies[0]).toEqual({
      targets: [{ platform: 'facebook', connectionId: 'conn-meta', format: 'feed' }],
    })
  })

  it('warns that editing an approved Post sends it back for review', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    renderPicker({ status: 'APPROVED' })
    await loaded()

    expect(await screen.findByRole('alert')).toHaveTextContent(/back for review/i)
  })

  it('does not warn while the Post is still a draft', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    renderPicker()
    await loaded()

    expect(await screen.findByText('Facebook')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('explains itself rather than showing a blank card when the response carries nothing', async () => {
    // Against a current backend this cannot happen — a manual destination is offered per platform
    // whether or not anything is connected. Kept so a partial or older response degrades into an
    // explanation instead of an empty card.
    renderPicker()

    expect(await screen.findByText('Nowhere to publish')).toBeInTheDocument()
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
  })

  it('offers a manual destination to a project with no connected accounts at all', async () => {
    // The case the manual lane exists for. Before it, this project could pick no target, could not
    // clear the approval gate, and its Posts could never leave In Review.
    availableTargets = [manualOption('tiktok'), manualOption('facebook')]
    renderPicker()
    await loaded()

    expect(await screen.findByRole('checkbox', { name: /TikTok \(manual\)/ })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /Facebook \(manual\)/ })).toBeInTheDocument()
  })

  it('sends a null connectionId when a manual destination is picked', async () => {
    availableTargets = [manualOption('tiktok')]
    renderPicker()
    await loaded()

    await userEvent.click(await screen.findByRole('checkbox', { name: /TikTok \(manual\)/ }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    // Null, not the string "manual": the platform alone identifies a manual destination, and the
    // backend's CHECK constraint ties a null connection to the MANUAL lane.
    expect(putBodies[0].targets).toEqual([{ platform: 'tiktok', connectionId: null, format: 'feed' }])
  })

  it('says plainly that a manual destination will not publish itself', async () => {
    availableTargets = [manualOption('tiktok')]
    renderPicker()
    await loaded()

    expect(await screen.findByText(/post it yourself and paste the link back/i)).toBeInTheDocument()
  })

  it('offers no TikTok publish options for a manual destination', async () => {
    // Those options are the payload we send TikTok's API. On this lane the creator sets every one of
    // them in TikTok's own composer, so offering them here would collect answers nobody reads.
    availableTargets = [manualOption('tiktok')]
    selectedTargets = [selection(manualOption('tiktok'))]
    renderPicker()
    await loaded()

    expect(screen.queryByText(/Privacy level/i)).not.toBeInTheDocument()
  })

  it('keeps the previous selection visible when the save fails', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    putRejection = { status: 400, detail: 'Not a publishable target for this project' }
    renderPicker()
    await loaded()

    const checkbox = await screen.findByRole('checkbox', { name: /Acme Page/ })
    await userEvent.click(checkbox)

    await waitFor(() => expect(checkbox).not.toBeChecked())
    expect(putBodies).toHaveLength(0)
  })
})

// ── TIK-2: per-target TikTok publish options ────────────────────────────────

describe('PostTargetPicker — TikTok publish options', () => {
  it('reveals the options for a TikTok account once it is selected', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok)]
    renderPicker()
    await loaded()

    expect(await screen.findByLabelText(/who can view this video/i)).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Comment' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Duet' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Stitch' })).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: /disclose commercial content/i })).toBeInTheDocument()
  })

  it('keeps the options out of the way until the account is chosen', async () => {
    availableTargets = [tiktokOption('acme')]
    renderPicker()
    await loaded()

    expect(await screen.findByText('TikTok')).toBeInTheDocument()
    expect(screen.queryByLabelText(/who can view this video/i)).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('checkbox', { name: /@acme/ }))
    expect(await screen.findByLabelText(/who can view this video/i)).toBeInTheDocument()
  })

  it('offers no publish options for a Facebook or YouTube target', async () => {
    const facebook = option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })
    const youtube = option({ platform: 'youtube', connectionId: 'conn-yt', label: 'Acme Channel' })
    availableTargets = [facebook, youtube]
    selectedTargets = [selection(facebook), selection(youtube)]
    renderPicker()
    await loaded()

    expect(await screen.findByText('Facebook')).toBeInTheDocument()
    expect(screen.queryByLabelText(/who can view this video/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
  })

  it('lists exactly the privacy levels TikTok reported for that creator', async () => {
    const tiktok = tiktokOption('acme', { privacyLevelOptions: ['PUBLIC_TO_EVERYONE', 'SELF_ONLY'] })
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok)]
    renderPicker()
    await loaded()

    const select = await screen.findByLabelText(/who can view this video/i)
    expect(Array.from(select.querySelectorAll('option')).map((o) => o.textContent)).toEqual([
      'Everyone',
      'Only me (private)',
    ])
  })

  it('preselects the first privacy level the account allows', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok)]
    renderPicker()
    await loaded()

    expect(await screen.findByLabelText(/who can view this video/i)).toHaveValue('PUBLIC_TO_EVERYONE')
  })

  it('sends the chosen options alongside the whole selection', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok)]
    renderPicker()
    await loaded()

    await userEvent.selectOptions(
      await screen.findByLabelText(/who can view this video/i),
      'PUBLIC_TO_EVERYONE'
    )

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(putBodies[0].targets).toEqual([
      {
        platform: 'tiktok',
        connectionId: 'acme',
        format: 'feed',
        publishOptions: {
          privacyLevel: 'PUBLIC_TO_EVERYONE',
          disableComment: false,
          disableDuet: false,
          disableStitch: false,
          brandContentToggle: false,
          brandOrganicToggle: false,
          isAigc: false,
        },
      },
    ])
  })

  it('carries no publish options for a non-TikTok target', async () => {
    const facebook = option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })
    availableTargets = [facebook]
    renderPicker()
    await loaded()

    await userEvent.click(await screen.findByRole('checkbox', { name: /Acme Page/ }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(putBodies[0].targets[0]).not.toHaveProperty('publishOptions')
  })

  it('holds independent options for two TikTok accounts on one Post', async () => {
    const acme = tiktokOption('acme')
    const acmeUk = tiktokOption('acme_uk', { privacyLevelOptions: ['PUBLIC_TO_EVERYONE'] })
    availableTargets = [acme, acmeUk]
    selectedTargets = [selection(acme), selection(acmeUk)]
    renderPicker()
    await loaded()

    const selects = await screen.findAllByLabelText(/who can view this video/i)
    expect(selects).toHaveLength(2)
    // The second creator reports fewer levels, and gets only those.
    expect(Array.from(selects[1].querySelectorAll('option'))).toHaveLength(1)

    await userEvent.selectOptions(selects[0], 'SELF_ONLY')

    await waitFor(() => expect(putBodies).toHaveLength(1))
    const [first, second] = putBodies[0].targets as Array<{
      connectionId: string
      publishOptions: { privacyLevel: string | null }
    }>
    expect(first.connectionId).toBe('acme')
    expect(first.publishOptions.privacyLevel).toBe('SELF_ONLY')
    expect(second.connectionId).toBe('acme_uk')
    // The untouched second account still saves with its own default (its only reported level) rather
    // than a blank privacy level — the picker never sends "nobody chose" once the audience select is
    // shown pre-selected.
    expect(second.publishOptions.privacyLevel).toBe('PUBLIC_TO_EVERYONE')
  })

  it('hydrates the options already saved on the Post', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    selectedTargets = [
      {
        ...selection(tiktok),
        publishOptions: {
          privacyLevel: 'MUTUAL_FOLLOW_FRIENDS',
          disableStitch: true,
          brandOrganicToggle: true,
        },
      },
    ]
    renderPicker()
    await loaded()

    expect(await screen.findByLabelText(/who can view this video/i)).toHaveValue(
      'MUTUAL_FOLLOW_FRIENDS'
    )
    expect(screen.getByRole('switch', { name: 'Stitch' })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: 'Your Brand' })).toBeChecked()
  })

  it('explains that branded content cannot be posted privately', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    selectedTargets = [
      { ...selection(tiktok), publishOptions: { privacyLevel: 'SELF_ONLY', brandContentToggle: true } },
    ]
    renderPicker()
    await loaded()

    // The options panel explains it right at the toggle; the embedded consent disclosure also flags
    // it as blocking, so both alerts carry the same explanation.
    const alerts = await screen.findAllByRole('alert')
    const brandedAlert = alerts.find((a) => /can.t be posted privately/i.test(a.textContent ?? ''))
    expect(brandedAlert).toHaveTextContent(/branded content/i)
  })

  it('reports each TikTok target upward so the consent step can name it', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok, option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    selectedTargets = [selection(tiktok)]
    const onTikTokChange = vi.fn()
    renderPicker({ onTikTokChange })

    await loaded()
    await waitFor(() => expect(onTikTokChange.mock.calls.at(-1)![0]).toHaveLength(1))
    const reported = onTikTokChange.mock.calls.at(-1)![0]
    expect(reported[0]).toMatchObject({
      connectionId: 'acme',
      label: '@acme',
      creatorNickname: 'acme',
    })
    expect(reported[0].problem).toMatch(/who can see/i)
  })

  it('reports nothing to consent to when no TikTok account is selected', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    const onTikTokChange = vi.fn()
    renderPicker({ onTikTokChange })
    await loaded()

    await waitFor(() => expect(onTikTokChange).toHaveBeenCalledWith([]))
  })
})

// ── post formats ─────────────────────────────────────────────────────────────

describe('PostTargetPicker — post formats', () => {
  it('hides the format selector for a platform that only offers feed', async () => {
    const youtube = option({ platform: 'youtube', connectionId: 'conn-yt', label: 'Acme Channel', formats: ['feed'] })
    availableTargets = [youtube]
    selectedTargets = [selection(youtube)]
    renderPicker()
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: /customize for this destination/i }))
    expect(screen.queryByRole('radiogroup', { name: /post format/i })).not.toBeInTheDocument()
  })

  it('offers a format selector for a platform with reel and story', async () => {
    const instagram = option({
      platform: 'instagram',
      connectionId: 'conn-meta',
      label: '@acme',
      formats: ['feed', 'reel', 'story'],
    })
    availableTargets = [instagram]
    selectedTargets = [selection(instagram)]
    renderPicker()
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: /customize for this destination/i }))
    expect(screen.getByRole('radiogroup', { name: /post format/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: 'Story' })).toBeInTheDocument()
  })

  it('sends the chosen format on save', async () => {
    const instagram = option({
      platform: 'instagram',
      connectionId: 'conn-meta',
      label: '@acme',
      formats: ['feed', 'reel', 'story'],
    })
    availableTargets = [instagram]
    selectedTargets = [selection(instagram)]
    renderPicker()
    await loaded()

    fireEvent.click(screen.getByRole('button', { name: /customize for this destination/i }))
    fireEvent.click(screen.getByRole('radio', { name: 'Story' }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(lastSelectionFor('instagram').format).toBe('story')
  })

  it('sends feed by default when a target never touched the selector', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    renderPicker()
    await loaded()

    await userEvent.click(await screen.findByRole('checkbox', { name: /Acme Page/ }))

    await waitFor(() => expect(putBodies).toHaveLength(1))
    expect(lastSelectionFor('facebook').format).toBe('feed')
  })

  it('badges a reel and a story destination, and badges nothing for feed', async () => {
    const instagram = option({
      platform: 'instagram',
      connectionId: 'conn-meta',
      label: '@acme',
      formats: ['feed', 'reel', 'story'],
    })
    const facebook = option({ platform: 'facebook', connectionId: 'conn-fb', label: 'Acme Page' })
    availableTargets = [instagram, facebook]
    selectedTargets = [
      { ...selection(instagram), format: 'reel' },
      { ...selection(facebook), format: 'feed' },
    ]
    renderPicker()
    await loaded()

    expect(screen.getByText('Reel')).toBeInTheDocument()
    expect(screen.queryByText('Story')).not.toBeInTheDocument()
    expect(screen.queryByText('Feed')).not.toBeInTheDocument()
  })
})

// ── outcomes: once a target leaves PENDING, its row shows what happened ────────

describe('PostTargetPicker — outcomes', () => {
  const retryButton = () => screen.queryByRole('button', { name: /retry failed/i })

  it('shows a clickable permalink and no retry button for a fully published Post', async () => {
    const ig = option({ platform: 'instagram', connectionId: 'conn-ig', label: '@acme' })
    const yt = option({ platform: 'youtube', connectionId: 'conn-yt', label: 'Acme Channel' })
    availableTargets = [ig, yt]
    selectedTargets = [
      { ...selection(ig), state: 'PUBLISHED', permalink: 'https://instagram.com/p/abc' },
      { ...selection(yt), state: 'PUBLISHED', permalink: 'https://youtube.com/watch?v=xyz' },
    ]
    renderPicker()
    await loaded()

    const igLink = await screen.findByRole('link', { name: /instagram\.com\/p\/abc/i })
    expect(igLink).toHaveAttribute('href', 'https://instagram.com/p/abc')
    expect(screen.getAllByText('Published')).toHaveLength(2)
    expect(retryButton()).not.toBeInTheDocument()
  })

  it('shows the successful permalink, the failed error verbatim, and a retry button on a mixed Post', async () => {
    const ig = option({ platform: 'instagram', connectionId: 'conn-ig', label: '@acme' })
    const yt = option({ platform: 'youtube', connectionId: 'conn-yt', label: 'Acme Channel' })
    availableTargets = [ig, yt]
    selectedTargets = [
      { ...selection(ig), state: 'PUBLISHED', permalink: 'https://instagram.com/p/abc' },
      {
        ...selection(yt),
        state: 'FAILED',
        errorMessage: 'The user has exceeded the number of videos they may upload.',
      },
    ]
    renderPicker()
    await loaded()

    expect(await screen.findByRole('link', { name: /instagram\.com\/p\/abc/i })).toBeVisible()
    expect(
      screen.getByText('The user has exceeded the number of videos they may upload.')
    ).toBeVisible()
    expect(screen.getByText('Failed')).toBeVisible()
    expect(retryButton()).toBeInTheDocument()
  })

  it('calls the retry endpoint once and refreshes the row', async () => {
    const yt = option({ platform: 'youtube', connectionId: 'conn-yt', label: 'Acme Channel' })
    const failed = { ...selection(yt), state: 'FAILED', errorMessage: 'Quota exceeded' }
    availableTargets = [yt]
    selectedTargets = [failed]
    retryResult = {
      workItemId: WORK_ITEM,
      status: 'SCHEDULED',
      retriedCount: 1,
      targets: [{ ...failed, state: 'PENDING', errorMessage: null }],
    }
    const onChanged = vi.fn()
    // The retry resets this target's wire state to PENDING — the same value an unscheduled selection
    // carries — so the item-level status is what says this Post has already been scheduled.
    renderPicker({ onChanged, status: 'APPROVED' })
    await loaded()

    await userEvent.click(await screen.findByRole('button', { name: /retry failed/i }))

    await waitFor(() => expect(screen.queryByText('Quota exceeded')).not.toBeInTheDocument())
    expect(retryCalls).toBe(1)
    expect(screen.getByText('Waiting')).toBeVisible()
    expect(retryButton()).not.toBeInTheDocument()
    expect(onChanged).toHaveBeenCalled()
  })

  it('shows in-flight state and no retry button when nothing has failed yet', async () => {
    const ig = option({ platform: 'instagram', connectionId: 'conn-ig', label: '@acme' })
    const yt = option({ platform: 'youtube', connectionId: 'conn-yt', label: 'Acme Channel' })
    availableTargets = [ig, yt]
    selectedTargets = [
      { ...selection(ig), state: 'PENDING' },
      { ...selection(yt), state: 'HANDED_OFF' },
    ]
    renderPicker()
    await loaded()

    expect(screen.queryByText('Waiting')).not.toBeInTheDocument()
    expect(await screen.findByText('Handed off')).toBeVisible()
    expect(retryButton()).not.toBeInTheDocument()
  })

  it('renders a REVOKED target distinctly from a failure', async () => {
    const ig = option({ platform: 'instagram', connectionId: 'conn-ig', label: '@acme' })
    availableTargets = [ig]
    selectedTargets = [{ ...selection(ig), state: 'REVOKED' }]
    renderPicker()
    await loaded()

    expect(await screen.findByText('Taken back')).toBeVisible()
    expect(screen.queryByText('Failed')).not.toBeInTheDocument()
    expect(retryButton()).not.toBeInTheDocument()
  })

  it('asks the reader to post a manual destination that has come due, and records the link', async () => {
    const manual = manualOption('tiktok')
    availableTargets = [manual]
    selectedTargets = [{ ...selection(manual), state: 'AWAITING_MANUAL' }]
    renderPicker()
    await loaded()

    expect(await screen.findByText('Post it now')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Mark published/i }))
    await userEvent.type(
      screen.getByLabelText(/Link to the published post/i),
      'https://tiktok.com/@acme/video/1'
    )
    await userEvent.click(screen.getByRole('button', { name: /Record as published/i }))

    await waitFor(() => expect(manualCalls).toHaveLength(1))
    expect(manualCalls[0].body.permalink).toBe('https://tiktok.com/@acme/video/1')
    expect(await screen.findByText('Published')).toBeInTheDocument()
  })

  it('offers no manual controls on a target that is still pending', async () => {
    const ig = option({ platform: 'instagram', connectionId: 'conn-ig', label: '@acme' })
    availableTargets = [ig]
    selectedTargets = [{ ...selection(ig), state: 'PENDING' }]
    renderPicker()
    await loaded()

    expect(screen.queryByRole('button', { name: /Mark published/i })).not.toBeInTheDocument()
  })
})

// ── the TikTok consent disclosure, embedded directly under the TikTok row ──────

describe('PostTargetPicker — TikTok consent disclosure', () => {
  it('shows the consent disclosure under the TikTok row once a TikTok account is selected', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok)]
    renderPicker({ caption: 'Launch copy' })
    await loaded()

    expect(await screen.findByText('Confirm your TikTok post')).toBeInTheDocument()
    expect(screen.getByText(/you are posting to/i)).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /consent/i })).toBeInTheDocument()
  })

  it('renders no consent disclosure when no TikTok account is selected', async () => {
    availableTargets = [option({ platform: 'facebook', connectionId: 'conn-meta', label: 'Acme Page' })]
    renderPicker()
    await loaded()

    expect(screen.queryByText('Confirm your TikTok post')).not.toBeInTheDocument()
  })

  it('records consent through the API and reports it upward', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    // A resolved privacy level — the consent checkbox stays disabled until this account has one.
    selectedTargets = [{ ...selection(tiktok), publishOptions: { privacyLevel: 'PUBLIC_TO_EVERYONE' } }]
    const onTikTokConsentChange = vi.fn()
    renderPicker({ onTikTokConsentChange })
    await loaded()

    const checkbox = await screen.findByRole('checkbox', { name: /consent/i })
    await userEvent.click(checkbox)

    await waitFor(() => expect(consentPutBodies).toEqual([{ consented: true }]))
    expect(onTikTokConsentChange).toHaveBeenLastCalledWith(true)
  })
})
