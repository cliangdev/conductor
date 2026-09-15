import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MARKETING_VIEW, jsonResponse } from '@/components/marketing/test-fixtures'
import { TikTokPublishGateProvider } from '@/components/marketing/TikTokConsentStep'
import { DropdownMenuItem } from '@/components/ui/dropdown-menu'
import { WorkItemMoreMenu } from './WorkItemMoreMenu'

const { toastErrorSpy } = vi.hoisted(() => ({ toastErrorSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/toast')>()
  return { ...actual, toastError: toastErrorSpy }
})

const API = 'https://api.test'
let transitions: Array<{ toStatus: string; label: string; requiresReview?: boolean }> = []
let patches: Array<Record<string, unknown>> = []

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (method === 'GET' && url.endsWith('/available-transitions')) {
    return jsonResponse(200, { workflow: 'MARKETING', currentStatus: 'SCHEDULED', transitions })
  }
  if (method === 'PATCH') {
    patches.push(JSON.parse(String(init?.body)))
    return jsonResponse(200, {})
  }
  throw new Error(`unexpected fetch: ${method} ${url}`)
})

beforeEach(() => {
  transitions = [
    { toStatus: 'APPROVED', label: 'Unschedule' },
    { toStatus: 'IN_REVIEW', label: 'Submit for review' },
    { toStatus: 'PUBLISHED', label: 'Approve', requiresReview: true },
  ]
  patches = []
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function renderMenu(props: Partial<React.ComponentProps<typeof WorkItemMoreMenu>> = {}, gate: string | null = null) {
  const onStatusChanged = vi.fn()
  render(
    <TikTokPublishGateProvider reason={gate}>
      <WorkItemMoreMenu
        projectId="project-1"
        issueId="post-1"
        currentStatus="SCHEDULED"
        userRole="CREATOR"
        token="tok"
        workflowView={MARKETING_VIEW}
        onStatusChanged={onStatusChanged}
        noun="Post"
        {...props}
      />
    </TikTokPublishGateProvider>
  )
  return { onStatusChanged }
}

async function open() {
  await userEvent.click(await screen.findByRole('button', { name: 'More actions' }))
}

describe('WorkItemMoreMenu', () => {
  it('offers every move but the primary button’s and the reviewer’s verdict, then Delete', async () => {
    const onDelete = vi.fn()
    renderMenu({ excludeStatus: 'IN_REVIEW', onDelete })
    await open()
    expect(await screen.findByRole('menuitem', { name: /Unschedule/ })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /Submit for review/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: /Approve/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('menuitem', { name: 'Delete this post' }))
    expect(onDelete).toHaveBeenCalled()
  })

  it('moves the item and reports the new status', async () => {
    const { onStatusChanged } = renderMenu()
    await open()
    await userEvent.click(await screen.findByRole('menuitem', { name: /Unschedule/ }))
    await waitFor(() => expect(patches).toEqual([{ status: 'APPROVED' }]))
    expect(onStatusChanged).toHaveBeenCalledWith('APPROVED')
  })

  it('disables a move the gate refuses and says why, in the gate’s own words', async () => {
    renderMenu({ blockedMoves: { APPROVED: 'Nothing has been scheduled yet.' } })
    await open()
    const item = await screen.findByRole('menuitem', { name: /Unschedule/ })
    expect(item).toHaveAttribute('aria-disabled', 'true')
    expect(item).toHaveTextContent('Nothing has been scheduled yet.')
  })

  it('refuses a review-bound move while the TikTok gate is shut', async () => {
    renderMenu({}, 'Consent first.')
    await open()
    const item = await screen.findByRole('menuitem', { name: /Submit for review/ })
    expect(item).toHaveAttribute('aria-disabled', 'true')
    expect(item).toHaveTextContent('Consent first.')
    expect(patches).toEqual([])
  })

  it('renders the page’s extra items between the moves and Delete', async () => {
    renderMenu({
      onDelete: vi.fn(),
      extraItems: <DropdownMenuItem>Retry failed destinations</DropdownMenuItem>,
    })
    await open()
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((i) => i.textContent)).toEqual(['Unschedule', 'Submit for review', 'Retry failed destinations', 'Delete this post'])
  })

  it('renders nothing at all when there is nothing to offer', async () => {
    transitions = []
    const { container } = render(
      <WorkItemMoreMenu projectId="p" issueId="i" currentStatus="PUBLISHED" userRole="REVIEWER" token="tok" onStatusChanged={vi.fn()} />
    )
    await waitFor(() => expect(container).toBeEmptyDOMElement())
  })

  it('surfaces a refused move as a toast', async () => {
    fetchMock.mockImplementationOnce(async (url: string, init?: RequestInit) => {
      if ((init?.method ?? 'GET') === 'GET') return jsonResponse(200, { workflow: 'MARKETING', currentStatus: 'SCHEDULED', transitions })
      return jsonResponse(422, { detail: 'nope' })
    })
    fetchMock.mockImplementationOnce(async () => jsonResponse(422, { detail: 'The move was refused' }))
    renderMenu()
    await open()
    await userEvent.click(await screen.findByRole('menuitem', { name: /Unschedule/ }))
    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
  })
})
