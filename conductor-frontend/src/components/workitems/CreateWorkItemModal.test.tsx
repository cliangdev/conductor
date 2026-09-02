import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'
import { CreateWorkItemModal } from './CreateWorkItemModal'

const { pushSpy, toastErrorSpy } = vi.hoisted(() => ({ pushSpy: vi.fn(), toastErrorSpy: vi.fn() }))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: pushSpy }) }))
vi.mock('@/components/ui/toast', async () => {
  const actual = await vi.importActual<typeof import('@/components/ui/toast')>('@/components/ui/toast')
  return { ...actual, toastError: toastErrorSpy }
})

const API = 'https://api.test'
let postBodies: unknown[] = []
let postRejection: { status: number; detail: string } | null = null

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  if ((init?.method ?? 'GET') === 'POST') {
    postBodies.push(JSON.parse(String(init?.body ?? '{}')))
    if (postRejection)
      return {
        ok: false,
        status: postRejection.status,
        headers: { get: () => 'application/json' },
        json: async () => ({ detail: postRejection!.detail }),
      }
    return {
      ok: true,
      status: 201,
      headers: { get: () => 'application/json' },
      json: async () => ({ id: 'wi-9', displayId: 'CLT-9' }),
    }
  }
  throw new Error(`unexpected ${init?.method} ${url}`)
})

const MARKETING: WorkflowView = {
  slug: 'MARKETING', noun: 'Post', area: 'MARKETING', defaultView: 'calendar', version: 1,
  types: ['POST'], statuses: [], transitions: [],
}
const ENGINEERING: WorkflowView = { ...MARKETING, slug: 'ENGINEERING', noun: 'Issue', area: 'ENGINEERING', types: ['PRD', 'BUG'] }

beforeEach(() => {
  postBodies = []
  postRejection = null
  pushSpy.mockClear()
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

function renderModal(view: WorkflowView = MARKETING) {
  const onCreated = vi.fn()
  const onOpenChange = vi.fn()
  render(
    <CreateWorkItemModal
      open
      onOpenChange={onOpenChange}
      projectId="proj-1"
      workflowSlug={view.slug}
      workflowView={view}
      detailArea={view.area ?? view.slug}
      noun={view.noun}
      token="tok"
      onCreated={onCreated}
    />
  )
  return { onCreated, onOpenChange }
}

describe('CreateWorkItemModal', () => {
  it('names itself after the Workflow noun rather than a hardcoded one', () => {
    renderModal()
    expect(screen.getByText('New Post')).toBeInTheDocument()

    render(<></>)
  })

  it('creates the item bound to the Workflow the list is scoped to', async () => {
    const { onCreated } = renderModal()

    await userEvent.type(screen.getByLabelText(/^Title/), 'Autumn launch')
    await userEvent.click(screen.getByRole('button', { name: /Create post/i }))

    await waitFor(() => expect(postBodies).toHaveLength(1))
    // `workflow` is required by the server and is what binds the item's whole lifecycle.
    expect(postBodies[0]).toEqual({ type: 'POST', title: 'Autumn launch', workflow: 'MARKETING' })
    expect(onCreated).toHaveBeenCalled()
  })

  it('opens the new item, which is the only way to see one with no date yet', async () => {
    // On a calendar-first Workflow a brand new item is on no day, so leaving the user on the list would
    // look like nothing happened.
    renderModal()
    await userEvent.type(screen.getByLabelText(/^Title/), 'Autumn launch')
    await userEvent.click(screen.getByRole('button', { name: /Create post/i }))

    await waitFor(() => expect(pushSpy).toHaveBeenCalledWith('/app/projects/proj-1/marketing/posts/CLT-9'))
  })

  it('hides the type field when the Workflow declares only one', () => {
    renderModal()
    expect(screen.queryByLabelText(/^Type/)).not.toBeInTheDocument()
  })

  it('asks which type when the Workflow declares several', async () => {
    renderModal(ENGINEERING)
    expect(screen.getByLabelText(/^Type/)).toBeInTheDocument()

    await userEvent.type(screen.getByLabelText(/^Title/), 'A bug')
    await userEvent.selectOptions(screen.getByLabelText(/^Type/), 'BUG')
    await userEvent.click(screen.getByRole('button', { name: /Create issue/i }))

    await waitFor(() => expect(postBodies).toHaveLength(1))
    expect(postBodies[0]).toMatchObject({ type: 'BUG', workflow: 'ENGINEERING' })
  })

  it('will not submit an empty title', async () => {
    renderModal()
    expect(screen.getByRole('button', { name: /Create post/i })).toBeDisabled()
    expect(postBodies).toHaveLength(0)
  })

  it('keeps the dialog open and says why when the server refuses', async () => {
    postRejection = { status: 400, detail: 'workflow is required' }
    const { onOpenChange } = renderModal()

    await userEvent.type(screen.getByLabelText(/^Title/), 'Autumn launch')
    await userEvent.click(screen.getByRole('button', { name: /Create post/i }))

    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(toastErrorSpy.mock.calls[0][0]).toContain('workflow is required')
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
    expect(pushSpy).not.toHaveBeenCalled()
  })
})
