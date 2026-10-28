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

vi.mock('@/components/ui/date-time-picker', () => ({
  DateTimePicker: ({ value, onChange, label }: { value: string; onChange: (v: string) => void; label: string }) => (
    <input aria-label={label} value={value} onChange={(e) => onChange(e.target.value)} />
  ),
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

const run = vi.fn(async (ids: string[], mutate: (id: string) => Promise<unknown>, ...rest: [failure: string]) => {
  void rest
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
    const base = new Date(Date.now() + 10 * 86400_000)
    base.setUTCMinutes(0, 0, 0)
    const shifted = new Date(base.getTime() + 2 * 86400_000)
    setup([
      post({ id: 'p1', scheduledFor: base.toISOString(), scheduleTimezone: 'UTC' }),
      post({ id: 'p2' }),
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule' }))
    fireEvent.change(screen.getByLabelText('Reschedule mode'), { target: { value: 'shift' } })
    fireEvent.change(screen.getByLabelText(/Days/), { target: { value: '2' } })
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule', hidden: false }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect(apiPatch.mock.calls[0]![1]).toEqual({ scheduledFor: shifted.toISOString(), scheduleTimezone: 'UTC' })
  })

  it('blocks a shift that would move a post into the past, with a clear message', () => {
    const soon = new Date(Date.now() + 86400_000).toISOString()
    setup([post({ id: 'p1', scheduledFor: soon, scheduleTimezone: 'UTC' })])
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule' }))
    fireEvent.change(screen.getByLabelText('Reschedule mode'), { target: { value: 'shift' } })
    fireEvent.change(screen.getByLabelText(/Days/), { target: { value: '-3' } })
    expect(screen.getByTestId('past-time')).toHaveTextContent('1 post would move into the past')
    expect(screen.getAllByRole('button', { name: 'Reschedule' }).at(-1)).toBeDisabled()
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
    expect(await screen.findByTestId('excluded-note')).toHaveTextContent('2 posts are live, approved or already handed to a platform')
    expect(screen.getByTestId('excluded-note')).toHaveTextContent('RE-2, RE-3')
    fireEvent.click(await screen.findByLabelText(/Rexcipe IG/))
    fireEvent.click(screen.getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(apiPut).toHaveBeenCalledTimes(1))
    expect(apiPut.mock.calls[0]![0]).toContain('/work-items/p1/publish-targets')
  })

  // The two actions lock differently: a schedule PATCH is allowed everywhere but live (a Scheduled post
  // re-times in place), while replacing destinations revokes a hand-off.
  const STATES: [string, Partial<IssueWithReviewers>, boolean, boolean][] = [
    // name, post, Reschedule enabled, Destinations enabled
    ['Draft', { status: 'DRAFT' }, true, true],
    ['In Review', { status: 'IN_REVIEW' }, true, true],
    ['Changes Requested', { status: 'CHANGES_REQUESTED' }, true, true],
    ['Failed', { status: 'FAILED' }, true, true],
    ['Approved', { status: 'APPROVED' }, true, false],
    ['Scheduled', { status: 'SCHEDULED' }, true, false],
    ['handed off', { status: 'DRAFT', publishTargets: [{ platform: 'tiktok', state: 'HANDED_OFF' }] }, true, false],
    ['Published', { status: 'PUBLISHED' }, false, false],
    ['live target', { status: 'DRAFT', publishTargets: [{ platform: 'tiktok', state: 'PUBLISHED' }] }, false, false],
  ]
  it.each(STATES)('%s post: Reschedule enabled=%s? Destinations enabled=%s?', (_name, over, reschedule, destinations) => {
    setup([post({ id: 'p1', ...over })])
    expect(screen.getByRole('button', { name: 'Reschedule' }).hasAttribute('disabled')).toBe(!reschedule)
    expect(screen.getByRole('button', { name: 'Destinations' }).hasAttribute('disabled')).toBe(!destinations)
  })

  it('Reschedule includes a Scheduled post and names only the live one as excluded', async () => {
    setup([
      post({ id: 'p1', status: 'SCHEDULED', scheduledFor: new Date(Date.now() + 5 * 86400_000).toISOString(), scheduleTimezone: 'UTC' }),
      post({ id: 'p2', displayId: 'RE-2', status: 'PUBLISHED' }),
    ])
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule' }))
    expect(screen.getByTestId('excluded-note')).toHaveTextContent('1 post is already live and will not be changed: RE-2.')
    fireEvent.change(screen.getByLabelText('Reschedule mode'), { target: { value: 'shift' } })
    fireEvent.change(screen.getByLabelText(/Days/), { target: { value: '1' } })
    fireEvent.click(screen.getAllByRole('button', { name: 'Reschedule' }).at(-1)!)
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect(apiPatch.mock.calls[0]![0]).toContain('/work-items/p1')
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

  it('refuses a "set" time in the past with a clear message and accepts a future one', async () => {
    setup([post({ id: 'p1' })])
    fireEvent.click(screen.getByRole('button', { name: 'Reschedule' }))
    const apply = () => screen.getAllByRole('button', { name: 'Reschedule' }).at(-1)!

    fireEvent.change(screen.getByLabelText('Date and time'), { target: { value: '2020-01-01T09:00' } })
    expect(screen.getByTestId('past-time')).toHaveTextContent('in the past')
    expect(apply()).toBeDisabled()

    const future = new Date(Date.now() + 3 * 86400_000)
    const pad = (n: number) => String(n).padStart(2, '0')
    const wall = `${future.getFullYear()}-${pad(future.getMonth() + 1)}-${pad(future.getDate())}T12:00`
    fireEvent.change(screen.getByLabelText('Date and time'), { target: { value: wall } })
    expect(screen.queryByTestId('past-time')).toBeNull()
    fireEvent.click(apply())
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect(new Date((apiPatch.mock.calls[0]![1] as { scheduledFor: string }).scheduledFor).getTime()).toBeGreaterThan(Date.now())
  })
})
