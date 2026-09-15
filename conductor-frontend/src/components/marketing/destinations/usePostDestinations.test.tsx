import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import { MARKETING_VIEW, jsonResponse, option, selection, tiktokOption } from '@/components/marketing/test-fixtures'
import type { PublishPreflight } from '@/components/marketing/publishReadiness'
import { usePostDestinations, type UsePostDestinationsArgs } from './usePostDestinations'
import type { PublishTargetOption, SelectedPublishTarget } from './types'

const API = 'https://api.test'
const PROJECT = 'project-1'
const WORK_ITEM = 'post-1'

const facebook = option({ platform: 'facebook', connectionId: 'fb', label: 'Rexipe' })
const tiktok = tiktokOption('rexipeio')

let availableTargets: PublishTargetOption[] = []
let selectedTargets: SelectedPublishTarget[] = []
let putBodies: Array<{ targets: Array<Record<string, unknown>> }> = []
let selectionReads = 0
let metricsReads = 0
let consentServed: Record<string, unknown> | null = null
let consentFails = false
let consentPutBodies: Array<{ consented: boolean }> = []

const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
  const method = init?.method ?? 'GET'
  if (method === 'GET' && url.endsWith(`/projects/${PROJECT}/publish-targets`)) return jsonResponse(200, availableTargets)
  if (method === 'GET' && url.endsWith(`/work-items/${WORK_ITEM}/publish-targets`)) {
    selectionReads += 1
    return jsonResponse(200, selectedTargets)
  }
  if (method === 'PUT' && url.endsWith(`/work-items/${WORK_ITEM}/publish-targets`)) {
    const body = JSON.parse(String(init?.body)) as { targets: Array<Record<string, unknown>> }
    putBodies.push(body)
    selectedTargets = body.targets.map((t) => {
      const o = availableTargets.find((a) => a.platform === t.platform && a.connectionId === t.connectionId)!
      return { ...selection(o, WORK_ITEM), publishOptions: t.publishOptions as never }
    })
    return jsonResponse(200, selectedTargets)
  }
  if (method === 'GET' && url.endsWith('/publish-metrics')) {
    metricsReads += 1
    return jsonResponse(200, { workItemId: WORK_ITEM, targets: [], totals: null })
  }
  if (method === 'GET' && url.endsWith('/publish-consent')) {
    if (consentFails) return jsonResponse(500, { detail: 'boom' })
    return jsonResponse(200, consentServed ?? { workItemId: WORK_ITEM, required: true, valid: false, verdict: 'NEVER_GIVEN' })
  }
  if (method === 'PUT' && url.endsWith('/publish-consent')) {
    const body = JSON.parse(String(init?.body)) as { consented: boolean }
    consentPutBodies.push(body)
    consentServed = { workItemId: WORK_ITEM, required: true, valid: body.consented, verdict: body.consented ? 'VALID' : 'NEVER_GIVEN', consentedByName: 'Bryan' }
    return jsonResponse(200, consentServed)
  }
  if (method === 'POST' && url.endsWith('/publish-targets/retry')) {
    selectedTargets = selectedTargets.map((t) => (t.state === 'FAILED' ? { ...t, state: 'PENDING' } : t))
    return jsonResponse(200, { workItemId: WORK_ITEM, status: 'SCHEDULED', retriedCount: 1, targets: selectedTargets })
  }
  throw new Error(`unexpected fetch: ${method} ${url}`)
})

beforeEach(() => {
  availableTargets = []
  selectedTargets = []
  putBodies = []
  selectionReads = 0
  metricsReads = 0
  consentServed = null
  consentFails = false
  consentPutBodies = []
  fetchMock.mockClear()
  vi.stubEnv('NEXT_PUBLIC_API_URL', API)
  vi.stubGlobal('fetch', fetchMock)
})
afterEach(() => {
  vi.unstubAllEnvs()
  vi.unstubAllGlobals()
})

function args(overrides: Partial<UsePostDestinationsArgs> = {}): UsePostDestinationsArgs {
  return {
    projectId: PROJECT,
    workItemId: WORK_ITEM,
    token: 'tok',
    enabled: true,
    status: 'DRAFT',
    workflowView: MARKETING_VIEW,
    assets: [],
    caption: 'Hello',
    preflight: null,
    ...overrides,
  }
}

describe('usePostDestinations', () => {
  it('does nothing at all when disabled', () => {
    const { result } = renderHook(() => usePostDestinations(args({ enabled: false })))
    expect(fetchMock).not.toHaveBeenCalled()
    expect(result.current.loading).toBe(false)
    expect(result.current.rows).toEqual([])
  })

  it('loads the options and the selection into rows, then saves a toggle as the whole selection', async () => {
    availableTargets = [facebook, tiktok]
    selectedTargets = [selection(facebook, WORK_ITEM)]
    const onChanged = vi.fn()
    const { result } = renderHook(() => usePostDestinations(args({ onChanged })))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.rows.map((r) => [r.option.platform, r.checked])).toEqual([
      ['facebook', true],
      ['tiktok', false],
    ])
    expect(result.current.summary.text).toBe('1 selected')

    const tt = result.current.rows.find((r) => r.option.platform === 'tiktok')!
    await act(async () => result.current.actions.toggle(tt))
    await waitFor(() => expect(putBodies).toHaveLength(1))
    // Every picked target goes out; the TikTok one carries the account's default audience and nothing
    // says "inherit" — an inheriting target's caption and media are simply absent.
    expect(putBodies[0].targets).toEqual([
      { platform: 'facebook', connectionId: 'fb', format: 'feed' },
      {
        platform: 'tiktok',
        connectionId: 'rexipeio',
        format: 'feed',
        publishOptions: expect.objectContaining({ privacyLevel: 'PUBLIC_TO_EVERYONE' }),
      },
    ])
    await waitFor(() => expect(onChanged).toHaveBeenCalled())
    expect(result.current.rows.find((r) => r.option.platform === 'tiktok')?.checked).toBe(true)
  })

  it('re-reads the selection when the page bumps the refresh key, and never the options', async () => {
    availableTargets = [facebook]
    selectedTargets = [selection(facebook, WORK_ITEM)]
    const { result, rerender } = renderHook((a: UsePostDestinationsArgs) => usePostDestinations(a), {
      initialProps: args({ refreshKey: 1 }),
    })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(selectionReads).toBe(1)
    selectedTargets = [{ ...selectedTargets[0], state: 'REVOKED' }]
    rerender(args({ refreshKey: 2, status: 'APPROVED' }))
    await waitFor(() => expect(selectionReads).toBe(2))
    await waitFor(() => expect(result.current.rows[0]?.stateLabel).toBe('Taken back'))
    expect(fetchMock.mock.calls.filter(([u]) => String(u).endsWith(`/projects/${PROJECT}/publish-targets`))).toHaveLength(1)
  })

  it('asks for metrics only once something has published', async () => {
    availableTargets = [facebook]
    selectedTargets = [selection(facebook, WORK_ITEM)]
    const { result, rerender } = renderHook((a: UsePostDestinationsArgs) => usePostDestinations(a), {
      initialProps: args(),
    })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(metricsReads).toBe(0)
    selectedTargets = [{ ...selectedTargets[0], state: 'PUBLISHED' }]
    rerender(args({ status: 'PUBLISHED', refreshKey: 'again' }))
    await waitFor(() => expect(metricsReads).toBeGreaterThan(0))
  })

  it('reads the TikTok consent for a picked TikTok account, and recording it lifts the gate', async () => {
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok, WORK_ITEM)]
    const onConsentChanged = vi.fn()
    const { result } = renderHook(() => usePostDestinations(args({ onConsentChanged })))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await waitFor(() => expect(result.current.tiktokBlockedReason).toMatch(/consent/i))
    expect(result.current.rows[0].consent).toMatchObject({ given: false, problem: null })
    expect(result.current.rows[0].action).toBe('review-consent')

    await act(async () => {
      await result.current.actions.setConsent(true)
    })
    expect(consentPutBodies).toEqual([{ consented: true }])
    expect(onConsentChanged).toHaveBeenCalledWith(true)
    expect(result.current.tiktokBlockedReason).toBeNull()
    expect(result.current.rows[0].consent).toMatchObject({ given: true, consentedByName: 'Bryan' })
    expect(result.current.rows[0].action).toBeNull()
  })

  it('fails closed when the consent cannot be read', async () => {
    availableTargets = [tiktok]
    selectedTargets = [selection(tiktok, WORK_ITEM)]
    consentFails = true
    const { result } = renderHook(() => usePostDestinations(args()))
    // The server's own words reach the panel; the gate stays shut.
    await waitFor(() => expect(result.current.consentError).toBe('boom'))
    expect(result.current.tiktokBlockedReason).not.toBeNull()
  })

  it('never asks about consent for a Post with no TikTok account', async () => {
    availableTargets = [facebook, tiktok]
    selectedTargets = [selection(facebook, WORK_ITEM)]
    const { result } = renderHook(() => usePostDestinations(args()))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(fetchMock.mock.calls.some(([u]) => String(u).endsWith('/publish-consent'))).toBe(false)
    expect(result.current.tiktokBlockedReason).toBeNull()
  })

  it('reads outcome mode past review, and Edit destinations flips it back to picking until the status moves', async () => {
    availableTargets = [facebook]
    selectedTargets = [{ ...selection(facebook, WORK_ITEM), state: 'PENDING' }]
    const { result, rerender } = renderHook((a: UsePostDestinationsArgs) => usePostDestinations(a), {
      initialProps: args({ status: 'APPROVED' }),
    })
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.mode).toBe('outcome')
    expect(result.current.revertsOnEdit).toBe(true)
    act(() => result.current.actions.editDestinations())
    expect(result.current.mode).toBe('pick')
    expect(result.current.editing).toBe(true)
    rerender(args({ status: 'DRAFT' }))
    expect(result.current.editing).toBe(false)
  })

  it('splits the preflight’s findings between the rows and the Post', async () => {
    availableTargets = [facebook]
    selectedTargets = [selection(facebook, WORK_ITEM, 'fb-target')]
    const preflight = {
      publishing: true,
      ready: false,
      blockers: [
        { code: 'FIRE_TIME_TOO_SOON', message: 'Facebook needs 15 minutes', targetId: 'fb-target' },
        { code: 'NO_MEDIA', message: 'Add a file', targetId: null },
      ],
      warnings: [],
      consent: { required: false, verdict: 'NOT_REQUIRED' },
      review: { gated: false, assignedReviewers: 0, satisfied: false },
    } as PublishPreflight
    const { result } = renderHook(() => usePostDestinations(args({ preflight })))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.rows[0].blockers.map((b) => b.code)).toEqual(['FIRE_TIME_TOO_SOON'])
    expect(result.current.postLevel.blockers.map((b) => b.code)).toEqual(['NO_MEDIA'])
  })

  it('retries through the bulk endpoint and takes the response as the new truth', async () => {
    availableTargets = [facebook]
    selectedTargets = [{ ...selection(facebook, WORK_ITEM), state: 'FAILED' }]
    const onChanged = vi.fn()
    const { result } = renderHook(() => usePostDestinations(args({ status: 'APPROVED', onChanged })))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.failedCount).toBe(1)
    await act(async () => {
      await result.current.actions.retry()
    })
    expect(result.current.rows[0].stateLabel).toBe('Waiting')
    expect(onChanged).toHaveBeenCalled()
  })
})
