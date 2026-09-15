import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// The signed-URL upload is an XMLHttpRequest jsdom cannot complete; the ticket and confirm calls
// around it are what this file asserts on.
vi.mock('@/components/workitems/MediaUploadPanel', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/workitems/MediaUploadPanel')>()),
  putToSignedUrl: vi.fn(async () => {}),
}))
import type { WorkflowView } from '@/types/workItem'
import { ComposePostPage } from './ComposePostPage'

const { pushSpy, toastErrorSpy } = vi.hoisted(() => ({ pushSpy: vi.fn(), toastErrorSpy: vi.fn() }))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: pushSpy }) }))
vi.mock('@/components/ui/toast', async () => {
  const actual = await vi.importActual<typeof import('@/components/ui/toast')>('@/components/ui/toast')
  return { ...actual, toastError: toastErrorSpy }
})

const API = 'https://api.test'
const PROJECT = 'project-1'

const MARKETING: WorkflowView = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'MARKETING',
  defaultView: 'calendar',
  version: 1,
  types: ['POST'],
  statuses: [{ id: 'DRAFT', label: 'Draft', category: 'open' }],
  transitions: [],
  assetTypes: ['facebook_post', 'instagram_post'],
}

const ACCOUNTS = [
  {
    platform: 'instagram',
    connectorId: 'meta',
    connectionId: 'c-ig',
    label: '@acme',
    lane: 'APP_MANAGED',
    healthStatus: 'HEALTHY',
    formats: ['feed', 'reel', 'story'],
  },
  { platform: 'facebook', connectorId: 'meta', connectionId: 'c-fb', label: 'Acme Page', lane: 'NATIVE', healthStatus: 'UNHEALTHY' },
  { platform: 'instagram', connectorId: null, connectionId: null, label: 'Instagram (manual)', lane: 'MANUAL' },
]

let accounts: unknown[] = ACCOUNTS
let calls: Array<{ method: string; url: string; body: unknown }> = []
let createRejection: { status: number; detail: string } | null = null
let targetsRejection: { status: number; detail: string } | null = null

function json(status: number, body: unknown) {
  return { ok: status >= 200 && status < 300, status, headers: { get: () => 'application/json' }, json: async () => body }
}

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  const body = init?.body ? JSON.parse(String(init.body)) : undefined
  calls.push({ method, url, body })
  if (method === 'GET' && url.endsWith('/publish-targets')) return json(200, accounts)
  if (method === 'GET' && url.endsWith('/publish-preflight')) return json(200, { earliestFireTime: '2026-09-04T12:01:01Z' })
  if (method === 'POST' && url.endsWith('/work-items')) {
    if (createRejection) return json(createRejection.status, { detail: createRejection.detail })
    return json(201, { id: 'wi-9', displayId: 'MK-9' })
  }
  if (method === 'PUT' && url.endsWith('/publish-targets')) {
    if (targetsRejection) return json(targetsRejection.status, { detail: targetsRejection.detail })
    return json(200, [])
  }
  if (method === 'POST' && url.endsWith('/assets/uploads')) {
    return json(201, { assetId: 'asset-1', uploadUrl: 'http://storage.test/put/asset-1', gcsPath: 'x', expiresAt: 'y' })
  }
  if (method === 'PUT' && url.startsWith('http://storage.test/')) return { ok: true, status: 200, headers: { get: () => null }, json: async () => ({}) }
  if (method === 'POST' && url.endsWith('/assets/asset-1/confirm')) return { ok: true, status: 204, headers: { get: () => null }, json: async () => ({}) }
  if (method === 'PATCH') return json(200, {})
  throw new Error(`unexpected ${method} ${url}`)
})

beforeEach(() => {
  accounts = ACCOUNTS
  calls = []
  createRejection = null
  targetsRejection = null
  pushSpy.mockClear()
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
  vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: () => 'blob:preview', revokeObjectURL: () => {} }))
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function renderPage() {
  render(
    <ComposePostPage projectId={PROJECT} workflowSlug="MARKETING" workflowView={MARKETING} detailArea="marketing" noun="Post" token="t" />
  )
}

/** The account's checkbox in the Destinations panel. */
async function account(label: string) {
  return screen.findByRole('checkbox', { name: new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) })
}

async function pickUtc() {
  await userEvent.click(screen.getByLabelText('Schedule timezone'))
  await userEvent.type(screen.getByLabelText('Search time zones'), 'UTC')
  await userEvent.click(screen.getByRole('option', { name: /UTC/ }))
}

describe('ComposePostPage', () => {
  it('creates the Post, saves its destinations and schedules it on the next accepted slot, then opens it', async () => {
    renderPage()
    await account('@acme')

    await userEvent.type(screen.getByLabelText('Caption'), 'Launch day!\nMore below.')
    await userEvent.click(await account('@acme'))
    // An unhealthy account is offered disabled rather than hidden.
    expect(await account('Acme Page')).toBeDisabled()
    await pickUtc()

    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))

    await waitFor(() => expect(pushSpy).toHaveBeenCalledWith('/app/projects/project-1/marketing/posts/MK-9'))
    const create = calls.find((c) => c.method === 'POST' && c.url.endsWith('/work-items'))
    expect(create?.body).toEqual({ type: 'POST', title: 'Launch day!', description: 'Launch day!\nMore below.', workflow: 'MARKETING' })
    const targets = calls.find((c) => c.method === 'PUT')
    expect(targets?.body).toEqual({ targets: [{ platform: 'instagram', connectionId: 'c-ig', format: 'feed' }] })
    const schedule = calls.find((c) => c.method === 'PATCH')
    expect(schedule?.body).toEqual({ scheduledFor: '2026-09-04T12:05:00.000Z', scheduleTimezone: 'UTC' })
    expect(toastErrorSpy).not.toHaveBeenCalled()
  })

  it('sends the publish-on-approval flag instead of a date when asked, and skips the preflight', async () => {
    renderPage()
    await userEvent.type(screen.getByLabelText('Caption'), 'Whenever it is ready')
    await userEvent.click(await account('@acme'))
    await pickUtc()
    await userEvent.click(screen.getByLabelText('As soon as approved'))
    expect(screen.queryByLabelText('Scheduled date and time')).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))

    await waitFor(() => expect(pushSpy).toHaveBeenCalledWith('/app/projects/project-1/marketing/posts/MK-9'))
    const schedule = calls.find((c) => c.method === 'PATCH')
    expect(schedule?.body).toEqual({ publishOnApproval: true, scheduleTimezone: 'UTC' })
    expect(calls.some((c) => c.url.endsWith('/publish-preflight'))).toBe(false)
  })

  it('lets a destination be customized before the Post exists, and sends the customization', async () => {
    renderPage()
    await userEvent.click(await account('@acme'))
    const row = screen.getByTestId('destination-row-instagram-c-ig')
    await userEvent.click(within(row).getByRole('button', { name: 'Show details' }))
    await userEvent.click(screen.getByRole('radio', { name: 'Reel' }))
    await userEvent.type(screen.getByLabelText('Caption for this destination'), 'Reel-only words')
    await userEvent.type(screen.getByLabelText('Caption'), 'Reel time')
    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))

    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    const targets = calls.find((c) => c.method === 'PUT')
    expect(targets?.body).toEqual({
      targets: [{ platform: 'instagram', connectionId: 'c-ig', format: 'reel', captionOverride: 'Reel-only words' }],
    })
  })

  it('shows a chosen file as a thumbnail and uploads it before the destinations are saved', async () => {
    renderPage()
    await userEvent.type(screen.getByLabelText('Caption'), 'With a picture')
    await userEvent.click(await account('@acme'))

    const file = new File([new Uint8Array([1, 2, 3])], 'story.jpg', { type: 'image/jpeg' })
    await userEvent.upload(document.getElementById('compose-media') as HTMLInputElement, file)
    expect(await screen.findByAltText('story.jpg')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add another' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))
    await waitFor(() => expect(pushSpy).toHaveBeenCalled())

    const methods = calls.map((c) => `${c.method} ${c.url.replace(/^.*\/work-items/, '/work-items')}`)
    const uploadIndex = methods.findIndex((m) => m.endsWith('/assets/uploads'))
    const confirmIndex = methods.findIndex((m) => m.endsWith('/assets/asset-1/confirm'))
    const targetsIndex = methods.findIndex((m) => m.startsWith('PUT') && m.endsWith('/publish-targets'))
    expect(uploadIndex).toBeGreaterThan(-1)
    expect(confirmIndex).toBeGreaterThan(uploadIndex)
    expect(targetsIndex).toBeGreaterThan(confirmIndex)
    const ticket = calls[uploadIndex].body as { filename: string; contentType: string }
    expect(ticket.filename).toBe('story.jpg')
    expect(ticket.contentType).toBe('image/jpeg')
  })

  it('explains the by-hand rows when no account is connected, and never offers one beside an account', async () => {
    accounts = ACCOUNTS.filter((a) => a.lane === 'MANUAL')
    renderPage()
    await screen.findByRole('checkbox', { name: /Instagram \(manual\)/ })
    expect(screen.getByText(/No accounts are connected yet/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Integrations' })).toHaveAttribute('href', '/app/projects/project-1/integrations')
  })

  it('hides the by-hand row for a platform with a connected account, healthy or not', async () => {
    renderPage()
    await account('@acme')
    expect(screen.queryByRole('checkbox', { name: /Instagram \(manual\)/ })).not.toBeInTheDocument()
    expect(screen.queryByText(/No accounts are connected yet/)).not.toBeInTheDocument()
  })

  it('says what is still missing while the button is disabled', async () => {
    renderPage()
    await account('@acme')
    expect(screen.getByText('Needs a caption and at least one destination.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Caption'), 'Hello')
    expect(screen.getByText('Needs at least one destination.')).toBeInTheDocument()
    await userEvent.click(await account('@acme'))
    expect(screen.queryByText(/^Needs /)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Create post' })).toBeEnabled()
  })

  it('reports a refused create and stays put', async () => {
    createRejection = { status: 422, detail: "Type 'POST' is not allowed" }
    renderPage()
    await userEvent.type(screen.getByLabelText('Caption'), 'hi')
    await userEvent.click(await account('@acme'))
    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))
    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(String(toastErrorSpy.mock.calls[0]![0])).toContain("Type 'POST' is not allowed")
    expect(pushSpy).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Create post' })).toBeEnabled()
  })

  it('opens the Post even when the destinations could not be saved, and says so', async () => {
    targetsRejection = { status: 422, detail: 'Not a publishable target for this project' }
    renderPage()
    await userEvent.type(screen.getByLabelText('Caption'), 'hi')
    await userEvent.click(await account('@acme'))
    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))
    await waitFor(() => expect(pushSpy).toHaveBeenCalled())
    expect(String(toastErrorSpy.mock.calls[0]![0])).toContain('Not a publishable target')
  })
})
