import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, waitFor, within } from '@testing-library/react'

const { apiGet, apiPost, apiPatch, apiDelete } = vi.hoisted(() => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiDelete: vi.fn(),
}))
vi.mock('@/lib/api', () => ({
  apiGet,
  apiPost,
  apiPatch,
  apiDelete,
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))

import { CommentableHtmlDocument } from './CommentableHtmlDocument'
import type { Comment, PendingCommentDraft } from '@/components/comments/types'

const RECT = { top: 10, left: 20, right: 120, bottom: 30, width: 100, height: 20 }

function comment(over: Partial<Comment> & { id: string }): Comment {
  return {
    documentId: 'doc-1',
    kind: over.anchor ? 'QUOTE' : 'DOCUMENT',
    status: 'OPEN',
    authorId: 'u1',
    authorName: 'Ada',
    authorKind: 'USER',
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
  token: 'tok',
  currentUserId: 'u1',
}

/** Renders the viewer and waits for its comments to load. */
async function renderDoc(comments: Comment[] = [], props: Partial<React.ComponentProps<typeof CommentableHtmlDocument>> = {}) {
  apiGet.mockResolvedValue(comments)
  const utils = render(<CommentableHtmlDocument {...baseProps} {...props} />)
  await waitFor(() => expect(apiGet).toHaveBeenCalled())
  // Let the list land. Anchored threads only show once the frame has placed them (see the tests below).
  await act(async () => {})
  return utils
}

function frameWindow(container: HTMLElement): Window {
  return container.querySelector('iframe')!.contentWindow!
}

/**
 * Delivers a message to the app as if the frame had posted it. A sandboxed srcdoc frame has the opaque
 * origin "null", which is what the app requires.
 */
function fromFrame(source: Window | null, data: unknown, origin = 'null') {
  act(() => {
    window.dispatchEvent(new MessageEvent('message', { data, source, origin }))
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

/** Types into the open composer and sends it with Cmd+Enter. */
function send(text: string, label = 'Comment text') {
  const box = screen.getByRole('textbox', { name: label })
  fireEvent.change(box, { target: { value: text } })
  fireEvent.keyDown(box, { key: 'Enter', metaKey: true })
}

function cardIds(root: HTMLElement): (string | null)[] {
  return Array.from(root.querySelectorAll('[data-comment-id]')).map((n) => n.getAttribute('data-comment-id'))
}

describe('CommentableHtmlDocument', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiGet.mockResolvedValue([])
    apiPost.mockResolvedValue(undefined)
    apiPatch.mockResolvedValue(undefined)
    apiDelete.mockResolvedValue(undefined)
  })

  it('renders the document in a locked-down frame with the CSP and bridge added', async () => {
    const { container } = await renderDoc()
    const frame = container.querySelector('iframe')!
    expect(frame.getAttribute('sandbox')).toBe('allow-scripts')
    const srcdoc = frame.getAttribute('srcdoc')!
    expect(srcdoc).toContain('Content-Security-Policy')
    expect(srcdoc).toContain('conductor-bridge')
    expect(srcdoc).toContain('<h1>Report</h1>')
  })

  it('loads the comments of this document from the unified resource', async () => {
    await renderDoc()
    expect(apiGet).toHaveBeenCalledWith(
      '/api/v2/projects/proj-1/comments?workItemId=wi-1&documentId=doc-1&status=all',
      'tok'
    )
  })

  it('puts the toolbar action at the right end of its toolbar', async () => {
    await renderDoc([], { toolbarAction: <button type="button">Full view</button> })
    const hint = screen.getByText('Select text in the document to comment on it.')
    expect(hint.parentElement).toContainElement(screen.getByRole('button', { name: 'Full view' }))
  })

  it('ignores messages that do not come from its frame', async () => {
    const { container } = await renderDoc()
    fromFrame(window, bridge(selection)) // some other window
    fromFrame(null, bridge(selection))
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()

    // The right window but not the bridge's envelope, or a malformed body.
    fromFrame(frameWindow(container), { ...selection })
    fromFrame(frameWindow(container), bridge({ ...selection, exact: '' }))
    fromFrame(frameWindow(container), bridge({ ...selection, rect: 'nope' }))
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()
  })

  it('ignores messages from a frame that has navigated away (origin is no longer "null")', async () => {
    const { container } = await renderDoc()
    const win = frameWindow(container)
    // Same contentWindow object, but the page is now some other site.
    fromFrame(win, bridge(selection), 'https://evil.example')
    fromFrame(win, bridge(selection), '')
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()

    const open = vi.spyOn(window, 'open').mockReturnValue(null)
    fromFrame(win, bridge({ type: 'openLink', href: 'https://example.com' }), 'https://evil.example')
    expect(open).not.toHaveBeenCalled()
    open.mockRestore()
  })

  it('opens an http(s) link the frame asks for in a new tab, and nothing else', async () => {
    const { container } = await renderDoc()
    const win = frameWindow(container)
    const open = vi.spyOn(window, 'open').mockReturnValue(null)

    fromFrame(win, bridge({ type: 'openLink', href: 'https://example.com/docs?a=1' }))
    expect(open).toHaveBeenCalledTimes(1)
    expect(open).toHaveBeenCalledWith('https://example.com/docs?a=1', '_blank', 'noopener,noreferrer')

    fromFrame(win, bridge({ type: 'openLink', href: 'javascript:alert(1)' }))
    fromFrame(win, bridge({ type: 'openLink', href: 'data:text/html,<b>x</b>' }))
    fromFrame(win, bridge({ type: 'openLink', href: 'relative/page.html' }))
    expect(open).toHaveBeenCalledTimes(1)
    open.mockRestore()
  })

  describe('writing comments', () => {
    it('shows a Comment button for a selection and posts the anchor to the unified resource', async () => {
      const onCommentsChanged = vi.fn()
      apiPost.mockResolvedValue(comment({ id: 'new', content: 'looks good', anchor: { exact: '20 of 26' } }))
      const { container } = await renderDoc([], { onCommentsChanged })
      fromFrame(frameWindow(container), bridge(selection))

      fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
      expect(screen.getByTestId('comment-popover')).toHaveTextContent('20 of 26')
      // The composer takes focus at once.
      expect(screen.getByRole('textbox', { name: 'Comment text' })).toHaveFocus()
      send('looks good')

      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
      expect(apiPost).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/comments',
        {
          target: { workItemId: 'wi-1', documentId: 'doc-1' },
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
      await waitFor(() => expect(onCommentsChanged).toHaveBeenCalled())
    })

    it('shows the new comment at once and takes it back, with the text kept out of the list, when the server refuses', async () => {
      let reject: (e: Error) => void = () => {}
      apiPost.mockReturnValue(new Promise((_, r) => (reject = r)))
      await renderDoc()
      fireEvent.click(screen.getByRole('button', { name: /Comment on whole document/ }))
      send('optimistic', 'Comment on whole document')
      await waitFor(() => expect(document.querySelector('[data-comment-id^="tmp:"]')).not.toBeNull())
      await act(async () => reject(new Error('nope')))
      await waitFor(() => expect(document.querySelector('[data-comment-id^="tmp:"]')).toBeNull())
    })

    it('stamps a new comment with the hash of the document version on screen', async () => {
      const { container, rerender } = await renderDoc([], { contentHash: 'hash-v1' })
      fromFrame(frameWindow(container), bridge(selection))
      fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
      send('one')
      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
      expect(apiPost.mock.calls[0][1]).toMatchObject({ documentHash: 'hash-v1' })

      // No hash known (older server): the field is left out rather than sent empty.
      apiPost.mockClear()
      rerender(<CommentableHtmlDocument {...baseProps} contentHash={null} />)
      fromFrame(frameWindow(container), bridge(selection))
      fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
      send('two')
      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
      expect(apiPost.mock.calls[0][1]).not.toHaveProperty('documentHash')
    })

    it('Escape closes the composer without posting', async () => {
      const { container } = await renderDoc()
      fromFrame(frameWindow(container), bridge(selection))
      fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
      fireEvent.keyDown(screen.getByRole('textbox', { name: 'Comment text' }), { key: 'Escape' })
      expect(screen.queryByTestId('comment-popover')).not.toBeInTheDocument()
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('drops the button when the selection is cleared', async () => {
      const { container } = await renderDoc()
      fromFrame(frameWindow(container), bridge(selection))
      await screen.findByRole('button', { name: 'Comment' })
      fromFrame(frameWindow(container), bridge({ type: 'selection-cleared' }))
      expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()
    })

    it('adds a whole-document comment with no anchor', async () => {
      apiPost.mockResolvedValue(comment({ id: 'w' }))
      await renderDoc()
      fireEvent.click(screen.getByRole('button', { name: /Comment on whole document/ }))
      send('overall', 'Comment on whole document')
      await waitFor(() => expect(apiPost).toHaveBeenCalledTimes(1))
      const body = apiPost.mock.calls[0][1]
      expect(body).toMatchObject({ target: { workItemId: 'wi-1', documentId: 'doc-1' }, content: 'overall' })
      expect(body).not.toHaveProperty('anchor')
      expect(body).not.toHaveProperty('lineNumber')
    })

    it('hands the hash to a review-mode draft', async () => {
      const onAddPendingComment = vi.fn()
      const { container } = await renderDoc([], { contentHash: 'hash-v1', reviewMode: true, onAddPendingComment })
      fromFrame(frameWindow(container), bridge(selection))
      fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
      send('looks good')
      await waitFor(() => expect(onAddPendingComment).toHaveBeenCalled())
      expect(onAddPendingComment).toHaveBeenCalledWith(expect.objectContaining({ exact: '20 of 26' }), 'looks good', 'hash-v1')
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('in review mode offers no whole-document comment, since a draft needs an anchor', async () => {
      await renderDoc([], { reviewMode: true })
      expect(screen.queryByRole('button', { name: /Comment on whole document/ })).not.toBeInTheDocument()
    })
  })

  describe('talking to the frame', () => {
    it('sends the anchors of open threads when the frame is ready, and again when they change', async () => {
      const comments = [
        comment({ id: 'c1', anchor: { exact: 'alpha', prefix: 'p', suffix: 's' } }),
        comment({ id: 'c2' }),
        comment({ id: 'c3', anchor: { exact: 'resolved one' }, status: 'RESOLVED', resolvedAt: '2026-10-02T00:00:00Z' }),
      ]
      const { container } = await renderDoc(comments)
      const win = frameWindow(container)
      const post = vi.spyOn(win, 'postMessage')

      fromFrame(win, bridge({ type: 'ready' }))
      expect(post).toHaveBeenCalledWith(
        { type: 'anchors', anchors: [{ id: 'c1', exact: 'alpha', prefix: 'p', suffix: 's' }] },
        '*'
      )

      // A new comment adds its anchor.
      apiPost.mockResolvedValue(comment({ id: 'c4', anchor: { exact: 'beta' }, content: 'fresh' }))
      fromFrame(win, bridge(selection))
      fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
      send('fresh')
      await waitFor(() =>
        expect(post).toHaveBeenLastCalledWith(
          expect.objectContaining({ type: 'anchors' }),
          '*'
        )
      )
    })

    it('tells the frame the highlight colours from the theme tokens, and only plain colours', async () => {
      const root = document.documentElement
      root.style.setProperty('--comment-highlight', '45 90% 55% / 0.28')
      root.style.setProperty('--comment-highlight-active', '40 90% 50% / 0.5')
      const { container } = await renderDoc()
      const win = frameWindow(container)
      const post = vi.spyOn(win, 'postMessage')
      fromFrame(win, bridge({ type: 'ready' }))
      expect(post).toHaveBeenCalledWith(
        { type: 'theme', highlight: 'hsl(45 90% 55% / 0.28)', active: 'hsl(40 90% 50% / 0.5)' },
        '*'
      )

      post.mockClear()
      root.style.setProperty('--comment-highlight', 'red; } body { display:none')
      fromFrame(win, bridge({ type: 'ready' }))
      expect(post.mock.calls.some(([m]) => (m as { type: string }).type === 'theme')).toBe(false)
      root.style.removeProperty('--comment-highlight')
      root.style.removeProperty('--comment-highlight-active')
    })

    it('tells the frame which thread is focused', async () => {
      const { container } = await renderDoc([comment({ id: 'c1', anchor: { exact: 'alpha' } })])
      const win = frameWindow(container)
      const post = vi.spyOn(win, 'postMessage')
      fromFrame(win, bridge({ type: 'ready' }))
      fromFrame(win, bridge({ type: 'anchored', results: [{ id: 'c1', found: true, top: 10 }] }))
      fromFrame(win, bridge({ type: 'open', id: 'c1' }))
      expect(post).toHaveBeenLastCalledWith({ type: 'active', id: 'c1' }, '*')
    })
  })

  describe('the rail', () => {
    async function twoPlaced() {
      const comments = [
        comment({ id: 'low', anchor: { exact: 'lower down' } }),
        comment({ id: 'high', anchor: { exact: 'near the top' } }),
      ]
      const utils = await renderDoc(comments)
      const win = frameWindow(utils.container)
      fromFrame(
        win,
        bridge({
          type: 'anchored',
          results: [
            { id: 'low', found: true, top: 900 },
            { id: 'high', found: true, top: 40 },
          ],
        })
      )
      return { ...utils, win }
    }

    it('lines each card up with its highlight, shifted by how far the frame has scrolled', async () => {
      const { win } = await twoPlaced()
      fromFrame(win, bridge({ type: 'layout', scrollY: 100, viewportH: 800, tops: [{ id: 'high', top: 340 }, { id: 'low', top: 900 }] }))
      const high = document.querySelector('[data-rail-id="high"]') as HTMLElement
      const low = document.querySelector('[data-rail-id="low"]') as HTMLElement
      // 340 (the highlight) - 8 (card header sits just above the text) - 100 (scrolled).
      expect(high.style.transform).toBe('translate3d(0, 232px, 0)')
      expect(low.style.transform).toBe('translate3d(0, 792px, 0)')

      fromFrame(win, bridge({ type: 'layout', scrollY: 300, viewportH: 800, tops: [{ id: 'high', top: 340 }, { id: 'low', top: 900 }] }))
      expect(high.style.transform).toBe('translate3d(0, 32px, 0)')
    })

    it('orders cards by position in the document', async () => {
      await twoPlaced()
      const rail = screen.getByRole('complementary', { name: 'Comments' })
      expect(cardIds(rail).filter((id) => id === 'high' || id === 'low')).toEqual(['high', 'low'])
    })

    it('focuses a thread when its highlight is clicked, and scrolls to the highlight when its card is clicked', async () => {
      const { win } = await twoPlaced()
      const post = vi.spyOn(win, 'postMessage')

      fromFrame(win, bridge({ type: 'open', id: 'low' }))
      expect(document.querySelector('[data-comment-id="low"]')).toHaveAttribute('data-active', 'true')

      fireEvent.click(screen.getByText('comment high'))
      expect(post).toHaveBeenCalledWith({ type: 'scrollTo', id: 'high' }, '*')
      expect(document.querySelector('[data-comment-id="high"]')).toHaveAttribute('data-active', 'true')
      expect(document.querySelector('[data-comment-id="low"]')).not.toHaveAttribute('data-active')
    })

    it('steps through open threads with j and k, and from the frame', async () => {
      const { win } = await twoPlaced()
      const post = vi.spyOn(win, 'postMessage')
      expect(screen.getByTestId('rail-position')).toHaveTextContent('– of 2')

      fireEvent.keyDown(document.body, { key: 'j' })
      expect(document.querySelector('[data-comment-id="high"]')).toHaveAttribute('data-active', 'true')
      expect(screen.getByTestId('rail-position')).toHaveTextContent('1 of 2')
      expect(post).toHaveBeenCalledWith({ type: 'scrollTo', id: 'high' }, '*')

      fromFrame(win, bridge({ type: 'key', key: 'j' }))
      expect(document.querySelector('[data-comment-id="low"]')).toHaveAttribute('data-active', 'true')

      fireEvent.keyDown(document.body, { key: 'k' })
      expect(document.querySelector('[data-comment-id="high"]')).toHaveAttribute('data-active', 'true')

      fromFrame(win, bridge({ type: 'key', key: 'Escape' }))
      expect(document.querySelector('[data-active="true"]')).toBeNull()
    })

    it('keeps the thread focused after a reply and after resolving it', async () => {
      apiPost.mockResolvedValue({ id: 'r1', authorId: 'u1', authorName: 'Ada', content: 'thanks', createdAt: '2026-10-02T00:00:00Z' })
      apiPatch.mockResolvedValue(undefined)
      const { win } = await twoPlaced()
      fromFrame(win, bridge({ type: 'open', id: 'high' }))

      send('thanks', 'Reply')
      await screen.findByText('thanks')
      expect(document.querySelector('[data-comment-id="high"]')).toHaveAttribute('data-active', 'true')
      expect(apiPost).toHaveBeenCalledWith('/api/v2/projects/proj-1/comments/high/replies', { content: 'thanks' }, 'tok')

      fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
      await waitFor(() =>
        expect(apiPatch).toHaveBeenCalledWith('/api/v2/projects/proj-1/comments/high', { status: 'RESOLVED' }, 'tok')
      )
      const card = document.querySelector('[data-comment-id="high"]') as HTMLElement
      expect(card).toHaveAttribute('data-active', 'true')
      expect(card).toHaveAttribute('data-state', 'resolved')
    })

    it('filters by Open, Resolved and Mine', async () => {
      const comments = [
        comment({ id: 'mine', anchor: { exact: 'a' }, authorId: 'u1' }),
        comment({ id: 'theirs', anchor: { exact: 'b' }, authorId: 'u2', authorName: 'Sam' }),
        comment({ id: 'done', status: 'RESOLVED', resolvedAt: '2026-10-02T00:00:00Z', resolvedByName: 'Sam', anchor: { exact: 'c' } }),
      ]
      const { win } = await (async () => {
        const u = await renderDoc(comments)
        return { ...u, win: frameWindow(u.container) }
      })()
      fromFrame(
        win,
        bridge({
          type: 'anchored',
          results: [
            { id: 'mine', found: true, top: 10 },
            { id: 'theirs', found: true, top: 50 },
          ],
        })
      )
      const rail = screen.getByRole('complementary', { name: 'Comments' })
      expect(cardIds(rail)).toEqual(expect.arrayContaining(['mine', 'theirs']))
      expect(cardIds(rail)).not.toContain('done')

      fireEvent.click(screen.getByRole('button', { name: 'Mine' }))
      expect(cardIds(rail)).toEqual(['mine'])

      fireEvent.click(screen.getByRole('button', { name: 'Resolved' }))
      expect(cardIds(rail)).toEqual(['done'])
      expect(screen.getByText('Resolved by Sam')).toBeInTheDocument()
    })
  })

  describe('whole document', () => {
    it('lists whole-document comments, text that is gone, and old line comments there, with a note', async () => {
      const comments = [
        comment({ id: 'doc', kind: 'DOCUMENT' }),
        comment({ id: 'gone', anchor: { exact: 'deleted text' }, quotedText: 'deleted text' }),
        comment({ id: 'legacy', kind: 'LINE', anchor: null, lineNumber: 4, quotedText: 'old line' }),
        comment({ id: 'here', anchor: { exact: 'present' } }),
      ]
      const { container } = await renderDoc(comments)
      fromFrame(
        frameWindow(container),
        bridge({
          type: 'anchored',
          results: [
            { id: 'gone', found: false, top: 0 },
            { id: 'here', found: true, top: 40 },
          ],
        })
      )
      const whole = screen.getByRole('region', { name: 'Whole document' })
      expect(cardIds(whole)).toEqual(['doc', 'gone', 'legacy'])
      expect(within(whole).getByText(/that text isn.t in this version/)).toBeInTheDocument()
      expect(within(whole).getByText(/deleted text/)).toBeInTheDocument()
      expect(within(whole).getByText(/Was on line 4/)).toBeInTheDocument()
      expect(within(whole).queryByText('comment here')).not.toBeInTheDocument()
    })
  })

  describe('review drafts', () => {
    it('shows drafts as cards with a Pending pill and sends their anchors', async () => {
      const drafts: PendingCommentDraft[] = [
        { localId: 'd1', documentId: 'doc-1', anchor: { exact: 'draft quote' }, content: 'my draft' },
        { localId: 'd2', documentId: 'other-doc', anchor: { exact: 'elsewhere' }, content: 'not here' },
      ]
      const { container } = await renderDoc([], { reviewMode: true, pendingComments: drafts })
      fromFrame(frameWindow(container), bridge({ type: 'anchored', results: [{ id: 'draft:d1', found: true, top: 20 }] }))
      expect(screen.getByText('my draft')).toBeInTheDocument()
      expect(screen.getByText('Pending')).toBeInTheDocument()
      expect(screen.queryByText('not here')).not.toBeInTheDocument()

      const win = frameWindow(container)
      const post = vi.spyOn(win, 'postMessage')
      fromFrame(win, bridge({ type: 'ready' }))
      expect(post).toHaveBeenCalledWith(
        { type: 'anchors', anchors: [{ id: 'draft:d1', exact: 'draft quote' }] },
        '*'
      )
    })

    it('lets a draft be edited and removed', async () => {
      const onEdit = vi.fn()
      const onRemove = vi.fn()
      const drafts: PendingCommentDraft[] = [
        { localId: 'd1', documentId: 'doc-1', anchor: { exact: 'q' }, content: 'my draft' },
      ]
      const { container } = await renderDoc([], {
        reviewMode: true,
        pendingComments: drafts,
        onEditPendingComment: onEdit,
        onRemovePendingComment: onRemove,
      })
      fromFrame(frameWindow(container), bridge({ type: 'anchored', results: [{ id: 'draft:d1', found: true, top: 20 }] }))
      fireEvent.pointerDown(screen.getByRole('button', { name: 'More actions' }), { button: 0, ctrlKey: false })
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
      expect(onRemove).toHaveBeenCalledWith('d1')
    })
  })

  describe('layout', () => {
    it('docks the rail from the start, so a document with no comments can still be commented on as a whole', async () => {
      await renderDoc()
      expect(screen.getByRole('complementary', { name: 'Comments' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Comment on whole document/ })).toBeInTheDocument()
      expect(screen.getByText('Select text in the document to comment on it.')).toBeInTheDocument()
    })

    it('places the Comment button above the selection so it does not cover the text', async () => {
      const { container } = await renderDoc()
      fromFrame(
        frameWindow(container),
        bridge({ ...selection, rect: { top: 200, left: 100, right: 300, bottom: 220, width: 200, height: 20 } })
      )
      const button = await screen.findByRole('button', { name: 'Comment' })
      // Centred on the selection (200 - 112/2 = 144) and 8px above it (200 - 32 - 8 = 160).
      expect(button.style.left).toBe('144px')
      expect(button.style.top).toBe('160px')
    })

    it('drops the Comment button below a selection at the top edge of the frame', async () => {
      const { container } = await renderDoc()
      fromFrame(frameWindow(container), bridge(selection)) // RECT.top is 10
      const button = await screen.findByRole('button', { name: 'Comment' })
      expect(button.style.top).toBe(`${RECT.bottom + 8}px`)
    })

    describe('on a narrower screen', () => {
      const originalRO = globalThis.ResizeObserver
      const w = window as unknown as { matchMedia?: unknown }
      /** Pretends the browser matches the queries that contain any of `active`. */
      function media(...active: string[]) {
        w.matchMedia = (q: string) => ({
          matches: active.some((a) => q.includes(a)),
          media: q,
          addEventListener: () => {},
          removeEventListener: () => {},
        })
      }
      function narrowWrapper(width: number) {
        globalThis.ResizeObserver = class {
          constructor(private cb: ResizeObserverCallback) {}
          observe() {
            this.cb([{ contentRect: { width } } as ResizeObserverEntry], this as unknown as ResizeObserver)
          }
          unobserve() {}
          disconnect() {}
        } as unknown as typeof ResizeObserver
      }
      afterEach(() => {
        globalThis.ResizeObserver = originalRO
        delete w.matchMedia
      })

      async function placed(ids: string[], mode: 'tablet' | 'phone') {
        if (mode === 'tablet') media('max-width: 1023px')
        else media('max-width: 767px')
        const list = ids.map((id) => comment({ id, anchor: { exact: id } }))
        apiGet.mockResolvedValue(list)
        const utils = render(<CommentableHtmlDocument {...baseProps} />)
        await waitFor(() => expect(apiGet).toHaveBeenCalled())
        await act(async () => {})
        const win = frameWindow(utils.container)
        fromFrame(win, bridge({ type: 'ready' }))
        fromFrame(win, bridge({ type: 'anchored', results: ids.map((id, n) => ({ id, found: true, top: 100 + n * 200 })) }))
        fromFrame(
          win,
          bridge({
            type: 'layout',
            scrollY: 0,
            viewportH: 800,
            tops: ids.map((id, n) => ({ id, top: 100 + n * 200 })),
          })
        )
        return { ...utils, win }
      }

      describe('tablet', () => {
        it('replaces the rail with count bubbles lined up with the highlights', async () => {
          await placed(['a', 'b'], 'tablet')
          expect(document.querySelector('[data-comment-id]')).toBeNull()
          expect(screen.queryByRole('complementary', { name: 'Comments' })?.id).toBe('html-comment-bubbles')
          const a = document.querySelector('[data-bubble-id="a"]') as HTMLElement
          const b = document.querySelector('[data-bubble-id="b"]') as HTMLElement
          expect(a.style.transform).toBe('translate3d(0, 100px, 0)')
          expect(b.style.transform).toBe('translate3d(0, 300px, 0)')
        })

        it('opens the thread in a side sheet from a bubble, and "All comments" switches it to the list', async () => {
          await placed(['a', 'b'], 'tablet')
          fireEvent.click(document.querySelector('[data-bubble-id="b"]')!)
          const sheet = await screen.findByRole('dialog')
          expect(within(sheet).getByText('comment b')).toBeInTheDocument()
          expect(within(sheet).queryByText('comment a')).not.toBeInTheDocument()

          fireEvent.click(within(sheet).getByRole('button', { name: 'All comments' }))
          expect(within(screen.getByRole('dialog')).getByText('comment a')).toBeInTheDocument()
          expect(within(screen.getByRole('dialog')).getByRole('button', { name: 'Resolved' })).toBeInTheDocument()
        })

        it('opens the thread from a click on its highlight', async () => {
          const { win } = await placed(['a'], 'tablet')
          fromFrame(win, bridge({ type: 'open', id: 'a' }))
          expect(within(await screen.findByRole('dialog')).getByText('comment a')).toBeInTheDocument()
        })

        it('treats a wrapper too narrow for the rail as a tablet even on a wide screen', async () => {
          narrowWrapper(720)
          apiGet.mockResolvedValue([comment({ id: 'c1', anchor: { exact: 'a' } })])
          render(<CommentableHtmlDocument {...baseProps} />)
          await screen.findByRole('button', { name: /^Comments · 1$/ })
          expect(document.getElementById('html-comment-rail')).toBeNull()
          expect(document.getElementById('html-comment-bubbles')).not.toBeNull()
        })
      })

      describe('phone', () => {
        it('shows highlights only: no rail and no bubbles', async () => {
          await placed(['a'], 'phone')
          expect(document.getElementById('html-comment-rail')).toBeNull()
          expect(document.getElementById('html-comment-bubbles')).toBeNull()
          expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
        })

        it('opens the tapped thread in a bottom sheet, which Escape closes', async () => {
          const { win } = await placed(['a', 'b'], 'phone')
          fromFrame(win, bridge({ type: 'open', id: 'b' }))
          const sheet = await screen.findByRole('dialog')
          expect(sheet).toHaveAttribute('data-sheet', 'comments')
          expect(sheet.className).toContain('bottom-0')
          expect(within(sheet).getByText('comment b')).toBeInTheDocument()

          fireEvent.keyDown(document, { key: 'Escape' })
          await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
        })

        it('has a close button', async () => {
          const { win } = await placed(['a'], 'phone')
          fromFrame(win, bridge({ type: 'open', id: 'a' }))
          fireEvent.click(await screen.findByRole('button', { name: 'Close' }))
          await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
        })

        it('lists every comment from "Comments · N", with Whole document at the top', async () => {
          apiGet.mockResolvedValue([
            comment({ id: 'a', anchor: { exact: 'a' } }),
            comment({ id: 'w', kind: 'DOCUMENT', content: 'about it all' }),
          ])
          media('max-width: 767px')
          render(<CommentableHtmlDocument {...baseProps} />)
          fireEvent.click(await screen.findByRole('button', { name: /^Comments · 2$/ }))
          const sheet = await screen.findByRole('dialog')
          const whole = within(sheet).getByRole('region', { name: 'Whole document' })
          expect(within(whole).getByText('about it all')).toBeInTheDocument()
          const list = within(sheet).getByTestId('sheet-list')
          expect(sheet.compareDocumentPosition(whole) & Node.DOCUMENT_POSITION_CONTAINED_BY).toBeTruthy()
          expect(whole.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
        })

        it('keeps j and k working inside the sheet, moving between threads', async () => {
          const { win } = await placed(['a', 'b'], 'phone')
          fromFrame(win, bridge({ type: 'open', id: 'a' }))
          const sheet = await screen.findByRole('dialog')
          expect(within(sheet).getByText('comment a')).toBeInTheDocument()
          fireEvent.keyDown(document, { key: 'j' })
          expect(within(screen.getByRole('dialog')).getByText('comment b')).toBeInTheDocument()
          expect(within(screen.getByRole('dialog')).queryByText('comment a')).not.toBeInTheDocument()
          fireEvent.keyDown(document, { key: 'k' })
          expect(within(screen.getByRole('dialog')).getByText('comment a')).toBeInTheDocument()
        })

        it('composes in the bottom sheet, with the quote shown, instead of a floating card', async () => {
          const { container } = await placed([], 'phone')
          fromFrame(frameWindow(container), bridge(selection))
          fireEvent.click(await screen.findByRole('button', { name: 'Comment' }))
          expect(screen.queryByTestId('comment-popover')).not.toBeInTheDocument()
          const sheet = await screen.findByRole('dialog')
          expect(within(sheet).getByText('20 of 26')).toBeInTheDocument()
          const box = within(sheet).getByRole('textbox', { name: 'Comment text' })
          fireEvent.change(box, { target: { value: 'from my phone' } })
          fireEvent.keyDown(box, { key: 'Enter', metaKey: true })
          await waitFor(() => expect(apiPost).toHaveBeenCalled())
          expect(apiPost.mock.calls[0][1]).toMatchObject({ content: 'from my phone', anchor: { exact: '20 of 26' } })
        })

        it('puts the Comment button below a touch selection, clear of the native menu above it', async () => {
          media('max-width: 767px', 'pointer: coarse')
          const { container } = render(<CommentableHtmlDocument {...baseProps} />)
          await act(async () => {})
          fromFrame(
            frameWindow(container),
            bridge({ ...selection, rect: { top: 200, left: 100, right: 300, bottom: 220, width: 200, height: 20 } })
          )
          const button = await screen.findByRole('button', { name: 'Comment' })
          // Below the selection: its bottom (220) + 8 gap + 22 for the selection handles.
          expect(button.style.top).toBe('250px')
        })
      })
    })
  })
})
