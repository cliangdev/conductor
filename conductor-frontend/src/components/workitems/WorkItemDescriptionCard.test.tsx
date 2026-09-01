import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'
import { WorkItemDescriptionCard } from './WorkItemDescriptionCard'

const { toastErrorSpy } = vi.hoisted(() => ({ toastErrorSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async () => {
  const actual = await vi.importActual<typeof import('@/components/ui/toast')>('@/components/ui/toast')
  return { ...actual, toastError: toastErrorSpy }
})

const API = 'https://api.test'
let patchBodies: unknown[] = []
let patchRejection: { status: number; detail: string } | null = null

const fetchMock = vi.fn(async (_url: string, init?: RequestInit) => {
  patchBodies.push(JSON.parse(String(init?.body ?? '{}')))
  if (patchRejection)
    return {
      ok: false, status: patchRejection.status,
      headers: { get: () => 'application/json' },
      json: async () => ({ detail: patchRejection!.detail }),
    }
  return { ok: true, status: 200, headers: { get: () => 'application/json' }, json: async () => ({}) }
})

const MARKETING: WorkflowView = {
  slug: 'MARKETING', noun: 'Post', area: 'MARKETING', defaultView: 'calendar', version: 1,
  types: ['POST'], assetTypes: ['instagram_post'],
  statuses: [
    { id: 'IN_REVIEW', label: 'In Review', category: 'in_progress' },
    { id: 'APPROVED', label: 'Approved', category: 'in_progress' },
  ],
  transitions: [{ from: 'IN_REVIEW', to: 'APPROVED', label: 'Approve', requiresReview: true }],
}
const ENGINEERING: WorkflowView = { ...MARKETING, slug: 'ENGINEERING', noun: 'Issue', assetTypes: ['github_pr'] }

beforeEach(() => {
  patchBodies = []
  patchRejection = null
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

function renderCard(props: Partial<React.ComponentProps<typeof WorkItemDescriptionCard>> = {}) {
  const onSaved = vi.fn()
  render(
    <WorkItemDescriptionCard
      projectId="proj-1"
      workItemId="post-1"
      token="tok"
      description={null}
      status="DRAFT"
      workflowView={MARKETING}
      isCaption
      canEdit
      onSaved={onSaved}
      {...props}
    />
  )
  return { onSaved }
}

describe('WorkItemDescriptionCard', () => {
  it('calls the field a caption where the Workflow publishes', () => {
    // On a Post this field IS the post: PostPublishScheduler sends it to the platform as the text.
    renderCard()
    expect(screen.getByRole('heading', { name: 'Caption' })).toBeInTheDocument()
  })

  it('calls it a description everywhere else', () => {
    renderCard({ workflowView: ENGINEERING, isCaption: false })
    expect(screen.getByRole('heading', { name: 'Description' })).toBeInTheDocument()
  })

  it('writes the caption the user types', async () => {
    const { onSaved } = renderCard()

    await userEvent.click(screen.getByRole('button', { name: /Write the caption/i }))
    await userEvent.type(screen.getByLabelText('Caption'), 'Doors open at nine.')
    await userEvent.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(patchBodies).toHaveLength(1))
    expect(patchBodies[0]).toEqual({ description: 'Doors open at nine.' })
    expect(onSaved).toHaveBeenCalledWith('Doors open at nine.')
  })

  it('warns before an edit that would send an approved post back for review', async () => {
    // A caption edit is a publish-bundle edit: the item reverts and anything handed to a platform is
    // revoked. Said before the edit, not discovered after it.
    renderCard({ status: 'APPROVED', description: 'Original' })

    await userEvent.click(screen.getByRole('button', { name: /Edit/i }))

    expect(screen.getByText(/sends this post back for review/i)).toBeInTheDocument()
  })

  it('does not warn on a Workflow that does not publish', async () => {
    renderCard({ workflowView: ENGINEERING, isCaption: false, status: 'APPROVED', description: 'x' })

    await userEvent.click(screen.getByRole('button', { name: /Edit/i }))

    expect(screen.queryByText(/back for review/i)).not.toBeInTheDocument()
  })

  it('keeps the stored text and says why when the server refuses', async () => {
    patchRejection = { status: 422, detail: 'Media is locked while this post is Scheduled' }
    const { onSaved } = renderCard({ description: 'Original' })

    await userEvent.click(screen.getByRole('button', { name: /Edit/i }))
    await userEvent.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(toastErrorSpy.mock.calls[0][0]).toContain('locked')
    expect(onSaved).not.toHaveBeenCalled()
  })

  it('preserves the line breaks a caption was written with', () => {
    renderCard({ description: 'Line one\nLine two' })
    expect(screen.getByText(/Line one/).className).toContain('whitespace-pre-wrap')
  })

  it('offers no edit control to a reader', () => {
    renderCard({ canEdit: false, description: 'Read only' })
    expect(screen.queryByRole('button', { name: /Edit|Write the/i })).not.toBeInTheDocument()
  })
})
