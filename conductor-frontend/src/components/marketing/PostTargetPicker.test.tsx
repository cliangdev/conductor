import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
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
    label: overrides.connectionId,
    lane: overrides.platform === 'facebook' || overrides.platform === 'youtube' ? 'NATIVE' : 'APP_MANAGED',
    ...overrides,
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
let putBodies: Array<{ targets: Array<{ platform: string; connectionId: string }> }> = []
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
    selectedTargets = body.targets.map((t: { platform: string; connectionId: string }) =>
      selection(option({ platform: t.platform as PublishTargetOption['platform'], connectionId: t.connectionId }))
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
    expect(putBodies[0]).toEqual({ targets: [{ platform: 'instagram', connectionId: 'conn-b' }] })
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
    expect(putBodies[0]).toEqual({ targets: [{ platform: 'facebook', connectionId: 'conn-meta' }] })
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

  it('offers nothing but guidance when the project has no connected accounts', async () => {
    renderPicker()

    expect(await screen.findByText('No connected accounts')).toBeInTheDocument()
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
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
