import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'
import { PublishOutcomePanel, type PublishOutcome } from './PublishOutcomePanel'

const { toastErrorSpy } = vi.hoisted(() => ({ toastErrorSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async () => {
  const actual = await vi.importActual<typeof import('@/components/ui/toast')>('@/components/ui/toast')
  return { ...actual, toastError: toastErrorSpy }
})

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
    { id: 'SCHEDULED', label: 'Scheduled', category: 'in_progress' },
    { id: 'FAILED', label: 'Failed', category: 'in_progress' },
  ],
  transitions: [],
  assetTypes: ['instagram_post', 'youtube_video'],
}

const ENGINEERING_VIEW: WorkflowView = { ...VIEW, assetTypes: ['github_pr'] }

function target(overrides: Partial<PublishOutcome> & Pick<PublishOutcome, 'platform' | 'state'>): PublishOutcome {
  return {
    id: `target-${overrides.platform}-${overrides.state}`,
    workItemId: WORK_ITEM,
    connectorId: overrides.platform === 'instagram' ? 'meta' : overrides.platform,
    connectionId: `conn-${overrides.platform}`,
    platformAccountLabel: `@${overrides.platform}-account`,
    lane: 'NATIVE',
    ...overrides,
  }
}

// ── recorded traffic ────────────────────────────────────────────────────────

let currentTargets: PublishOutcome[] = []
let retryResult: { workItemId: string; status: string; retriedCount: number; targets: PublishOutcome[] } | null = null
let retryRejection: { status: number; detail: string } | null = null
let retryCalls = 0
let manualCalls: Array<{ url: string; body: { permalink: string; publishedAt: string | null } }> = []
let manualRejection: { status: number; detail: string } | null = null

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
  if (method === 'POST' && url.includes('/manual-publish')) {
    manualCalls.push({ url, body: JSON.parse(String(init?.body ?? '{}')) })
    if (manualRejection) return jsonResponse(manualRejection.status, { detail: manualRejection.detail })
    const targetId = url.split('/publish-targets/')[1].replace('/manual-publish', '')
    const updated: PublishOutcome = {
      ...currentTargets.find((t) => t.id === targetId)!,
      state: 'PUBLISHED',
      permalink: manualCalls[manualCalls.length - 1].body.permalink,
    }
    currentTargets = currentTargets.map((t) => (t.id === targetId ? updated : t))
    return jsonResponse(200, updated)
  }
  if (method === 'POST' && url.endsWith(`/work-items/${WORK_ITEM}/publish-targets/retry`)) {
    retryCalls += 1
    if (retryRejection) return jsonResponse(retryRejection.status, { detail: retryRejection.detail })
    currentTargets = retryResult!.targets
    return jsonResponse(200, retryResult)
  }
  if (method === 'GET' && url.endsWith(`/work-items/${WORK_ITEM}/publish-targets`)) {
    return jsonResponse(200, currentTargets)
  }
  throw new Error(`unexpected fetch: ${method} ${url}`)
})

beforeEach(() => {
  currentTargets = []
  retryResult = null
  retryRejection = null
  retryCalls = 0
  manualCalls = []
  manualRejection = null
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function renderPanel(props: Partial<React.ComponentProps<typeof PublishOutcomePanel>> = {}) {
  return render(
    <PublishOutcomePanel
      projectId={PROJECT}
      workItemId={WORK_ITEM}
      token="tok"
      workflowView={VIEW}
      {...props}
    />
  )
}

const retryButton = () => screen.queryByRole('button', { name: /retry failed/i })

describe('PublishOutcomePanel', () => {
  // [auto] Each target's permalink or error renders on the Post detail
  it('renders a clickable permalink per target and no retry button for a fully published Post', async () => {
    currentTargets = [
      target({
        platform: 'instagram',
        state: 'PUBLISHED',
        permalink: 'https://instagram.com/p/abc',
        platformPostId: 'ig-1',
      }),
      target({
        platform: 'youtube',
        state: 'PUBLISHED',
        permalink: 'https://youtube.com/watch?v=xyz',
        platformPostId: 'yt-1',
      }),
    ]

    renderPanel()

    const igLink = await screen.findByRole('link', { name: /instagram\.com\/p\/abc/i })
    expect(igLink).toHaveAttribute('href', 'https://instagram.com/p/abc')
    const ytLink = screen.getByRole('link', { name: /youtube\.com\/watch/i })
    expect(ytLink).toHaveAttribute('href', 'https://youtube.com/watch?v=xyz')

    expect(screen.getAllByText('Published')).toHaveLength(2)
    expect(retryButton()).not.toBeInTheDocument()
  })

  // [auto] Retry is offered only when a target failed, and successful permalinks remain visible
  it('shows the successful permalink, the failed error verbatim, and a retry button on a mixed Post', async () => {
    currentTargets = [
      target({
        platform: 'instagram',
        state: 'PUBLISHED',
        permalink: 'https://instagram.com/p/abc',
      }),
      target({
        platform: 'youtube',
        state: 'FAILED',
        errorMessage: 'The user has exceeded the number of videos they may upload.',
      }),
    ]

    renderPanel()

    // The success is not hidden or collapsed just because the Post as a whole failed.
    const link = await screen.findByRole('link', { name: /instagram\.com\/p\/abc/i })
    expect(link).toBeVisible()
    expect(screen.getByText('Published')).toBeVisible()

    // The platform's own words, verbatim.
    expect(
      screen.getByText('The user has exceeded the number of videos they may upload.')
    ).toBeVisible()
    expect(screen.getByText('Failed')).toBeVisible()

    expect(retryButton()).toBeInTheDocument()
  })

  it('calls the retry endpoint once and refreshes the list', async () => {
    const user = userEvent.setup()
    const published = target({
      platform: 'instagram',
      state: 'PUBLISHED',
      permalink: 'https://instagram.com/p/abc',
    })
    const failed = target({ platform: 'youtube', state: 'FAILED', errorMessage: 'Quota exceeded' })
    currentTargets = [published, failed]
    retryResult = {
      workItemId: WORK_ITEM,
      status: 'SCHEDULED',
      retriedCount: 1,
      targets: [published, { ...failed, state: 'PENDING', errorMessage: null }],
    }

    const onRetried = vi.fn()
    renderPanel({ onRetried })

    await user.click(await screen.findByRole('button', { name: /retry failed/i }))

    await waitFor(() => expect(screen.queryByText('Quota exceeded')).not.toBeInTheDocument())
    expect(retryCalls).toBe(1)
    expect(screen.getByText('Waiting')).toBeVisible()
    // The published target and its permalink survive the retry untouched.
    expect(screen.getByRole('link', { name: /instagram\.com\/p\/abc/i })).toBeVisible()
    expect(retryButton()).not.toBeInTheDocument()
    expect(onRetried).toHaveBeenCalledTimes(1)
  })

  it('shows in-flight state and no retry button when nothing has failed yet', async () => {
    currentTargets = [
      target({ platform: 'instagram', state: 'PENDING', fireTime: '2026-09-01T10:00:00Z' }),
      target({ platform: 'youtube', state: 'HANDED_OFF' }),
    ]

    renderPanel()

    expect(await screen.findByText('Waiting')).toBeVisible()
    expect(screen.getByText('Handed off')).toBeVisible()
    expect(retryButton()).not.toBeInTheDocument()
    expect(screen.queryByRole('link')).not.toBeInTheDocument()
  })

  it('renders a REVOKED target distinctly from a failure', async () => {
    currentTargets = [target({ platform: 'instagram', state: 'REVOKED' })]

    renderPanel()

    expect(await screen.findByText('Taken back')).toBeVisible()
    expect(screen.queryByText('Failed')).not.toBeInTheDocument()
    // A withdrawal is not something to retry.
    expect(retryButton()).not.toBeInTheDocument()
  })

  it('opens permalinks in a new tab with rel="noopener noreferrer"', async () => {
    currentTargets = [
      target({ platform: 'instagram', state: 'PUBLISHED', permalink: 'https://instagram.com/p/abc' }),
    ]

    renderPanel()

    const link = await screen.findByRole('link', { name: /instagram\.com\/p\/abc/i })
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
  })

  it('does not render for a Workflow that declares no publish platforms', async () => {
    currentTargets = [
      target({ platform: 'instagram', state: 'PUBLISHED', permalink: 'https://instagram.com/p/abc' }),
    ]

    const { container } = renderPanel({ workflowView: ENGINEERING_VIEW })

    await waitFor(() => expect(container).toBeEmptyDOMElement())
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('renders nothing when the Post has no publish targets at all', async () => {
    currentTargets = []

    const { container } = renderPanel()

    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('surfaces a failed retry instead of swallowing it, keeping the outcomes on screen', async () => {
    const user = userEvent.setup()
    currentTargets = [target({ platform: 'youtube', state: 'FAILED', errorMessage: 'Quota exceeded' })]
    retryRejection = { status: 400, detail: 'This Post is not in a status a retry can fire from.' }

    renderPanel()

    await user.click(await screen.findByRole('button', { name: /retry failed/i }))

    await waitFor(() =>
      expect(toastErrorSpy).toHaveBeenCalledWith('This Post is not in a status a retry can fire from.')
    )
    // The outcomes stay on screen — a refused retry must not blank the panel.
    expect(screen.getByText('Quota exceeded')).toBeVisible()
    expect(retryButton()).toBeInTheDocument()
  })

  it('falls back to the connection id when a target carries no account label', async () => {
    currentTargets = [
      target({
        platform: 'instagram',
        state: 'PUBLISHED',
        platformAccountLabel: null,
        permalink: 'https://instagram.com/p/abc',
      }),
    ]

    renderPanel()

    expect(await screen.findByText('conn-instagram')).toBeVisible()
  })

  // ── the MANUAL lane: a destination a human posts by hand (MKT-2) ──────────────────────────

  function manualTarget(state: string, overrides: Partial<PublishOutcome> = {}): PublishOutcome {
    return {
      id: 'target-manual-tiktok',
      workItemId: WORK_ITEM,
      platform: 'tiktok',
      connectorId: null,
      connectionId: null,
      platformAccountLabel: 'TikTok (manual)',
      lane: 'MANUAL',
      state,
      ...overrides,
    }
  }

  it('asks the reader to post a manual destination that has come due', async () => {
    currentTargets = [manualTarget('AWAITING_MANUAL')]
    renderPanel()

    expect(await screen.findByText(/publishes by hand/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Mark published/i })).toBeInTheDocument()
    expect(screen.getByText('Post it now')).toBeInTheDocument()
  })

  it('records the link a human pastes back and shows the destination as published', async () => {
    currentTargets = [manualTarget('AWAITING_MANUAL')]
    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: /Mark published/i }))
    await userEvent.type(
      screen.getByLabelText(/Link to the published post/i),
      'https://tiktok.com/@acme/video/1'
    )
    await userEvent.click(screen.getByRole('button', { name: /Record as published/i }))

    await waitFor(() => expect(manualCalls).toHaveLength(1))
    expect(manualCalls[0].body.permalink).toBe('https://tiktok.com/@acme/video/1')
    expect(manualCalls[0].url).toContain('/publish-targets/target-manual-tiktok/manual-publish')
    expect(await screen.findByText('Published')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /tiktok.com/ })).toHaveAttribute(
      'href',
      'https://tiktok.com/@acme/video/1'
    )
  })

  it('will not record a manual publish without a link', async () => {
    // The link is the only record this destination ever went out — there is no platform to ask.
    currentTargets = [manualTarget('AWAITING_MANUAL')]
    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: /Mark published/i }))

    expect(screen.getByRole('button', { name: /Record as published/i })).toBeDisabled()
    expect(manualCalls).toHaveLength(0)
  })

  it('keeps the row exactly as it was when recording fails, and says why', async () => {
    currentTargets = [manualTarget('AWAITING_MANUAL')]
    manualRejection = { status: 422, detail: 'This destination was taken back down' }
    renderPanel()

    await userEvent.click(await screen.findByRole('button', { name: /Mark published/i }))
    await userEvent.type(screen.getByLabelText(/Link to the published post/i), 'https://tiktok.com/x')
    await userEvent.click(screen.getByRole('button', { name: /Record as published/i }))

    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(toastErrorSpy.mock.calls[0][0]).toContain('taken back down')
    expect(screen.getByText('Post it now')).toBeInTheDocument()
  })

  it('offers no manual controls on a target that publishes automatically', async () => {
    // The refusal the backend enforces, mirrored here so the button never appears to promise it.
    currentTargets = [target({ platform: 'instagram', state: 'PENDING' })]
    renderPanel()

    await screen.findByText('Waiting')
    expect(screen.queryByRole('button', { name: /Mark published/i })).not.toBeInTheDocument()
  })

  it('offers no manual controls on a manual target that is not due yet', async () => {
    currentTargets = [manualTarget('PENDING')]
    renderPanel()

    await screen.findByText('Waiting')
    expect(screen.queryByRole('button', { name: /Mark published/i })).not.toBeInTheDocument()
  })

  it('shows a manual destination that was already published as published, with its link', async () => {
    currentTargets = [
      manualTarget('PUBLISHED', { permalink: 'https://tiktok.com/@acme/video/9' }),
    ]
    renderPanel()

    expect(await screen.findByText('Published')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Mark published/i })).not.toBeInTheDocument()
  })

})
