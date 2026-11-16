import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { CommentCard } from './CommentCard'
import { CommentsFeaturesProvider } from './CommentsFeatures'
import type { Comment } from './types'

vi.mock('@/lib/api', () => ({ apiGet: vi.fn(), apiPost: vi.fn(), apiErrorMessage: (_e: unknown, f: string) => f }))

function comment(over: Partial<Comment> = {}): Comment {
  return {
    id: 'c1',
    documentId: 'doc-1',
    kind: 'QUOTE',
    status: 'OPEN',
    authorId: 'u1',
    authorName: 'Ada',
    authorKind: 'USER',
    content: 'Whose 9am?',
    anchor: { exact: 'up to ten posts' },
    createdAt: '2026-10-01T00:00:00Z',
    replies: [],
    ...over,
  }
}

const noop = vi.fn()
function renderCard(c: Comment, decide = vi.fn().mockResolvedValue(true), canAccept = true) {
  render(
    <CommentsFeaturesProvider
      target={{ projectId: 'p', workItemId: 'w', documentId: 'doc-1' }}
      token="t"
      canAcceptSuggestions={canAccept}
      decideSuggestion={decide}
    >
      <CommentCard
        comment={c}
        currentUserId="u2"
        onReply={noop}
        onEdit={noop}
        onEditReply={noop}
        onResolve={noop}
        onReopen={noop}
        onDelete={noop}
        onDeleteReply={noop}
      />
    </CommentsFeaturesProvider>
  )
  return decide
}

describe('mention chips in a card', () => {
  it('renders thread and reply mentions as chips, never raw tokens', () => {
    renderCard(
      comment({
        content: 'cc @[Ada](user:u1)',
        mentions: [{ kind: 'USER', id: 'u1', name: 'Ada' }],
        replies: [
          {
            id: 'r1',
            authorId: 'a',
            authorName: 'Scribe',
            authorKind: 'AGENT',
            authorAgent: { id: 'a1', name: 'Scribe', avatar: { emoji: '✍️', color: 'teal' } },
            content: 'Done, @[Ada](user:u1)',
            mentions: [{ kind: 'USER', id: 'u1', name: 'Ada' }],
            createdAt: '2026-10-01T01:00:00Z',
          },
        ],
      })
    )
    expect(document.querySelectorAll('[data-mention="user"]')).toHaveLength(2)
    expect(document.body.textContent).not.toContain('user:u1')
  })
})

describe('@agent status', () => {
  const base = { agentId: 'a1', agentName: 'Scribe', createdAt: '2026-10-01T00:00:00Z' }

  it('shows "is working on it" for pending and running requests', () => {
    renderCard(
      comment({
        agentRequests: [
          { ...base, status: 'PENDING' },
          { ...base, agentId: 'a2', agentName: 'Editor', status: 'RUNNING' },
        ],
      })
    )
    expect(screen.getByText('Scribe is working on it…')).toBeInTheDocument()
    expect(screen.getByText('Editor is working on it…')).toBeInTheDocument()
    expect(document.querySelectorAll('[data-agent-status="working"]')).toHaveLength(2)
    // The pulse is motion-safe only.
    expect(document.querySelector('.motion-safe\\:animate-ping')).not.toBeNull()
  })

  it('shows a failure in plain words and nothing else', () => {
    renderCard(comment({ agentRequests: [{ ...base, status: 'FAILED', error: 'Scribe ran out of time.' }] }))
    expect(screen.getByText('Scribe ran out of time.')).toBeInTheDocument()
    expect(screen.queryByText(/working on it/)).toBeNull()
  })

  it('shows nothing extra once it succeeded', () => {
    renderCard(comment({ agentRequests: [{ ...base, status: 'SUCCEEDED' }] }))
    expect(document.querySelector('[data-agent-status]')).toBeNull()
  })
})

describe('suggested edit card', () => {
  const pending = { text: 'the top five posts', status: 'PENDING' as const }

  it('shows the tag, old text struck out, new text inserted and the note', () => {
    renderCard(comment({ suggestion: pending, content: 'Matches the spec' }))
    expect(screen.getByText('Suggestion')).toBeInTheDocument()
    expect(document.querySelector('del')).toHaveTextContent('up to ten posts')
    expect(document.querySelector('ins')).toHaveTextContent('the top five posts')
    expect(screen.getByText('Matches the spec')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Accept' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('accepts and rejects through the decision call', async () => {
    const decide = renderCard(comment({ suggestion: pending }))
    fireEvent.click(screen.getByRole('button', { name: 'Accept' }))
    await waitFor(() => expect(decide).toHaveBeenCalledWith('c1', 'ACCEPT'))
    fireEvent.click(screen.getByRole('button', { name: 'Reject' }))
    await waitFor(() => expect(decide).toHaveBeenCalledWith('c1', 'REJECT'))
  })

  it('hides Accept (but not Reject) when the user cannot edit', () => {
    renderCard(comment({ suggestion: pending }), vi.fn(), false)
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Reject' })).toBeInTheDocument()
  })

  it('shows the outcome once decided, with no buttons', () => {
    const { unmount } = render(<div />)
    unmount()
    renderCard(
      comment({
        status: 'RESOLVED',
        suggestion: { ...pending, status: 'ACCEPTED', decidedByName: 'Sam' },
      })
    )
    expect(screen.getByText('Accepted by Sam · thread closed')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Accept' })).toBeNull()
    expect(screen.queryByRole('button', { name: 'Reject' })).toBeNull()
  })

  it('shows Rejected by', () => {
    renderCard(
      comment({
        status: 'RESOLVED',
        suggestion: { ...pending, status: 'REJECTED', decidedByName: 'Lee' },
      })
    )
    expect(screen.getByText('Rejected by Lee')).toBeInTheDocument()
  })

  it('shows a deletion as struck-out text only', () => {
    renderCard(comment({ suggestion: { text: '', status: 'PENDING' } }))
    expect(document.querySelector('del')).toBeInTheDocument()
    expect(document.querySelector('ins')).toBeNull()
  })
})
