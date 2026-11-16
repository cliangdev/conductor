import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react'

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
vi.mock('@/components/ui/toast', () => ({ toastError: vi.fn() }))
vi.mock('@/components/markdown/MarkdownRenderer', () => ({
  MarkdownRenderer: () => (
    <div data-testid="markdown">
      <h1 id="goals" data-line-start="1" data-line-end="1">
        Goals
      </h1>
      <p data-line-start="3" data-line-end="3">
        Ship the thing by Friday.
      </p>
      <p data-line-start="5" data-line-end="5">
        Second paragraph here.
      </p>
    </div>
  ),
}))

import { CommentableDocument } from './CommentableDocument'
import type { Comment } from './types'

function comment(over: Partial<Comment> & { id: string }): Comment {
  return {
    documentId: 'doc-1',
    kind: over.anchor ? 'QUOTE' : 'DOCUMENT',
    status: 'OPEN',
    authorId: 'u9',
    authorName: 'Sam',
    authorKind: 'USER',
    content: `comment ${over.id}`,
    createdAt: '2026-10-01T00:00:00Z',
    replies: [],
    ...over,
  }
}

const baseProps = {
  content: '# Goals\n\nShip the thing by Friday.\n\nSecond paragraph here.\n',
  target: { projectId: 'proj-1', workItemId: 'wi-1', documentId: 'doc-1' },
  token: 'tok',
  currentUserId: 'u1',
}

async function renderDoc(
  comments: Comment[] = [],
  props: Partial<React.ComponentProps<typeof CommentableDocument>> = {}
) {
  apiGet.mockImplementation(async (url: string) => (url.includes('/comments?') ? comments : []))
  const utils = render(<CommentableDocument {...baseProps} {...props} />)
  await waitFor(() => expect(apiGet).toHaveBeenCalled())
  await act(async () => {})
  return utils
}

function cardFor(id: string) {
  return document.querySelector(`[data-comment-id="${id}"]`) as HTMLElement | null
}

function selectInParagraph(text: string, index = 0) {
  const p = screen.getByTestId('markdown').querySelectorAll('p')[index]
  const node = p.firstChild as Text
  const start = node.data.indexOf(text)
  window.getSelection()!.setBaseAndExtent(node, start, node, start + text.length)
  fireEvent.mouseUp(p)
}

describe('CommentableDocument: mentions, agents, suggestions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })
  afterEach(() => {
    window.getSelection()?.removeAllRanges()
  })

  describe('Mine filter', () => {
    it('includes threads I wrote and threads that mention me (in the body or a reply), not others', async () => {
      await renderDoc([
        comment({ id: 'other', anchor: { exact: 'Ship the thing' } }),
        comment({
          id: 'mentioned',
          anchor: { exact: 'Second paragraph' },
          content: 'cc @[Ada](user:u1)',
          mentions: [{ kind: 'USER', id: 'u1', name: 'Ada' }],
        }),
        comment({
          id: 'replied',
          anchor: { exact: 'Goals' },
          replies: [
            {
              id: 'r1',
              authorId: 'u9',
              authorName: 'Sam',
              content: 'and @[Ada](user:u1)',
              mentions: [{ kind: 'USER', id: 'u1', name: 'Ada' }],
              createdAt: '2026-10-01T01:00:00Z',
            },
          ],
        }),
        comment({ id: 'mine', authorId: 'u1', authorName: 'Ada' }),
      ])
      expect(cardFor('other')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Mine' }))
      expect(cardFor('other')).toBeNull()
      expect(cardFor('mentioned')).toBeInTheDocument()
      expect(cardFor('replied')).toBeInTheDocument()
      expect(cardFor('mine')).toBeInTheDocument()
    })
  })

  describe('deep link', () => {
    it('focuses the thread once comments load, then hands the link back', async () => {
      const onFocusHandled = vi.fn()
      await renderDoc(
        [comment({ id: 'c1', anchor: { exact: 'Ship the thing' } }), comment({ id: 'c2', anchor: { exact: 'Second paragraph' } })],
        { focusCommentId: 'c2', onFocusHandled }
      )
      await waitFor(() => expect(cardFor('c2')).toHaveAttribute('data-active', 'true'))
      expect(cardFor('c1')).not.toHaveAttribute('data-active')
      await waitFor(() => expect(onFocusHandled).toHaveBeenCalledTimes(1))
    })

    it('focuses a whole-document thread', async () => {
      const onFocusHandled = vi.fn()
      await renderDoc([comment({ id: 'w1' })], { focusCommentId: 'w1', onFocusHandled })
      await waitFor(() => expect(cardFor('w1')).toHaveAttribute('data-active', 'true'))
      expect(onFocusHandled).toHaveBeenCalled()
    })

    it('shows a resolved thread by switching to Resolved', async () => {
      const onFocusHandled = vi.fn()
      await renderDoc([comment({ id: 'r1', anchor: { exact: 'Ship the thing' }, status: 'RESOLVED' })], {
        focusCommentId: 'r1',
        onFocusHandled,
      })
      await waitFor(() => expect(cardFor('r1')).toHaveAttribute('data-active', 'true'))
      expect(screen.getByRole('button', { name: 'Resolved' })).toHaveAttribute('aria-pressed', 'true')
      expect(onFocusHandled).toHaveBeenCalled()
    })

    it('lets go of a link to a comment that is gone', async () => {
      const onFocusHandled = vi.fn()
      await renderDoc([comment({ id: 'c1', anchor: { exact: 'Ship the thing' } })], {
        focusCommentId: 'nope',
        onFocusHandled,
      })
      await waitFor(() => expect(onFocusHandled).toHaveBeenCalled())
    })
  })

  describe('suggest edit', () => {
    async function openComposer() {
      selectInParagraph('the thing')
      fireEvent.click(await screen.findByRole('button', { name: 'Comment on selection' }))
      return screen.getByTestId('comment-popover')
    }

    it('offers the toggle on a selection comment and posts the suggestion', async () => {
      await renderDoc()
      apiPost.mockImplementation(async (url: string) =>
        url.endsWith('/suggestion-check')
          ? { suggestable: true }
          : comment({ id: 'new', anchor: { exact: 'the thing' }, suggestion: { text: 'that', status: 'PENDING' } })
      )
      const popover = await openComposer()
      fireEvent.click(within(popover).getByRole('button', { name: 'Suggest edit' }))
      const replacement = await within(popover).findByLabelText('Suggested text')
      fireEvent.change(replacement, { target: { value: 'that' } })
      fireEvent.click(within(popover).getByRole('button', { name: 'Comment' }))
      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith(
          '/api/v2/projects/proj-1/comments',
          expect.objectContaining({
            content: 'Suggested edit',
            suggestion: { text: 'that' },
            anchor: expect.objectContaining({ exact: 'the thing' }),
          }),
          'tok'
        )
      )
    })

    it('disables the toggle with the reason when the selection is not suggestable', async () => {
      await renderDoc()
      apiPost.mockResolvedValue({ suggestable: false, reason: 'This text appears more than once' })
      const popover = await openComposer()
      fireEvent.click(within(popover).getByRole('button', { name: 'Suggest edit' }))
      await waitFor(() => expect(within(popover).getByRole('button', { name: 'Suggest edit' })).toBeDisabled())
      expect(within(popover).getByText('This text appears more than once')).toBeInTheDocument()
    })

    it('is not offered while reviewing (drafts are plain comments)', async () => {
      await renderDoc([], { reviewMode: true })
      const popover = await openComposer()
      expect(within(popover).queryByRole('button', { name: 'Suggest edit' })).toBeNull()
    })

    it('is not offered on a reply or the whole-document composer', async () => {
      await renderDoc([comment({ id: 'c1', anchor: { exact: 'Ship the thing' } })])
      fireEvent.click(screen.getByRole('button', { name: 'Comment on whole document' }))
      expect(screen.queryByRole('button', { name: 'Suggest edit' })).toBeNull()
    })
  })

  describe('suggestion card', () => {
    const suggestion = comment({
      id: 's1',
      anchor: { exact: 'Ship the thing' },
      suggestion: { text: 'Launch the thing', status: 'PENDING' },
      content: 'Better verb',
    })

    it('accepting posts the decision and asks the page to reload the document', async () => {
      const onDocumentChanged = vi.fn()
      await renderDoc([suggestion], { onDocumentChanged })
      apiPost.mockResolvedValue({
        ...suggestion,
        status: 'RESOLVED',
        suggestion: { text: 'Launch the thing', status: 'ACCEPTED', decidedByName: 'Ada' },
      })
      fireEvent.click(screen.getByRole('button', { name: 'Accept' }))
      await waitFor(() =>
        expect(apiPost).toHaveBeenCalledWith(
          '/api/v2/projects/proj-1/comments/s1/suggestion-decision',
          { decision: 'ACCEPT' },
          'tok'
        )
      )
      await waitFor(() => expect(onDocumentChanged).toHaveBeenCalled())
    })

    it('hides Accept when the page says the user cannot edit', async () => {
      await renderDoc([suggestion], { canAcceptSuggestions: false })
      expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
      expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
    })
  })

  describe('@agent finishing', () => {
    it('asks the page to reload the document when a request that was running succeeds', async () => {
      const onDocumentChanged = vi.fn()
      const running = comment({
        id: 'c1',
        agentRequests: [{ agentId: 'a1', agentName: 'Scribe', status: 'RUNNING', createdAt: '2026-10-01T00:00:00Z' }],
      })
      await renderDoc([running], { onDocumentChanged })
      expect(screen.getByText('Scribe is working on it…')).toBeInTheDocument()
      apiGet.mockImplementation(async () => [
        {
          ...running,
          agentRequests: [{ ...running.agentRequests![0], status: 'SUCCEEDED' }],
        },
      ])
      await act(async () => {
        window.dispatchEvent(new Event('focus'))
      })
      await waitFor(() => expect(onDocumentChanged).toHaveBeenCalledTimes(1))
      expect(screen.queryByText('Scribe is working on it…')).toBeNull()
    })
  })
})
