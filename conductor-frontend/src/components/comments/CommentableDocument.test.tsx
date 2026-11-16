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
vi.mock('@/components/markdown/MarkdownRenderer', () => ({
  MarkdownRenderer: ({
    onToggleTask,
    tasksReadOnly,
  }: {
    onToggleTask?: (line: number, checked: boolean) => void
    tasksReadOnly?: boolean
  }) => (
    <div
      data-testid="markdown"
      data-has-toggle={onToggleTask ? 'yes' : 'no'}
      data-tasks-read-only={tasksReadOnly ? 'yes' : 'no'}
    >
      <h1 id="goals" data-line-start="1" data-line-end="1">
        Goals
      </h1>
      <p data-line-start="3" data-line-end="3">
        Ship the thing by Friday.
      </p>
      <p data-line-start="5" data-line-end="5">
        Second paragraph here.
      </p>
      <button data-testid="toggle-task" onClick={() => onToggleTask?.(2, true)}>
        toggle
      </button>
    </div>
  ),
}))

import { CommentableDocument } from './CommentableDocument'
import type { Comment, PendingCommentDraft } from './types'

const CONTENT = '# Goals\n\nShip the thing by Friday.\n\nSecond paragraph here.\n'

function comment(over: Partial<Comment> & { id: string }): Comment {
  return {
    documentId: 'doc-1',
    kind: over.anchor ? 'QUOTE' : over.lineNumber ? 'LINE' : 'DOCUMENT',
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
  content: CONTENT,
  target: { projectId: 'proj-1', workItemId: 'wi-1', documentId: 'doc-1' },
  token: 'tok',
  currentUserId: 'u1',
}

async function renderDoc(
  comments: Comment[] = [],
  props: Partial<React.ComponentProps<typeof CommentableDocument>> = {}
) {
  apiGet.mockResolvedValue(comments)
  const utils = render(<CommentableDocument {...baseProps} {...props} />)
  await waitFor(() => expect(apiGet).toHaveBeenCalled())
  await act(async () => {})
  return utils
}

/** Selects `text` inside the second paragraph and lets the viewer see the mouse come up. */
function selectInParagraph(text: string, index = 0) {
  const p = screen.getByTestId('markdown').querySelectorAll('p')[index]
  const node = p.firstChild as Text
  const start = node.data.indexOf(text)
  window.getSelection()!.setBaseAndExtent(node, start, node, start + text.length)
  fireEvent.mouseUp(p)
}

function cardFor(id: string) {
  return document.querySelector(`[data-comment-id="${id}"]`) as HTMLElement | null
}

describe('CommentableDocument', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiPost.mockResolvedValue({ ...comment({ id: 'new', anchor: { exact: 'the thing' } }) })
  })
  afterEach(() => {
    window.getSelection()?.removeAllRanges()
    delete (document as unknown as { caretRangeFromPoint?: unknown }).caretRangeFromPoint
    vi.unstubAllGlobals()
  })

  it('has no gutter and no popover until text is selected', async () => {
    await renderDoc()
    expect(screen.queryByLabelText('comment gutter')).not.toBeInTheDocument()
    expect(screen.queryByTestId('comment-popover')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Comment on selection' })).not.toBeInTheDocument()
  })

  it('loads the thread list from the unified comments resource', async () => {
    await renderDoc()
    expect(apiGet).toHaveBeenCalledWith(
      expect.stringContaining('/api/v2/projects/proj-1/comments?workItemId=wi-1&documentId=doc-1'),
      'tok'
    )
  })

  it('selecting text offers a Comment button, which opens a composer showing the quote', async () => {
    await renderDoc()
    selectInParagraph('the thing')
    fireEvent.click(await screen.findByRole('button', { name: 'Comment on selection' }))
    const popover = screen.getByTestId('comment-popover')
    expect(within(popover).getByText('the thing')).toBeInTheDocument()
    expect(within(popover).getByRole('textbox')).toBeInTheDocument()
  })

  it('posts a quote comment with its anchor and the document hash', async () => {
    await renderDoc([], { contentHash: 'hash-v1' })
    selectInParagraph('the thing')
    fireEvent.click(await screen.findByRole('button', { name: 'Comment on selection' }))
    const popover = screen.getByTestId('comment-popover')
    fireEvent.change(within(popover).getByRole('textbox'), { target: { value: 'Which Friday?' } })
    fireEvent.click(within(popover).getByRole('button', { name: 'Comment' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    const [url, body] = apiPost.mock.calls[0]
    expect(url).toBe('/api/v2/projects/proj-1/comments')
    expect(body).toMatchObject({
      target: { workItemId: 'wi-1', documentId: 'doc-1' },
      content: 'Which Friday?',
      anchor: { exact: 'the thing', prefix: 'Goals Ship ', suffix: ' by Friday. Second paragraph her' },
      documentHash: 'hash-v1',
    })
    expect(body).not.toHaveProperty('lineNumber')
    // The popover is gone and the new thread is focused.
    await waitFor(() => expect(screen.queryByTestId('comment-popover')).not.toBeInTheDocument())
    await waitFor(() => expect(cardFor('new')).toHaveAttribute('data-active', 'true'))
  })

  it('omits the hash when the document has none', async () => {
    await renderDoc([], { contentHash: null })
    selectInParagraph('the thing')
    fireEvent.click(await screen.findByRole('button', { name: 'Comment on selection' }))
    const popover = screen.getByTestId('comment-popover')
    fireEvent.change(within(popover).getByRole('textbox'), { target: { value: 'hi' } })
    fireEvent.click(within(popover).getByRole('button', { name: 'Comment' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    expect(apiPost.mock.calls[0][1]).not.toHaveProperty('documentHash')
  })

  it('posts to the project-doc target for a project doc', async () => {
    await renderDoc([], { target: { projectId: 'proj-1', projectDocId: 'pd-1' } })
    expect(apiGet).toHaveBeenCalledWith(expect.stringContaining('projectDocId=pd-1'), 'tok')
    selectInParagraph('the thing')
    fireEvent.click(await screen.findByRole('button', { name: 'Comment on selection' }))
    const popover = screen.getByTestId('comment-popover')
    fireEvent.change(within(popover).getByRole('textbox'), { target: { value: 'hi' } })
    fireEvent.click(within(popover).getByRole('button', { name: 'Comment' }))
    await waitFor(() => expect(apiPost).toHaveBeenCalled())
    expect(apiPost.mock.calls[0][1]).toMatchObject({ target: { projectDocId: 'pd-1' } })
  })

  it('lists a thread whose text is in the document, and focuses it when its highlight is clicked', async () => {
    await renderDoc([
      comment({ id: 'c1', anchor: { exact: 'Ship the thing' } }),
      comment({ id: 'c2', anchor: { exact: 'Second paragraph' } }),
    ])
    expect(cardFor('c1')).toBeInTheDocument()
    expect(cardFor('c1')).not.toHaveAttribute('data-active')

    const p = screen.getByTestId('markdown').querySelectorAll('p')[1]
    const node = p.firstChild as Text
    ;(document as unknown as { caretRangeFromPoint: unknown }).caretRangeFromPoint = () => {
      const r = document.createRange()
      r.setStart(node, 4)
      r.collapse(true)
      return r
    }
    fireEvent.click(p)
    expect(cardFor('c2')).toHaveAttribute('data-active', 'true')
    expect(cardFor('c1')).not.toHaveAttribute('data-active')
  })

  it('clicking a card scrolls to and flashes its highlight', async () => {
    const highlights = new Map<string, unknown>()
    vi.stubGlobal(
      'Highlight',
      class {
        ranges: Range[]
        constructor(...r: Range[]) {
          this.ranges = r
        }
      }
    )
    vi.stubGlobal('CSS', { highlights: { set: (k: string, v: unknown) => highlights.set(k, v), delete: (k: string) => highlights.delete(k) } })
    await renderDoc([comment({ id: 'c1', anchor: { exact: 'Ship the thing' } })])
    expect(highlights.has('conductor-comment')).toBe(true)
    expect(highlights.has('conductor-comment-flash')).toBe(false)

    fireEvent.click(within(cardFor('c1')!).getByText('comment c1'))
    expect(cardFor('c1')).toHaveAttribute('data-active', 'true')
    expect(highlights.has('conductor-comment-active')).toBe(true)
    expect(highlights.has('conductor-comment-flash')).toBe(true)
  })

  it('j and k step through the threads in reading order', async () => {
    await renderDoc([
      comment({ id: 'c1', anchor: { exact: 'Ship the thing' } }),
      comment({ id: 'c2', anchor: { exact: 'Second paragraph' } }),
    ])
    fireEvent.keyDown(document, { key: 'j' })
    expect(cardFor('c1')).toHaveAttribute('data-active', 'true')
    fireEvent.keyDown(document, { key: 'j' })
    expect(cardFor('c2')).toHaveAttribute('data-active', 'true')
    fireEvent.keyDown(document, { key: 'k' })
    expect(cardFor('c1')).toHaveAttribute('data-active', 'true')
  })

  it('puts a thread whose text is gone under Whole document, with its original quote', async () => {
    await renderDoc([comment({ id: 'c1', anchor: { exact: 'text that was deleted' } })])
    const section = screen.getByRole('region', { name: 'Whole document' })
    expect(within(section).getByText('comment c1')).toBeInTheDocument()
    expect(within(section).getByText(/text that was deleted/)).toBeInTheDocument()
  })

  it('puts a whole-document comment under Whole document', async () => {
    await renderDoc([comment({ id: 'd1' })])
    expect(within(screen.getByRole('region', { name: 'Whole document' })).getByText('comment d1')).toBeInTheDocument()
  })

  it('places an older line comment on the block showing its line', async () => {
    await renderDoc([comment({ id: 'l1', lineNumber: 3 })])
    const section = screen.getByRole('region', { name: 'Whole document' })
    expect(within(section).queryByText('comment l1')).not.toBeInTheDocument()
    expect(cardFor('l1')).toBeInTheDocument()
  })

  it('puts an older line comment on a line past the end under Whole document with a note', async () => {
    await renderDoc([comment({ id: 'l1', lineNumber: 99 })])
    const section = screen.getByRole('region', { name: 'Whole document' })
    expect(within(section).getByText('comment l1')).toBeInTheDocument()
    expect(within(section).getByText(/Was on line 99/)).toBeInTheDocument()
  })

  it('forwards task-toggle props to the renderer', async () => {
    const onToggleTask = vi.fn()
    await renderDoc([], { onToggleTask, tasksReadOnly: true })
    const markdown = screen.getByTestId('markdown')
    expect(markdown).toHaveAttribute('data-has-toggle', 'yes')
    expect(markdown).toHaveAttribute('data-tasks-read-only', 'yes')
    fireEvent.click(screen.getByTestId('toggle-task'))
    expect(onToggleTask).toHaveBeenCalledWith(2, true)
  })

  it('omits the toggle handler when the caller does not supply one', async () => {
    await renderDoc()
    expect(screen.getByTestId('markdown')).toHaveAttribute('data-has-toggle', 'no')
  })

  describe('review mode', () => {
    it('holds a selection comment as a draft instead of posting it', async () => {
      const onAddPendingComment = vi.fn()
      await renderDoc([], { reviewMode: true, onAddPendingComment, contentHash: 'hash-v1' })
      selectInParagraph('the thing')
      fireEvent.click(await screen.findByRole('button', { name: 'Comment on selection' }))
      const popover = screen.getByTestId('comment-popover')
      fireEvent.change(within(popover).getByRole('textbox'), { target: { value: 'draft text' } })
      fireEvent.click(within(popover).getByRole('button', { name: 'Comment' }))

      await waitFor(() => expect(onAddPendingComment).toHaveBeenCalled())
      expect(onAddPendingComment).toHaveBeenCalledWith(
        expect.objectContaining({ exact: 'the thing', prefix: 'Goals Ship ' }),
        'draft text',
        'hash-v1'
      )
      expect(apiPost).not.toHaveBeenCalled()
    })

    it('does not offer a whole-document comment while reviewing', async () => {
      await renderDoc([], { reviewMode: true })
      expect(screen.queryByRole('button', { name: /Comment on whole document/ })).not.toBeInTheDocument()
    })

    it('shows quote drafts and older line drafts as Pending cards, for this document only', async () => {
      const drafts: PendingCommentDraft[] = [
        { localId: 'p1', documentId: 'doc-1', anchor: { exact: 'Ship the thing' }, content: 'quote draft' },
        { localId: 'p2', documentId: 'doc-1', lineNumber: 5, content: 'line draft' },
        { localId: 'p3', documentId: 'other-doc', anchor: { exact: 'Ship the thing' }, content: 'elsewhere' },
      ]
      await renderDoc([], { reviewMode: true, pendingComments: drafts })
      expect(within(cardFor('draft:p1')!).getByText('quote draft')).toBeInTheDocument()
      expect(within(cardFor('draft:p1')!).getByText('Pending')).toBeInTheDocument()
      expect(within(cardFor('draft:p2')!).getByText('line draft')).toBeInTheDocument()
      expect(screen.queryByText('elsewhere')).not.toBeInTheDocument()
    })

    it('edits and removes a draft through the callbacks', async () => {
      const onRemovePendingComment = vi.fn()
      const drafts: PendingCommentDraft[] = [
        { localId: 'p1', documentId: 'doc-1', anchor: { exact: 'Ship the thing' }, content: 'quote draft' },
      ]
      await renderDoc([], { reviewMode: true, pendingComments: drafts, onRemovePendingComment })
      fireEvent.pointerDown(within(cardFor('draft:p1')!).getByRole('button', { name: 'More actions' }), {
        button: 0,
        ctrlKey: false,
      })
      fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
      expect(onRemovePendingComment).toHaveBeenCalledWith('p1')
    })

    it('lists a draft whose text is gone under Whole document', async () => {
      const drafts: PendingCommentDraft[] = [
        { localId: 'p1', documentId: 'doc-1', anchor: { exact: 'vanished words' }, content: 'orphan draft' },
      ]
      await renderDoc([], { reviewMode: true, pendingComments: drafts })
      const section = screen.getByRole('region', { name: 'Whole document' })
      expect(within(section).getByText('orphan draft')).toBeInTheDocument()
    })
  })
})
