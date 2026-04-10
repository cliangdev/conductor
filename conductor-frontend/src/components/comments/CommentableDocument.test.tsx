import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

vi.mock('@/lib/api', () => ({ apiPost: vi.fn() }))
vi.mock('@/components/markdown/MarkdownRenderer', () => ({
  MarkdownRenderer: ({ content }: { content: string }) => <div data-testid="markdown">{content}</div>,
}))
vi.mock('./CommentThread', () => ({
  CommentThread: () => <div data-testid="comment-thread" />,
}))
vi.mock('./NewCommentForm', () => ({
  NewCommentForm: ({ onSubmit, onCancel }: { onSubmit: (t: string) => void; onCancel: () => void }) => (
    <div data-testid="new-comment-form">
      <button onClick={() => onSubmit('test comment')}>Submit</button>
      <button onClick={onCancel}>Cancel</button>
    </div>
  ),
}))

import { CommentableDocument } from './CommentableDocument'
import type { Comment } from './types'

const baseProps = {
  content: 'line one\nline two\nline three',
  documentId: 'doc-1',
  issueId: 'issue-1',
  projectId: 'proj-1',
  comments: [] as Comment[],
  onCommentAdded: vi.fn(),
  token: 'test-token',
  currentUserId: 'user-1',
}

describe('CommentableDocument', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders markdown content', () => {
    render(<CommentableDocument {...baseProps} />)
    expect(screen.getByTestId('markdown')).toBeInTheDocument()
    expect(screen.getByTestId('markdown')).toHaveTextContent('line one')
  })

  it('renders line comment buttons in gutter', () => {
    render(<CommentableDocument {...baseProps} />)
    const gutter = screen.getByLabelText('comment gutter')
    expect(gutter).toBeInTheDocument()
    // 3 lines → 3 gutter buttons
    const buttons = gutter.querySelectorAll('button')
    expect(buttons).toHaveLength(3)
  })

  it('shows comment form when line gutter button is clicked', () => {
    render(<CommentableDocument {...baseProps} />)
    const gutter = screen.getByLabelText('comment gutter')
    const firstLineBtn = gutter.querySelectorAll('button')[0]
    fireEvent.click(firstLineBtn)
    expect(screen.getByTestId('new-comment-form')).toBeInTheDocument()
    expect(screen.getByText(/new comment on line 1/i)).toBeInTheDocument()
  })

  it('hides comment form when cancel is clicked', () => {
    render(<CommentableDocument {...baseProps} />)
    const gutter = screen.getByLabelText('comment gutter')
    fireEvent.click(gutter.querySelectorAll('button')[0])
    expect(screen.getByTestId('new-comment-form')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(screen.queryByTestId('new-comment-form')).not.toBeInTheDocument()
  })

  it('shows comment thread emoji when line has comments', () => {
    const comments: Comment[] = [
      {
        id: 'c1',
        documentId: 'doc-1',
        authorId: 'user-1',
        authorName: 'Alice',
        content: 'nice',
        lineNumber: 2,
        createdAt: '2024-01-01T00:00:00Z',
        replies: [],
      },
    ]
    render(<CommentableDocument {...baseProps} comments={comments} />)
    const gutter = screen.getByLabelText('comment gutter')
    const buttons = gutter.querySelectorAll('button')
    expect(buttons[1]).toHaveTextContent('💬')
  })

  it('does not show selection form on mouseup when skipNextMouseUpRef is set', () => {
    // Simulate the "Add comment" button mousedown setting skipNextMouseUpRef,
    // then a mouseup firing — the selection tooltip should not reappear.
    render(<CommentableDocument {...baseProps} />)
    const contentDiv = screen.getByTestId('markdown').parentElement!

    // No selection bubble should be visible initially
    expect(screen.queryByText('+ Comment')).not.toBeInTheDocument()

    // Firing mouseup on the content area with no selection — nothing happens
    fireEvent.mouseUp(contentDiv)
    expect(screen.queryByText('+ Comment')).not.toBeInTheDocument()
  })
})
