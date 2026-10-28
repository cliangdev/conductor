import { beforeEach, describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { IssueWithReviewers } from './listTypes'

const OPTIONS = [
  { platform: 'instagram', connectionId: 'c1', label: 'Rexcipe IG', lane: 'NATIVE' },
  { platform: 'tiktok', connectionId: 'c2', label: 'Rexcipe TikTok', lane: 'NATIVE' },
]
const apiGetImpl = vi.fn((url: string): Promise<unknown> =>
  Promise.resolve(url.endsWith('/publish-targets') && url.includes('/work-items/') ? [] : OPTIONS))
const apiPatch = vi.fn().mockResolvedValue({})
const apiPost = vi.fn().mockResolvedValue({})
const apiPut = vi.fn().mockResolvedValue({})
const apiDelete = vi.fn().mockResolvedValue({})
vi.mock('@/lib/api', () => ({
  apiGet: (url: string) =>
    apiGetImpl(url),
  apiPatch: (...a: unknown[]) => apiPatch(...a),
  apiPost: (...a: unknown[]) => apiPost(...a),
  apiPut: (...a: unknown[]) => apiPut(...a),
  apiDelete: (...a: unknown[]) => apiDelete(...a),
  apiErrorMessage: (_e: unknown, f: string) => f,
}))

import { PostBulkActions } from './PostBulkActions'

function post(over: Partial<IssueWithReviewers>): IssueWithReviewers {
  return {
    id: 'p1',
    title: 'Post one',
    type: 'POST',
    status: 'DRAFT',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    displayId: 'RE-1',
    ...over,
  }
}

const run = vi.fn(async (ids: string[], mutate: (id: string) => Promise<unknown>, _failure: string) => {
  await Promise.all(ids.map((id) => mutate(id)))
})

function setup(selected: IssueWithReviewers[]) {
  render(<PostBulkActions projectId="proj" token="tok" selected={selected} run={run} />)
}

beforeEach(() => vi.clearAllMocks())

describe('PostBulkActions', () => {
  it('disables Retry failed unless a selected post has a failed destination', () => {
    setup([post({})])
    expect(screen.getByRole('button', { name: /Retry failed/ })).toBeDisabled()
  })

  it('retries only the posts with failed destinations', async () => {
    setup([
      post({ id: 'p1', publishTargets: [{ platform: 'tiktok', state: 'FAILED' }] }),
      post({ id: 'p2', publishTargets: [{ platform: 'tiktok', state: 'PENDING' }] }),
    ])
    fireEvent.click(screen.getByRole('button', { name: /Retry failed \(1\)/ }))
    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    expect(apiPost.mock.calls[0]![0]).toContain('/work-items/p1/publish-targets/retry')
  })

  it('delete excludes live posts and says so, and deletes only the rest', async () => {
    setup([
      post({ id: 'p1' }),
      post({ id: 'p2', displayId: 'RE-2', publishTargets: [{ platform: 'instagram', state: 'PUBLISHED' }] }),
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(screen.getByTestId('delete-live-note')).toHaveTextContent('1 live post is not included')
    expect(screen.getByTestId('delete-live-note')).toHaveTextContent('RE-2')
    fireEvent.click(screen.getByRole('button', { name: 'Delete 1' }))
    await waitFor(() => expect(apiDelete).toHaveBeenCalledTimes(1))
    expect(apiDelete.mock.calls[0]![0]).toContain('/work-items/p1')
  })

  it('disables Delete when every selected post is live', () => {
    setup([post({ publishTargets: [{ platform: 'instagram', state: 'PUBLISHED' }] })])
    expect(screen.getByRole('button', { name: 'Delete' })).toBeDisabled()
  })

  it('adds tags only to posts that lack them', async () => {
    setup([post({ id: 'p1', tags: ['launch'] }), post({ id: 'p2', tags: [] })])
    fireEvent.click(screen.getByRole('button', { name: 'Tags' }))
    fireEvent.change(screen.getByLabelText(/Tags \(comma/), { target: { value: 'Launch, autumn' } })
    fireEvent.click(screen.getByRole('button', { name: 'Add tags' }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(2))
    const bodies = Object.fromEntries(apiPatch.mock.calls.map((c) => [c[0] as string, c[1]]))
    expect(Object.values(bodies)).toContainEqual({ tags: ['launch', 'autumn'] })
    expect(Object.values(bodies)).toContainEqual({ tags: ['launch', 'autumn'] })
  })

  it('shifts schedules by days and skips unscheduled posts', async () => {
    setup([
      post({ id: 'p1', scheduledFor: '2026-03-04T15:00:00.000Z', scheduleTimezone: 'UTC' }),
      post({ id: 'p2' }),
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule' }))
    fireEvent.change(screen.getByLabelText('Reschedule mode'), { target: { value: 'shift' } })
    fireEvent.change(screen.getByLabelText(/Days/), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule', hidden: false }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect(apiPatch.mock.calls[0]![1]).toEqual({ scheduledFor: '2026-03-06T15:00:00.000Z', scheduleTimezone: 'UTC' })
  })

  it('sets destinations on every selected post with a PUT', async () => {
    setup([post({ id: 'p1' }), post({ id: 'p2' })])
    fireEvent.click(screen.getByRole('button', { name: 'Destinations' }))
    fireEvent.click(await screen.findByLabelText(/Rexcipe IG/))
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(2))
    expect(apiPut.mock.calls[0]![1]).toEqual({ targets: [{ platform: 'instagram', connectionId: 'c1' }] })
  })

  it('leaves approved, scheduled and handed-off posts out of Reschedule and Destinations, and says so', async () => {
    setup([
      post({ id: 'p1' }),
      post({ id: 'p2', displayId: 'RE-2', status: 'SCHEDULED' }),
      post({ id: 'p3', displayId: 'RE-3', publishTargets: [{ platform: 'tiktok', state: 'HANDED_OFF' }] }),
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Destinations' }))
    expect(await screen.findByTestId('excluded-note')).toHaveTextContent('2 posts are live, approved or already handed')
    expect(screen.getByTestId('excluded-note')).toHaveTextContent('RE-2, RE-3')
    fireEvent.click(await screen.findByLabelText(/Rexcipe IG/))
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1))
    expect(apiPut.mock.calls[0]![0]).toContain('/work-items/p1/publish-targets')
  })

  it('disables Reschedule and Destinations when every selected post is locked', () => {
    setup([post({ id: 'p1', status: 'APPROVED' })])
    expect(screen.getByRole('button', { name: 'Reschedule' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Destinations' })).toBeDisabled()
  })

  it('keeps the settings of a destination that stays selected (e.g. TikTok options) and adds new ones plain', async () => {
    apiGetImpl.mockImplementation((url: string) =>
      Promise.resolve(
        url.includes('/work-items/p1/publish-targets')
          ? [{ id: 't', platform: 'tiktok', connectionId: 'c2', lane: 'NATIVE', state: 'PENDING', format: 'reel', publishOptions: { privacyLevel: 'PUBLIC' }, captionOverride: 'Own caption' }]
          : OPTIONS,
      ),
    )
    setup([post({ id: 'p1' })])
    fireEvent.click(screen.getByRole('button', { name: 'Destinations' }))
    fireEvent.click(await screen.findByLabelText(/Rexcipe TikTok/))
    fireEvent.click(screen.getByLabelText(/Rexcipe IG/))
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1))
    const targets = (apiPut.mock.calls[0]![1] as { targets: Record<string, unknown>[] }).targets
    expect(targets.find((t) => t.platform === 'tiktok')).toMatchObject({
      format: 'reel',
      publishOptions: { privacyLevel: 'PUBLIC' },
      captionOverride: 'Own caption',
    })
    expect(targets.find((t) => t.platform === 'instagram')).toEqual({ platform: 'instagram', connectionId: 'c1' })
  })

  it('does not count or PATCH posts whose tags would not change', async () => {
    setup([post({ id: 'p1', tags: ['launch'] }), post({ id: 'p2', tags: undefined }), post({ id: 'p3', tags: [] })])
    fireEvent.click(screen.getByRole('button', { name: 'Tags' }))
    fireEvent.change(screen.getByLabelText(/Tags \(comma/), { target: { value: 'launch' } })
    fireEvent.change(screen.getByLabelText('Tag action'), { target: { value: 'remove' } })
    expect(screen.getByText('1 of 3 posts will change.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Remove tags' }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect(apiPatch.mock.calls[0]![0]).toContain('/work-items/p1')
    expect(apiPatch.mock.calls[0]![1]).toEqual({ tags: [] })
  })

  it('passes a full-phrase failure message to the runner for each action', async () => {
    setup([post({ id: 'p1', publishTargets: [{ platform: 'tiktok', state: 'FAILED' }] })])
    fireEvent.click(screen.getByRole('button', { name: /Retry failed/ }))
    await waitFor(() => expect(run).toHaveBeenCalled())
    expect(run.mock.calls[0]![2]).toBe('to retry the failed destinations')
  })
})
