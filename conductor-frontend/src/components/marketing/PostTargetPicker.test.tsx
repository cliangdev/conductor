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
      'Select who can view this video…',
      'Everyone',
      'Only me (private)',
    ])
  })

  it('preselects no privacy level', async () => {
    const tiktok = tiktokOption('acme')
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok)]
    renderPicker()
    await loaded()

    expect(await screen.findByLabelText(/who can view this video/i)).toHaveValue('')
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
    expect(Array.from(selects[1].querySelectorAll('option'))).toHaveLength(2)

    await userEvent.selectOptions(selects[0], 'SELF_ONLY')

    await waitFor(() => expect(putBodies).toHaveLength(1))
    const [first, second] = putBodies[0].targets as Array<{
      connectionId: string
      publishOptions: { privacyLevel: string | null }
    }>
    expect(first.connectionId).toBe('acme')
    expect(first.publishOptions.privacyLevel).toBe('SELF_ONLY')
    expect(second.connectionId).toBe('acme_uk')
    expect(second.publishOptions.privacyLevel).toBeNull()
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

    expect(await screen.findByRole('alert')).toHaveTextContent(/branded content/i)
    expect(screen.getByRole('alert')).toHaveTextContent(/can.t be posted privately/i)
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
