import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, act, waitFor, within } from '@testing-library/react'

const { apiPost } = vi.hoisted(() => ({ apiPost: vi.fn() }))
vi.mock('@/lib/api', () => ({ apiPost }))
vi.mock('@/components/comments/CommentThread', () => ({
  CommentThread: ({ comment }: { comment: { id: string; content: string } }) => (
    <div data-testid={`thread-${comment.id}`}>{comment.content}</div>
  ),
}))
vi.mock('@/components/comments/NewCommentForm', () => ({
  NewCommentForm: ({ onSubmit, onCancel }: { onSubmit: (t: string) => void; onCancel: () => void }) => (
    <div data-testid="new-comment-form">
      <button onClick={() => onSubmit('looks good')}>Submit</button>
      <button onClick={onCancel}>Cancel form</button>
    </div>
  ),
}))

import { CommentableHtmlDocument } from './CommentableHtmlDocument'
import type { Comment, PendingCommentDraft } from '@/components/comments/types'

const RECT = { top: 10, left: 20, right: 120, bottom: 30, width: 100, height: 20 }

function comment(over: Partial<Comment> & { id: string }): Comment {
  return {
    documentId: 'doc-1',
    authorId: 'u1',
    authorName: 'Ada',
    content: `comment ${over.id}`,
    createdAt: '2026-10-01T00:00:00Z',
    replies: [],
    ...over,
  }
}

const baseProps = {
  content: '<html><body><h1>Report</h1></body></html>',
  documentId: 'doc-1',
  issueId: 'wi-1',
  projectId: 'proj-1',
  comments: [] as Comment[],
  onCommentAdded: vi.fn(),
  token: 'tok',
  currentUserId: 'u1',
}

function frameWindow(container: HTMLElement): Window {
  return container.querySelector('iframe')!.contentWindow!
}

/** Delivers a message to the app as if the frame had posted it. */
function fromFrame(source: Window | null, data: unknown) {
  act(() => {
    window.dispatchEvent(new MessageEvent('message', { data, source }))
  })
}

const bridge = (data: Record<string, unknown>) => ({ source: 'conductor-bridge', ...data })

const selection = {
  type: 'selection',
  exact: '20 of 26',
  prefix: 'Reached ',
  suffix: ' users',
  sectionId: 'funnel',
  sectionTitle: 'The funnel',
  rect: RECT,
}

describe('CommentableHtmlDocument', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiPost.mockResolvedValue(undefined)
  })

  it('renders the document in a locked-down frame with the CSP and bridge added', () => {
    const { container } = render(<CommentableHtmlDocument {...baseProps} />)
    const frame = container.querySelector('iframe')!
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts allow-popups allow-popups-to-escape-sandbox')
    const srcdoc = frame.getAttribute('srcdoc')!
    expect(srcdoc).toContain('Content-Security-Policy')
    expect(srcdoc).toContain('conductor-bridge')
    expect(srcdoc).toContain('<h1>Report</h1>')
  })

  it('ignores messages that do not come from its frame', () => {
    const { container } = render(<CommentableHtmlDocument {...baseProps} />)
    fromFrame(window, bridge(selection)) // some other window
    fromFrame(null, bridge(selection))
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()

    // The right window but not the bridge's envelope, or a malformed body.
    fromFrame(frameWindow(container), { ...selection })
    fromFrame(frameWindow(container), bridge({ ...selection, exact: '' }))
    fromFrame(frameWindow(container), bridge({ ...selection, rect: 'nope' }))
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()
  })

  it('shows a Comment button for a selection and posts the anchor', async () => {
    const onCommentAdded = vi.fn()
    const { container } = render(
      <CommentableHtmlDocument {...baseProps} onCommentAdded={onCommentAdded} />
    )
    fromFrame(frameWindow(container), bridge(selection))

    fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
    expect(screen.getByTestId('comment-popover')).toHaveTextContent('20 of 26')
    fireEvent.click(screen.getByText('Submit'))

    await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/work-items/wi-1/comments',
      {
        documentId: 'doc-1',
        content: 'looks good',
        anchor: {
          exact: '20 of 26',
          prefix: 'Reached ',
          suffix: ' users',
          sectionId: 'funnel',
          sectionTitle: 'The funnel',
        },
      },
      'tok'
    )
    // No line number: the backend rejects one on an HTML document.
    expect(apiPost.mock.calls[0][1]).not.toHaveProperty('lineNumber')
    await waitFor(() => expect(onCommentAdded).toHaveBeenCalled())
  })

  it('drops the button when the selection is cleared', async () => {
    const { container } = render(<CommentableHtmlDocument {...baseProps} />)
    fromFrame(frameWindow(container), bridge(selection))
    await screen.findByRole('button', { name: 'Comment' })
    fromFrame(frameWindow(container), bridge({ type: 'selection-cleared' }))
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()
  })

  it('in review mode adds a draft with the anchor instead of posting', async () => {
    const onAddPendingComment = vi.fn()
    const { container } = render(
      <CommentableHtmlDocument
        {...baseProps}
        reviewMode
        onAddPendingComment={onAddPendingComment}
      />
    )
    fromFrame(frameWindow(container), bridge(selection))
    fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
    fireEvent.click(screen.getByText('Submit'))

    await waitFor(() => expect(onAddPendingComment).toHaveBeenCalled())
    expect(onAddPendingComment).toHaveBeenCalledWith(
      expect.objectContaining({ exact: '20 of 26', sectionId: 'funnel' }),
      'looks good'
    )
    expect(apiPost).not.toHaveBeenCalled()
  })

  it('sends the anchors to the frame when it is ready, and again when the comments change', () => {
    const comments = [
      comment({ id: 'c1', anchor: { exact: 'alpha', prefix: 'p', suffix: 's' } }),
      comment({ id: 'c2', anchor: null }),
      comment({ id: 'c3', anchor: { exact: 'resolved one' }, resolvedAt: '2026-10-02T00:00:00Z' }),
    ]
    const { container, rerender } = render(<CommentableHtmlDocument {...baseProps} comments={comments} />)
    const win = frameWindow(container)
    const post = vi.spyOn(win, 'postMessage')

    fromFrame(win, bridge({ type: 'ready' }))
    expect(post).toHaveBeenLastCalledWith(
      { type: 'anchors', anchors: [{ id: 'c1', exact: 'alpha', prefix: 'p', suffix: 's' }] },
      '*'
    )

    rerender(
      <CommentableHtmlDocument
        {...baseProps}
        comments={[...comments, comment({ id: 'c4', anchor: { exact: 'beta' } })]}
      />
    )
    expect(post).toHaveBeenLastCalledWith(
      {
        type: 'anchors',
        anchors: [{ id: 'c1', exact: 'alpha', prefix: 'p', suffix: 's' }, { id: 'c4', exact: 'beta' }],
      },
      '*'
    )
  })

  it('orders threads by reported position and lists unfound and anchorless comments as Unplaced', () => {
    const comments = [
      comment({ id: 'low', anchor: { exact: 'lower down' }, lineNumber: 0 }), // the server sends 0 with an anchor
      comment({ id: 'high', anchor: { exact: 'near the top' } }),
      comment({ id: 'gone', anchor: { exact: 'deleted text' }, quotedText: 'deleted text' }),
      comment({ id: 'legacy', anchor: null, lineNumber: 4, quotedText: 'old line' }),
    ]
    const { container } = render(<CommentableHtmlDocument {...baseProps} comments={comments} />)
    fromFrame(
      frameWindow(container),
      bridge({
        type: 'anchored',
        results: [
          { id: 'low', found: true, top: 900 },
          { id: 'high', found: true, top: 40 },
          { id: 'gone', found: false, top: 0 },
        ],
      })
    )

    const rail = screen.getByRole('complementary', { name: 'Comments' })
    const ids = Array.from(rail.querySelectorAll('[data-comment-id]')).map((n) =>
      n.getAttribute('data-comment-id')
    )
    expect(ids).toEqual(['high', 'low', 'gone', 'legacy'])

    const unplaced = within(rail).getByRole('region', { name: 'Unplaced comments' })
    expect(within(unplaced).getByTestId('thread-gone')).toBeInTheDocument()
    expect(within(unplaced).getByTestId('thread-legacy')).toBeInTheDocument()
    expect(within(unplaced).queryByTestId('thread-high')).not.toBeInTheDocument()
  })

  it('badges an outdated comment', () => {
    render(
      <CommentableHtmlDocument
        {...baseProps}
        comments={[
          comment({ id: 'old', anchor: { exact: 'x' }, outdated: true }),
          comment({ id: 'fresh', anchor: { exact: 'y' }, outdated: false }),
        ]}
      />
    )
    expect(screen.getAllByText('Outdated')).toHaveLength(1)
    const card = document.querySelector('[data-comment-id="old"]')!
    expect(within(card as HTMLElement).getByText('Outdated')).toBeInTheDocument()
  })

  it('scrolls the frame to a thread that is clicked, and focuses the thread for a highlight click', () => {
    const { container } = render(
      <CommentableHtmlDocument
        {...baseProps}
        comments={[comment({ id: 'c1', anchor: { exact: 'alpha' } })]}
      />
    )
    const win = frameWindow(container)
    const post = vi.spyOn(win, 'postMessage')
    fromFrame(win, bridge({ type: 'anchored', results: [{ id: 'c1', found: true, top: 10 }] }))

    fireEvent.click(screen.getByTestId('thread-c1'))
    expect(post).toHaveBeenCalledWith({ type: 'scrollTo', id: 'c1' }, '*')

    const card = document.querySelector('[data-comment-id="c1"]') as HTMLElement
    fromFrame(win, bridge({ type: 'open', id: 'c1' }))
    expect(card).toHaveFocus()
  })

  it('shows review drafts in the rail and sends their anchors', () => {
    const drafts: PendingCommentDraft[] = [
      { localId: 'd1', documentId: 'doc-1', anchor: { exact: 'draft quote' }, content: 'my draft' },
      { localId: 'd2', documentId: 'other-doc', anchor: { exact: 'elsewhere' }, content: 'not here' },
    ]
    const { container } = render(
      <CommentableHtmlDocument {...baseProps} reviewMode pendingComments={drafts} />
    )
    expect(screen.getByText('my draft')).toBeInTheDocument()
    expect(screen.queryByText('not here')).not.toBeInTheDocument()

    const win = frameWindow(container)
    const post = vi.spyOn(win, 'postMessage')
    fromFrame(win, bridge({ type: 'ready' }))
    expect(post).toHaveBeenLastCalledWith(
      { type: 'anchors', anchors: [{ id: 'draft:d1', exact: 'draft quote' }] },
      '*'
    )
  })
})
