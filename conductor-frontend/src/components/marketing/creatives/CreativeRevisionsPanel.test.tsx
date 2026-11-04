import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', async (orig) => ({ ...(await orig<typeof import('@/lib/api')>()), apiGet: vi.fn() }))

import { apiGet } from '@/lib/api'
import { CreativeRevisionsPanel } from './CreativeRevisionsPanel'

const get = vi.mocked(apiGet)
const now = new Date().toISOString()

const REVISIONS = [
  {
    id: 'r3', revisionNo: 3, creativeVersion: 5, status: 'APPLIED', source: 'REVIEWER', authorName: 'Rita Reviewer', createdAt: now,
    textDiff: [{ slide: 2, path: 'bubbles[1].t', old: 'Hello there', new: 'Hello friend' }],
  },
  {
    id: 'r2', revisionNo: 2, creativeVersion: 4, status: 'FAILED', source: 'EDITOR', authorName: 'Ada Admin', createdAt: now,
    error: 'Slide checks failed',
    checks: [{ slide: 3, ratio: '4x5', rule: 'safeZone', severity: 'error', message: 'text intrudes' }],
    textDiff: [{ slide: 3, path: 'texts[0].t', old: 'a', new: 'b' }],
  },
  {
    id: 'r1', revisionNo: 1, creativeVersion: 3, status: 'APPLIED', source: 'EDITOR', authorName: 'Ada Admin', createdAt: now,
    textDiff: ['one', 'two', 'three', 'four', 'five'].map((w, i) => ({ slide: i + 1, path: 'texts[0].t', old: w, new: `${w}!` })),
  },
  { id: 'r0', revisionNo: 0, creativeVersion: 1, status: 'APPLIED', source: 'IMPORT', createdAt: now, textDiff: [] },
]

beforeEach(() => {
  vi.clearAllMocks()
})

describe('CreativeRevisionsPanel', () => {
  it('lists revisions in the order given with source, author and status', async () => {
    get.mockResolvedValue({ revisions: REVISIONS })
    render(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={5} />)
    const first = await screen.findByTestId('revision-3')
    expect(get).toHaveBeenCalledWith('/api/v2/projects/p/marketing/creatives/cr-1/revisions', 't')
    expect(first).toHaveTextContent('Reviewer fix')
    expect(first).toHaveTextContent('Rita Reviewer')
    expect(screen.getByTestId('revision-status-3')).toHaveTextContent('Applied')
    expect(screen.getByTestId('revision-status-2')).toHaveTextContent('Failed')
    expect(screen.getByTestId('revision-0')).toHaveTextContent('Spec stored')
    const order = screen.getAllByTestId(/^revision-\d+$/).map((e) => e.getAttribute('data-testid'))
    expect(order).toEqual(['revision-3', 'revision-2', 'revision-1', 'revision-0'])
  })

  it('shows diff rows as "Slide n · Field" with the changed words marked', async () => {
    get.mockResolvedValue({ revisions: REVISIONS })
    render(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={5} />)
    const first = await screen.findByTestId('revision-3')
    expect(within(first).getByText('Slide 2 · Bubble 2')).toBeInTheDocument()
    expect(within(first).getByTestId('text-change-old')).toHaveTextContent('Hello there')
    expect(within(first).getByTestId('text-change-new')).toHaveTextContent('Hello friend')
    expect(within(first).getByText('there').tagName).toBe('DEL')
    expect(within(first).getByText('friend').tagName).toBe('INS')
  })

  it('shows a failed revision’s error and checks by slide, ratio and rule', async () => {
    get.mockResolvedValue({ revisions: REVISIONS })
    render(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={5} />)
    const failed = await screen.findByTestId('revision-2')
    expect(failed).toHaveTextContent('Slide checks failed')
    expect(within(failed).getByTestId('slide-check-safeZone')).toHaveTextContent('Error · Safe zone · Slide 3, 4:5')
  })

  it('shows the first few changes of a long revision and the rest on request', async () => {
    get.mockResolvedValue({ revisions: REVISIONS })
    render(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={5} />)
    const long = await screen.findByTestId('revision-1')
    expect(within(long).getAllByTestId('text-change-row')).toHaveLength(3)
    await userEvent.click(within(long).getByRole('button', { name: 'Show all 5 changes' }))
    expect(within(long).getAllByTestId('text-change-row')).toHaveLength(5)
  })

  it('says so when there are none, and when the history cannot be read', async () => {
    get.mockResolvedValueOnce({ revisions: [] })
    const { unmount } = render(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={1} />)
    expect(await screen.findByText('No text edits yet.')).toBeInTheDocument()
    unmount()
    get.mockRejectedValueOnce(new Error('boom'))
    render(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={1} />)
    expect(await screen.findByText('Could not load the text history')).toBeInTheDocument()
  })

  it('reloads when the Creative’s version moves', async () => {
    get.mockResolvedValue({ revisions: [] })
    const { rerender } = render(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={1} />)
    await screen.findByText('No text edits yet.')
    rerender(<CreativeRevisionsPanel projectId="p" creativeId="cr-1" token="t" version={2} />)
    await vi.waitFor(() => expect(get).toHaveBeenCalledTimes(2))
  })
})
