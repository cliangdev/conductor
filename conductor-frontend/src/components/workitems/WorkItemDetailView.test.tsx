import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, fireEvent } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { WorkflowView } from '@/types/workItem'

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ accessToken: 'token', user: { id: 'user-1' } }),
}))

const VIEW: WorkflowView = {
  slug: 'ENGINEERING',
  noun: 'PRD',
  area: 'ENGINEERING',
  defaultView: 'list',
  version: 1,
  types: ['PRD'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'in_progress' },
    { id: 'DONE', label: 'Done', category: 'terminal' },
  ],
  transitions: [
    {
      from: 'IN_REVIEW',
      to: 'DONE',
      label: 'Approve & Close',
      requiresReview: true,
      reviewOutcomes: ['approve', 'request_changes', 'comment'],
    },
  ],
}

vi.mock('@/lib/workflows', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/workflows')>()
  return { ...actual, useWorkflowView: () => VIEW }
})

vi.mock('@/components/markdown/MarkdownRenderer', () => ({
  MarkdownRenderer: ({ content }: { content: string }) => <div data-testid="markdown">{content}</div>,
}))

const { registerSpy, unregisterSpy } = vi.hoisted(() => ({
  registerSpy: vi.fn(),
  unregisterSpy: vi.fn(),
}))
vi.mock('@/components/layout/CommandPalette', () => ({
  registerPaletteActions: (group: unknown) => {
    registerSpy(group)
    return unregisterSpy
  },
}))

vi.mock('@/components/ui/modal', () => ({
  Modal: ({
    open,
    children,
    title,
    footer,
  }: {
    open: boolean
    children: React.ReactNode
    title: string
    footer?: React.ReactNode
  }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
        {footer}
      </div>
    ) : null,
}))

const { toastErrorSpy, toastSuccessSpy } = vi.hoisted(() => ({
  toastErrorSpy: vi.fn(),
  toastSuccessSpy: vi.fn(),
}))
vi.mock('@/components/ui/toast', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/components/ui/toast')>()
  return { ...actual, toastError: toastErrorSpy, toastSuccess: toastSuccessSpy }
})

interface MockDoc {
  id: string
  filename: string
  contentType: string
  content?: string
}

interface MockComment {
  id: string
  documentId: string
  authorId: string
  authorName: string
  content: string
  lineNumber?: number
  createdAt: string
  resolvedAt?: string | null
  replies: { id: string; authorId: string; authorName: string; content: string; createdAt: string }[]
}

interface MockReviewer {
  userId: string
  name: string
  email: string
  reviewVerdict?: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
}

interface MockReview {
  reviewerId: string
  name: string
  verdict: 'APPROVED' | 'CHANGES_REQUESTED' | 'COMMENTED'
  body?: string
  submittedAt: string
}

let ISSUE: Record<string, unknown>
let DOCS: MockDoc[]
let COMMENTS: MockComment[]
let REVIEWERS: MockReviewer[]
let REVIEWS: MockReview[]
const MEMBERS = [
  { userId: 'user-1', role: 'ADMIN', name: 'Ada Admin', email: 'ada@x.com' },
  { userId: 'user-2', role: 'CREATOR', name: 'Cara Creator', email: 'cara@x.com' },
  { userId: 'user-3', role: 'REVIEWER', name: 'Rita Reviewer', email: 'rita@x.com' },
]
const TRANSITIONS = [{ toStatus: 'DONE', label: 'Approve & Close', requiresReview: true }]

const apiPost = vi.fn(async (url: string, body: Record<string, unknown>, _token?: string) => {
  if (url.includes('/reviewers')) {
    REVIEWERS = [...REVIEWERS, { userId: body.userId as string, name: 'Rita Reviewer', email: 'rita@x.com' }]
  } else if (url.includes('/reviews')) {
    REVIEWS = [
      ...REVIEWS,
      {
        reviewerId: 'user-1',
        name: 'Ada Admin',
        verdict: body.verdict as MockReview['verdict'],
        body: body.body as string | undefined,
        submittedAt: new Date().toISOString(),
      },
    ]
  } else if (url.includes('/comments')) {
    COMMENTS = [
      ...COMMENTS,
      {
        id: `posted-${COMMENTS.length}`,
        documentId: body.documentId as string,
        authorId: 'user-1',
        authorName: 'Ada Admin',
        content: body.content as string,
        lineNumber: body.lineNumber as number,
        createdAt: new Date().toISOString(),
        replies: [],
      },
    ]
  }
  return undefined
})
const apiPatch = vi.fn().mockResolvedValue(undefined)
const apiDelete = vi.fn(async (url: string, _token: string) => {
  const userId = url.match(/reviewers\/([^/]+)$/)?.[1]
  if (userId) REVIEWERS = REVIEWERS.filter((r) => r.userId !== userId)
})

vi.mock('@/lib/api', () => ({
  apiGet: (url: string) => {
    if (/\/work-items\/[^/]+$/.test(url) && !url.includes('?')) return Promise.resolve(ISSUE)
    if (url.includes('/documents')) return Promise.resolve(DOCS)
    if (url.includes('/comments')) return Promise.resolve(COMMENTS)
    if (url.includes('/reviewers')) return Promise.resolve(REVIEWERS)
    if (url.includes('/reviews')) return Promise.resolve(REVIEWS)
    if (url.includes('/assets')) return Promise.resolve([])
    if (url.includes('/available-transitions')) return Promise.resolve({ workflow: 'ENGINEERING', transitions: TRANSITIONS })
    if (url.includes('/members')) return Promise.resolve(MEMBERS)
    return Promise.resolve([])
  },
  apiPost: (...args: Parameters<typeof apiPost>) => apiPost(...args),
  apiPatch: (...args: unknown[]) => apiPatch(...args),
  apiDelete: (...args: Parameters<typeof apiDelete>) => apiDelete(...args),
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))

import { WorkItemDetailView } from './WorkItemDetailView'

function resetFixtures() {
  ISSUE = {
    id: 'wi-1',
    title: 'My PRD',
    type: 'PRD',
    status: 'IN_REVIEW',
    displayId: 'COND-42',
    createdBy: 'user-2',
    createdAt: '2026-07-01T00:00:00Z',
    updatedAt: '2026-07-10T00:00:00Z',
    assignee: { userId: 'user-1', name: 'Ada Admin' },
    workflow: 'ENGINEERING',
  }
  DOCS = [{ id: 'doc-1', filename: 'prd.md', contentType: 'text/markdown', content: 'line one\nline two\nline three' }]
  COMMENTS = []
  REVIEWERS = [{ userId: 'user-1', name: 'Ada Admin', email: 'ada@x.com' }]
  REVIEWS = []
  localStorage.clear()
}

async function renderView() {
  const utils = render(<WorkItemDetailView projectId="proj-1" workItemId="wi-1" slug="ENGINEERING" />)
  await screen.findByText('My PRD')
  return utils
}

describe('WorkItemDetailView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    resetFixtures()
  })

  it('renders document tabs and switches between them', async () => {
    DOCS = [
      { id: 'doc-1', filename: 'prd.md', contentType: 'text/markdown', content: 'line one\nline two\nline three' },
      { id: 'doc-2', filename: 'design.md', contentType: 'text/markdown', content: 'design content here' },
    ]
    await renderView()

    expect(await screen.findByText('line one', { exact: false })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /prd\.md/i })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: /design\.md/i })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('tab', { name: /design\.md/i }))
    expect(await screen.findByText('design content here')).toBeInTheDocument()
  })

  it('shows unresolved comment counts on the document tabs so you can see which doc was commented on', async () => {
    DOCS = [
      { id: 'doc-1', filename: 'prd.md', contentType: 'text/markdown', content: 'line one\nline two' },
      { id: 'doc-2', filename: 'design.md', contentType: 'text/markdown', content: 'design content' },
    ]
    COMMENTS = [
      {
        id: 'c1',
        documentId: 'doc-2',
        authorId: 'user-2',
        authorName: 'Rita',
        content: 'needs work',
        lineNumber: 1,
        createdAt: '2026-07-11T00:00:00Z',
        replies: [],
      },
      {
        id: 'c2',
        documentId: 'doc-2',
        authorId: 'user-2',
        authorName: 'Rita',
        content: 'and this',
        lineNumber: 2,
        createdAt: '2026-07-11T01:00:00Z',
        replies: [],
      },
      {
        id: 'c3',
        documentId: 'doc-2',
        authorId: 'user-2',
        authorName: 'Rita',
        content: 'already handled',
        lineNumber: 2,
        createdAt: '2026-07-11T02:00:00Z',
        resolvedAt: '2026-07-12T00:00:00Z',
        replies: [],
      },
    ]
    await renderView()

    // Resolved comments don't count — only the two open ones.
    expect(await screen.findByRole('tab', { name: /design\.md, 2 unresolved comments/i })).toBeInTheDocument()
    // The uncommented tab stays a bare filename.
    expect(screen.getByRole('tab', { name: /^prd\.md$/i })).toBeInTheDocument()
  })

  it('counts item-level comments on the Activity tab', async () => {
    COMMENTS = [
      {
        id: 'c1',
        documentId: 'doc-1',
        authorId: 'user-2',
        authorName: 'Rita',
        content: 'overall thoughts',
        createdAt: '2026-07-11T00:00:00Z',
        replies: [],
      },
    ]
    await renderView()

    expect(await screen.findByRole('tab', { name: /activity, 1 unresolved comment/i })).toBeInTheDocument()
  })

  it('shows a single empty state when there are no documents', async () => {
    DOCS = []
    await renderView()

    const matches = await screen.findAllByText(/no documents attached yet/i)
    expect(matches).toHaveLength(1)
  })

  it('changes status from the properties panel', async () => {
    await renderView()
    const panel = within(screen.getByTestId('properties-panel'))

    await userEvent.click(await panel.findByRole('button', { name: /in review/i }))
    await userEvent.click(await screen.findByRole('menuitem', { name: /approve & close/i }))

    await waitFor(() => {
      expect(apiPatch).toHaveBeenCalledWith(
        expect.stringContaining('/work-items/wi-1'),
        { status: 'DONE' },
        'token'
      )
    })
  })

  it('reassigns from the properties panel', async () => {
    await renderView()
    const panel = within(screen.getByTestId('properties-panel'))

    await userEvent.click(panel.getByRole('button', { name: /reassign, currently ada admin/i }))
    await userEvent.click(await screen.findByText('Cara Creator'))

    await waitFor(() => {
      expect(apiPatch).toHaveBeenCalledWith(
        expect.stringContaining('/work-items/wi-1'),
        { assigneeId: 'user-2' },
        'token'
      )
    })
  })

  it('assigns and unassigns a reviewer from the properties panel', async () => {
    REVIEWERS = []
    await renderView()
    const panel = within(screen.getByTestId('properties-panel'))

    await userEvent.click(panel.getByRole('button', { name: /add reviewer/i }))
    await userEvent.click(await screen.findByText('Rita Reviewer'))

    await waitFor(() => {
      expect(apiPost).toHaveBeenCalledWith(
        expect.stringContaining('/reviewers'),
        { userId: 'user-3' },
        'token'
      )
    })

    await userEvent.click(await within(screen.getByTestId('properties-panel')).findByRole('button', { name: /unassign rita reviewer/i }))
    await waitFor(() => {
      expect(apiDelete).toHaveBeenCalledWith(expect.stringContaining('/reviewers/user-3'), 'token')
    })
  })

  it('renders the comment popover within the document container, not viewport-fixed', async () => {
    await renderView()
    const gutter = screen.getByLabelText('comment gutter')
    fireEvent.click(gutter.querySelectorAll('button')[0])

    const popover = await screen.findByTestId('comment-popover')
    expect(popover.className).toContain('absolute')
    expect(popover.className).not.toContain('fixed')
  })

  it('interleaves and sorts comments and reviews on the Activity tab', async () => {
    COMMENTS = [
      {
        id: 'c1',
        documentId: 'doc-1',
        authorId: 'user-2',
        authorName: 'Cara Creator',
        content: 'earliest comment',
        createdAt: '2026-07-01T00:00:00Z',
        replies: [],
      },
    ]
    REVIEWS = [
      { reviewerId: 'user-1', name: 'Ada Admin', verdict: 'APPROVED', body: 'looks good', submittedAt: '2026-07-05T00:00:00Z' },
    ]
    await renderView()

    // Prefix match: the tab's accessible name also carries an unresolved comment count when it has one.
    await userEvent.click(screen.getByRole('tab', { name: /^Activity/ }))

    const rows = await screen.findAllByTestId(/^activity-/)
    expect(rows).toHaveLength(2)
    // Newest first: the review (Jul 5) before the comment (Jul 1).
    expect(rows[0]).toHaveTextContent('looks good')
    expect(rows[1]).toHaveTextContent('earliest comment')
  })

  it('registers a Work item palette group and unregisters it on unmount', async () => {
    const { unmount } = await renderView()

    expect(registerSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        group: 'Work item',
        actions: expect.arrayContaining([
          expect.objectContaining({ id: 'wi-change-status' }),
          expect.objectContaining({ id: 'wi-assign' }),
          expect.objectContaining({ id: 'wi-start-review' }),
        ]),
      })
    )

    unmount()
    expect(unregisterSpy).toHaveBeenCalled()
  })

  describe('batch review mode', () => {
    it('holds a drafted comment as pending instead of posting it, then submits comments + verdict together', async () => {
      await renderView()

      await userEvent.click(screen.getByRole('button', { name: /start review/i }))
      const bar = within(await screen.findByTestId('review-bar'))
      expect(bar.getByText(/reviewing — 0 pending comments/i)).toBeInTheDocument()

      // Draft a comment on line 1 — held client-side, not posted.
      const gutter = screen.getByLabelText('comment gutter')
      fireEvent.click(gutter.querySelectorAll('button')[0])
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'please fix this')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))

      expect(apiPost).not.toHaveBeenCalledWith(expect.stringContaining('/comments'), expect.anything(), expect.anything())
      expect(within(screen.getByTestId('review-bar')).getByText(/reviewing — 1 pending comment/i)).toBeInTheDocument()

      // Submitting Approve posts the pending comment, then the review, then leaves review mode.
      await userEvent.click(within(screen.getByTestId('review-bar')).getByRole('button', { name: 'Approve' }))

      await waitFor(() => {
        expect(apiPost).toHaveBeenCalledWith(
          expect.stringContaining('/comments'),
          expect.objectContaining({ documentId: 'doc-1', lineNumber: 1, content: 'please fix this' }),
          'token'
        )
      })
      await waitFor(() => {
        expect(apiPost).toHaveBeenCalledWith(
          expect.stringContaining('/reviews'),
          expect.objectContaining({ verdict: 'APPROVED' }),
          'token'
        )
      })
      await waitFor(() => {
        expect(screen.queryByTestId('review-bar')).not.toBeInTheDocument()
      })
      expect(toastSuccessSpy).toHaveBeenCalled()
    })

    it('persists the in-progress review draft across a remount', async () => {
      const { unmount } = await renderView()
      await userEvent.click(screen.getByRole('button', { name: /start review/i }))
      const gutter = screen.getByLabelText('comment gutter')
      fireEvent.click(gutter.querySelectorAll('button')[0])
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'draft across remount')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))
      expect(within(screen.getByTestId('review-bar')).getByText(/1 pending comment/i)).toBeInTheDocument()

      unmount()

      await renderView()
      const bar = within(await screen.findByTestId('review-bar'))
      expect(bar.getByText(/reviewing — 1 pending comment/i)).toBeInTheDocument()
    })

    it('cancel discards pending comments after a confirm when any are drafted', async () => {
      await renderView()
      await userEvent.click(screen.getByRole('button', { name: /start review/i }))
      const gutter = screen.getByLabelText('comment gutter')
      fireEvent.click(gutter.querySelectorAll('button')[0])
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'about to discard')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))

      await userEvent.click(within(screen.getByTestId('review-bar')).getByRole('button', { name: 'Cancel' }))
      const modal = within(await screen.findByTestId('modal'))
      expect(modal.getByText(/discard pending comments/i)).toBeInTheDocument()

      // "Keep reviewing" backs out without discarding.
      await userEvent.click(modal.getByRole('button', { name: /keep reviewing/i }))
      expect(screen.queryByTestId('modal')).not.toBeInTheDocument()
      expect(screen.getByTestId('review-bar')).toBeInTheDocument()

      await userEvent.click(within(screen.getByTestId('review-bar')).getByRole('button', { name: 'Cancel' }))
      await userEvent.click(within(await screen.findByTestId('modal')).getByRole('button', { name: /^discard$/i }))

      expect(screen.queryByTestId('review-bar')).not.toBeInTheDocument()
      expect(localStorage.getItem('wi_review_wi-1_user-1')).toBeNull()
    })

    it('scopes the persisted draft key to the signed-in user', async () => {
      await renderView()
      await userEvent.click(screen.getByRole('button', { name: /start review/i }))
      const gutter = screen.getByLabelText('comment gutter')
      fireEvent.click(gutter.querySelectorAll('button')[0])
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'scoped draft')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))

      const raw = localStorage.getItem('wi_review_wi-1_user-1')
      expect(raw).not.toBeNull()
      expect(JSON.parse(raw!)).toMatchObject({ version: 1, active: true })
      // Never under the old unscoped key — a shared browser must not leak drafts across accounts.
      expect(localStorage.getItem('wi_review_wi-1')).toBeNull()
    })

    it('ignores a malformed persisted draft instead of crashing', async () => {
      localStorage.setItem('wi_review_wi-1_user-1', JSON.stringify({ not: 'a valid draft' }))
      await renderView()
      expect(screen.queryByTestId('review-bar')).not.toBeInTheDocument()
    })

    it('discards a persisted draft and toasts when the current state no longer allows review', async () => {
      // The user is no longer an assigned reviewer, so the restored draft is no longer eligible.
      REVIEWERS = []
      localStorage.setItem(
        'wi_review_wi-1_user-1',
        JSON.stringify({
          version: 1,
          active: true,
          pending: [{ localId: 'p1', documentId: 'doc-1', lineNumber: 1, content: 'stale' }],
        })
      )
      await renderView()

      expect(screen.queryByTestId('review-bar')).not.toBeInTheDocument()
      await waitFor(() => {
        expect(toastErrorSpy).toHaveBeenCalledWith(expect.stringMatching(/discarded.*moved on/i))
      })
      expect(localStorage.getItem('wi_review_wi-1_user-1')).toBeNull()
    })

    it('drops a draft comment referencing a document that no longer exists, on hydration', async () => {
      localStorage.setItem(
        'wi_review_wi-1_user-1',
        JSON.stringify({
          version: 1,
          active: true,
          pending: [
            { localId: 'p1', documentId: 'doc-1', lineNumber: 1, content: 'still valid' },
            { localId: 'p2', documentId: 'doc-gone', lineNumber: 1, content: 'orphaned' },
          ],
        })
      )
      await renderView()

      const bar = within(await screen.findByTestId('review-bar'))
      expect(bar.getByText(/reviewing — 1 pending comment/i)).toBeInTheDocument()
      await waitFor(() => {
        expect(toastErrorSpy).toHaveBeenCalledWith(expect.stringMatching(/1 draft comment.*discarded/i))
      })
    })

    it('drops stale drafts at submit time too, without blocking the ones that are still valid', async () => {
      // Three lines up front so line 3 is a valid draft target.
      DOCS = [{ id: 'doc-1', filename: 'prd.md', contentType: 'text/markdown', content: 'line one\nline two\nline three' }]
      await renderView()
      await userEvent.click(screen.getByRole('button', { name: /start review/i }))
      const gutter = screen.getByLabelText('comment gutter')

      fireEvent.click(gutter.querySelectorAll('button')[0]) // line 1 — stays valid
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'valid draft')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))

      fireEvent.click(gutter.querySelectorAll('button')[2]) // line 3 — will go stale below
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'about to go stale')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))

      // The doc shrinks to one line before submit — the already-fetched `documents` state holds this
      // exact object, so mutating it in place (as a real edit-out-from-under-the-draft would look,
      // once refetched) is enough; no extra refetch plumbing needed for the test.
      DOCS[0].content = 'line one'

      await userEvent.click(within(screen.getByTestId('review-bar')).getByRole('button', { name: 'Approve' }))

      await waitFor(() => {
        expect(toastErrorSpy).toHaveBeenCalledWith(expect.stringMatching(/1 draft comment.*discarded/i))
      })
      await waitFor(() => {
        expect(apiPost).toHaveBeenCalledWith(
          expect.stringContaining('/comments'),
          expect.objectContaining({ content: 'valid draft' }),
          'token'
        )
      })
      expect(apiPost).not.toHaveBeenCalledWith(
        expect.stringContaining('/comments'),
        expect.objectContaining({ content: 'about to go stale' }),
        'token'
      )
      await waitFor(() => {
        expect(screen.queryByTestId('review-bar')).not.toBeInTheDocument()
      })
    })

    it('clears succeeded comments from pending even when the verdict POST fails, so a retry cannot double-post', async () => {
      await renderView()
      await userEvent.click(screen.getByRole('button', { name: /start review/i }))
      const gutter = screen.getByLabelText('comment gutter')
      fireEvent.click(gutter.querySelectorAll('button')[0])
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'about to succeed')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))

      const originalPost = apiPost.getMockImplementation()!
      apiPost.mockImplementation(async (url: string, body: Record<string, unknown>, token?: string) => {
        if (url.includes('/reviews')) throw new Error('verdict rejected')
        return originalPost(url, body, token)
      })

      await userEvent.click(within(screen.getByTestId('review-bar')).getByRole('button', { name: 'Approve' }))

      await waitFor(() => {
        expect(apiPost).toHaveBeenCalledWith(expect.stringContaining('/comments'), expect.anything(), 'token')
      })
      // The bar survives the failed verdict POST, but with the already-posted comment cleared out —
      // otherwise a retry would resubmit it alongside the verdict.
      await waitFor(() => {
        expect(within(screen.getByTestId('review-bar')).getByText(/reviewing — 0 pending comments/i)).toBeInTheDocument()
      })

      apiPost.mockImplementation(originalPost)
      const postCallsBefore = apiPost.mock.calls.filter((c) => (c[0] as string).includes('/comments')).length
      await userEvent.click(within(screen.getByTestId('review-bar')).getByRole('button', { name: 'Approve' }))
      await waitFor(() => {
        expect(apiPost).toHaveBeenCalledWith(expect.stringContaining('/reviews'), expect.anything(), 'token')
      })
      const postCallsAfter = apiPost.mock.calls.filter((c) => (c[0] as string).includes('/comments')).length
      expect(postCallsAfter).toBe(postCallsBefore)
    })
  })

  describe('single status control + edit-pending prefill', () => {
    it('renders exactly one interactive status control (the properties panel owns it)', async () => {
      await renderView()
      expect(await screen.findAllByRole('button', { name: /in review/i })).toHaveLength(1)
    })

    it('reopens a pending comment with its existing text instead of blank', async () => {
      await renderView()
      await userEvent.click(screen.getByRole('button', { name: /start review/i }))
      const gutter = screen.getByLabelText('comment gutter')
      fireEvent.click(gutter.querySelectorAll('button')[0])
      await userEvent.type(screen.getByPlaceholderText(/add a comment/i), 'edit me later')
      await userEvent.click(within(await screen.findByTestId('comment-popover')).getByRole('button', { name: 'Comment' }))

      fireEvent.click(gutter.querySelectorAll('button')[0])
      await userEvent.click(await screen.findByLabelText('Edit pending comment'))

      expect(screen.getByDisplayValue('edit me later')).toBeInTheDocument()
    })
  })

  describe('creator byline', () => {
    it('resolves a human creator by member id', async () => {
      ISSUE = { ...ISSUE, createdBy: 'user-2', createdByLabel: undefined }
      await renderView()
      expect(await screen.findByText(/created by cara creator/i)).toBeInTheDocument()
    })

    it('falls back to createdByLabel for an agent-created item with no createdBy', async () => {
      ISSUE = { ...ISSUE, createdBy: undefined, createdByLabel: 'Agent (ceo)' }
      await renderView()
      expect(await screen.findByText(/created by agent \(ceo\)/i)).toBeInTheDocument()
    })
  })

  describe('the tab bar is never a one-way trip', () => {
    it('offers a content tab when there are no documents, so Activity can be left', async () => {
      // With no documents there were no document tabs, so Activity was the only one on the bar — and the
      // caption, media and accounts all hide on Activity. Clicking it stranded you with nothing to click.
      DOCS = []
      await renderView()

      // Named after the Workflow's noun, so it reads as the thing itself.
      await waitFor(() => expect(screen.getByRole('tab', { name: 'PRD' })).toBeInTheDocument())

      await userEvent.click(screen.getByRole('tab', { name: /Activity/ }))
      expect(screen.getByRole('tab', { name: 'PRD' })).toBeInTheDocument()

      await userEvent.click(screen.getByRole('tab', { name: 'PRD' }))
      await waitFor(() =>
        expect(screen.getByRole('tab', { name: 'PRD' })).toHaveAttribute('aria-selected', 'true')
      )
    })
  })
})
