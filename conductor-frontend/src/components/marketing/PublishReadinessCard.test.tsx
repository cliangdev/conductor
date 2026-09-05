import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'
import { PublishReadinessCard, type PublishPreflight } from './PublishReadinessCard'

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
  defaultView: 'calendar',
  version: 1,
  types: ['POST'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'in_progress' },
    { id: 'SCHEDULED', label: 'Scheduled', category: 'in_progress' },
  ],
  transitions: [],
  assetTypes: ['instagram_post'],
}

function preflight(overrides: Partial<PublishPreflight> = {}): PublishPreflight {
  return {
    publishing: true,
    ready: true,
    blockers: [],
    warnings: [],
    nextTransition: { to: 'IN_REVIEW', label: 'Submit for review', requiresReview: false },
    consent: { required: false, verdict: 'NOT_REQUIRED' },
    review: { gated: true, assignedReviewers: 0, satisfied: false, reviewerRole: 'REVIEWER' },
    earliestFireTime: '2026-09-04T12:10:00Z',
    ...overrides,
  }
}

let current: PublishPreflight = preflight()
let patches: Array<Record<string, unknown>> = []
let patchRejection: { status: number; detail: string } | null = null

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (method === 'GET' && url.endsWith('/publish-preflight')) {
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => current }
  }
  if (method === 'PATCH') {
    patches.push(JSON.parse(String(init?.body ?? '{}')))
    if (patchRejection) {
      return { ok: false, status: patchRejection.status, headers: { get: () => 'application/json' }, json: async () => ({ detail: patchRejection!.detail }) }
    }
    return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}) }
  }
  throw new Error(`unexpected ${method} ${url}`)
})

beforeEach(() => {
  current = preflight()
  patches = []
  patchRejection = null
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

function renderCard(props: Partial<React.ComponentProps<typeof PublishReadinessCard>> = {}) {
  const onStatusChanged = vi.fn()
  render(
    <PublishReadinessCard
      projectId={PROJECT}
      workItemId={WORK_ITEM}
      token="t"
      status="DRAFT"
      userRole="ADMIN"
      workflowView={VIEW}
      onStatusChanged={onStatusChanged}
      {...props}
    />
  )
  return { onStatusChanged }
}

describe('PublishReadinessCard', () => {
  it('shows every blocker verbatim and offers the next move disabled with the first as its reason', async () => {
    current = preflight({
      ready: false,
      blockers: [
        { code: 'NO_MEDIA', message: 'no uploaded media file is attached — upload at least one image or video' },
        { code: 'NO_TARGETS', message: 'no publish target is selected — pick at least one account to publish to' },
      ],
      warnings: [{ code: 'MEDIA_ADVISORY', message: 'YouTube will treat this as a Short', targetId: 't-1' }],
    })
    renderCard()

    expect(await screen.findByText('Not ready yet')).toBeInTheDocument()
    expect(screen.getByText(/2 things to fix/)).toBeInTheDocument()
    expect(screen.getByText(/no uploaded media file is attached/)).toBeInTheDocument()
    expect(screen.getByText(/no publish target is selected/)).toBeInTheDocument()
    expect(screen.getByText(/YouTube will treat this as a Short/)).toBeInTheDocument()
    const button = screen.getByRole('button', { name: 'Submit for review' })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('title', expect.stringContaining('no uploaded media file'))
  })

  it('takes the next move when ready, reports the new status, and re-asks the server', async () => {
    const { onStatusChanged } = renderCard()
    const button = await screen.findByRole('button', { name: 'Submit for review' })
    expect(button).toBeEnabled()
    expect(screen.getByText(/Assign a reviewer/)).toBeInTheDocument()

    current = preflight({ nextTransition: { to: 'APPROVED', label: 'Approve', requiresReview: true }, review: { gated: true, assignedReviewers: 1, satisfied: false } })
    await userEvent.click(button)

    await waitFor(() => expect(patches).toEqual([{ status: 'IN_REVIEW' }]))
    expect(onStatusChanged).toHaveBeenCalledWith('IN_REVIEW')
    // A review-gated next move is the reviewer's verdict, not a button here.
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Approve' })).not.toBeInTheDocument())
    expect(screen.getByText(/Waiting on 1 reviewer/)).toBeInTheDocument()
  })

  it('surfaces a refused move as a toast and stays on the server’s answer', async () => {
    patchRejection = { status: 422, detail: 'Cannot move Post to IN_REVIEW: the fire time is less than 10 minutes in the future' }
    renderCard()
    await userEvent.click(await screen.findByRole('button', { name: 'Submit for review' }))

    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(String(toastErrorSpy.mock.calls[0]![0])).toContain('less than 10 minutes')
  })

  it('renders nothing for a Work Item whose Workflow does not publish, and no button for a reviewer', async () => {
    current = preflight({ publishing: false, nextTransition: null })
    const { container } = render(
      <PublishReadinessCard projectId={PROJECT} workItemId={WORK_ITEM} token="t" status="DRAFT" userRole="ADMIN" workflowView={VIEW} />
    )
    await waitFor(() => expect(fetchMock).toHaveBeenCalled())
    expect(container.querySelector('[data-testid="publish-readiness"]')).toBeNull()

    current = preflight()
    renderCard({ userRole: 'REVIEWER' })
    expect(await screen.findByText('Ready to publish')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit for review' })).not.toBeInTheDocument()
  })

  it('re-asks the server when refreshKey changes', async () => {
    const { rerender } = render(
      <PublishReadinessCard projectId={PROJECT} workItemId={WORK_ITEM} token="t" status="DRAFT" userRole="ADMIN" workflowView={VIEW} refreshKey={1} />
    )
    await screen.findByText('Ready to publish')
    const before = fetchMock.mock.calls.length
    current = preflight({ ready: false, blockers: [{ code: 'NO_MEDIA', message: 'no uploaded media file is attached' }] })
    rerender(
      <PublishReadinessCard projectId={PROJECT} workItemId={WORK_ITEM} token="t" status="DRAFT" userRole="ADMIN" workflowView={VIEW} refreshKey={2} />
    )
    await screen.findByText('Not ready yet')
    expect(fetchMock.mock.calls.length).toBeGreaterThan(before)
  })
})
