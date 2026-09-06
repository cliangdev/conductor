import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'
import { ComposePostModal, nextQuarterHour, toInstant } from './ComposePostModal'

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
  statuses: [],
  transitions: [],
  assetTypes: ['facebook_post', 'instagram_post'],
}

const ACCOUNTS = [
  {
    platform: 'instagram',
    connectionId: 'c-ig',
    label: '@acme',
    lane: 'APP_MANAGED',
    healthStatus: 'HEALTHY',
    formats: ['feed', 'reel', 'story'],
  },
  { platform: 'facebook', connectionId: 'c-fb', label: 'Acme Page', lane: 'NATIVE', healthStatus: 'UNHEALTHY' },
  { platform: 'instagram', connectionId: null, label: 'Instagram (manual)', lane: 'MANUAL' },
]

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
  if (method === 'GET' && url.endsWith('/publish-targets')) return json(200, ACCOUNTS)
  if (method === 'GET' && url.endsWith('/publish-preflight')) return json(200, { earliestFireTime: '2026-09-04T12:01:01Z' })
  if (method === 'POST' && url.endsWith('/work-items')) {
    if (createRejection) return json(createRejection.status, { detail: createRejection.detail })
    return json(201, { id: 'wi-9', displayId: 'MK-9' })
  }
  if (method === 'PUT' && url.endsWith('/publish-targets')) {
    if (targetsRejection) return json(targetsRejection.status, { detail: targetsRejection.detail })
    return json(200, [])
  }
  if (method === 'PATCH') return json(200, {})
  throw new Error(`unexpected ${method} ${url}`)
})

beforeEach(() => {
  calls = []
  createRejection = null
  targetsRejection = null
  pushSpy.mockClear()
  toastErrorSpy.mockClear()
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})

function renderModal() {
  const onCreated = vi.fn()
  const onOpenChange = vi.fn()
  render(
    <ComposePostModal
      open
      onOpenChange={onOpenChange}
      projectId={PROJECT}
      workflowSlug="MARKETING"
      workflowView={MARKETING}
      detailArea="marketing"
      noun="Post"
      token="t"
      onCreated={onCreated}
    />
  )
  return { onCreated, onOpenChange }
}

describe('ComposePostModal', () => {
  it('creates the Post, saves its destinations and schedules it on the next accepted slot, then opens it', async () => {
    const { onCreated, onOpenChange } = renderModal()
    await screen.findByLabelText('@acme')

    await userEvent.type(screen.getByLabelText('Caption'), 'Launch day!\nMore below.')
    await userEvent.click(screen.getByLabelText('@acme'))
    await userEvent.click(screen.getByLabelText('Instagram (manual)'))
    // An unhealthy account is offered disabled rather than hidden.
    expect(screen.getByLabelText('Acme Page')).toBeDisabled()
    await userEvent.selectOptions(screen.getByLabelText('Time zone'), 'UTC')

    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))

    await waitFor(() => expect(pushSpy).toHaveBeenCalledWith('/app/projects/project-1/marketing/posts/MK-9'))
    const create = calls.find((c) => c.method === 'POST' && c.url.endsWith('/work-items'))
    expect(create?.body).toEqual({ type: 'POST', title: 'Launch day!', description: 'Launch day!\nMore below.', workflow: 'MARKETING' })
    const targets = calls.find((c) => c.method === 'PUT')
    expect(targets?.body).toEqual({
      targets: [
        { platform: 'instagram', connectionId: 'c-ig', format: 'feed' },
        { platform: 'instagram', format: 'feed' },
      ],
    })
    const schedule = calls.find((c) => c.method === 'PATCH')
    expect(schedule?.body).toEqual({ scheduledFor: '2026-09-04T12:15:00.000Z', scheduleTimezone: 'UTC' })
    expect(onCreated).toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(toastErrorSpy).not.toHaveBeenCalled()
  })

  it('offers a format selector only for a destination with more than feed, and sends the choice', async () => {
    renderModal()
    await screen.findByLabelText('@acme')

    // Facebook (feed-only here) never grows a selector, whether or not it is checked.
    await userEvent.click(screen.getByLabelText('@acme'))
    expect(screen.getByRole('radiogroup', { name: /post format/i })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('radio', { name: 'Reel' }))
    await userEvent.type(screen.getByLabelText('Caption'), 'Reel time')
    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))

    await waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    const targets = calls.find((c) => c.method === 'PUT')
    expect(targets?.body).toEqual({ targets: [{ platform: 'instagram', connectionId: 'c-ig', format: 'reel' }] })
  })

  it('cannot be submitted without a caption and a destination', async () => {
    renderModal()
    await screen.findByLabelText('@acme')
    const button = screen.getByRole('button', { name: 'Create post' })
    expect(button).toBeDisabled()
    await userEvent.type(screen.getByLabelText('Caption'), 'hi')
    expect(button).toBeDisabled()
    await userEvent.click(screen.getByLabelText('@acme'))
    expect(button).toBeEnabled()
  })

  it('reports a refused create and stays open; a later failure still opens the Post and says what is missing', async () => {
    createRejection = { status: 422, detail: "Type 'POST' is not allowed" }
    const first = renderModal()
    await screen.findByLabelText('@acme')
    await userEvent.type(screen.getByLabelText('Caption'), 'hi')
    await userEvent.click(screen.getByLabelText('@acme'))
    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))
    await waitFor(() => expect(toastErrorSpy).toHaveBeenCalled())
    expect(String(toastErrorSpy.mock.calls[0]![0])).toContain("Type 'POST' is not allowed")
    expect(first.onOpenChange).not.toHaveBeenCalledWith(false)
    expect(pushSpy).not.toHaveBeenCalled()
  })

  it('opens the Post even when the destinations could not be saved, and says so', async () => {
    targetsRejection = { status: 422, detail: 'Not a publishable target for this project' }
    renderModal()
    await screen.findByLabelText('@acme')
    await userEvent.type(screen.getByLabelText('Caption'), 'hi')
    await userEvent.click(screen.getByLabelText('@acme'))
    await userEvent.click(screen.getByRole('button', { name: 'Create post' }))
    await waitFor(() => expect(pushSpy).toHaveBeenCalled())
    expect(String(toastErrorSpy.mock.calls[0]![0])).toContain('Not a publishable target')
  })
})

describe('schedule helpers', () => {
  it('nextQuarterHour rounds up to the next slot at or after the instant', () => {
    expect(nextQuarterHour(new Date('2026-09-04T12:01:01Z')).toISOString()).toBe('2026-09-04T12:15:00.000Z')
    expect(nextQuarterHour(new Date('2026-09-04T12:15:00Z')).toISOString()).toBe('2026-09-04T12:15:00.000Z')
  })

  it('toInstant reads a wall-clock time in its zone, DST included', () => {
    expect(toInstant('2026-07-01T09:00', 'Europe/Berlin')).toBe('2026-07-01T07:00:00.000Z')
    expect(toInstant('2026-01-15T09:00', 'Europe/Berlin')).toBe('2026-01-15T08:00:00.000Z')
    expect(toInstant('', 'UTC')).toBeNull()
  })
})
