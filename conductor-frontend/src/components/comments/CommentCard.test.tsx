import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react'
import { CommentCard, isLongBody, type CommentCardProps } from './CommentCard'
import type { Comment, CommentReply } from './types'

function reply(id: string, over: Partial<CommentReply> = {}): CommentReply {
  return {
    id,
    authorId: 'u2',
    authorName: 'Sam',
    authorKind: 'USER',
    content: `reply ${id}`,
    createdAt: '2026-10-01T01:00:00Z',
    ...over,
  }
}

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
    createdAt: '2026-10-01T00:00:00Z',
    replies: [],
    ...over,
  }
}

function setup(c: Comment, props: Partial<CommentCardProps> = {}) {
  const handlers = {
    onReply: vi.fn().mockResolvedValue(undefined),
    onEdit: vi.fn().mockResolvedValue(undefined),
    onEditReply: vi.fn().mockResolvedValue(undefined),
    onResolve: vi.fn(),
    onReopen: vi.fn(),
    onDelete: vi.fn(),
    onDeleteReply: vi.fn(),
    onActivate: vi.fn(),
  }
  render(<CommentCard comment={c} currentUserId="u1" {...handlers} {...props} />)
  return handlers
}

async function openMenu(card: HTMLElement) {
  fireEvent.pointerDown(within(card).getAllByRole('button', { name: 'More actions' })[0], { button: 0, ctrlKey: false })
}

const LONG = Array.from({ length: 9 }, (_, i) => `line ${i + 1}`).join('\n')

describe('isLongBody', () => {
  it('counts line breaks and wrapping against six lines', () => {
    expect(isLongBody('short')).toBe(false)
    expect(isLongBody(Array(6).fill('x').join('\n'))).toBe(false)
    expect(isLongBody(Array(7).fill('x').join('\n'))).toBe(true)
    expect(isLongBody('word '.repeat(60))).toBe(true)
    expect(isLongBody('word '.repeat(40))).toBe(false)
  })
})

describe('CommentCard', () => {
  it('folds a long body behind Show more and unfolds it', () => {
    setup(comment({ content: LONG }))
    const body = screen.getByText(/line 1/)
    expect(body.className).toContain('line-clamp-6')
    fireEvent.click(screen.getByRole('button', { name: 'Show more' }))
    expect(body.className).not.toContain('line-clamp-6')
    fireEvent.click(screen.getByRole('button', { name: 'Show less' }))
    expect(body.className).toContain('line-clamp-6')
  })

  it('does not fold a short body', () => {
    setup(comment())
    expect(screen.queryByRole('button', { name: 'Show more' })).not.toBeInTheDocument()
  })

  it('keeps line breaks and renders text as plain text', () => {
    setup(comment({ content: 'a\nb <b>not bold</b>' }))
    const p = screen.getByText(/not bold/)
    expect(p.className).toContain('whitespace-pre-wrap')
    expect(p.querySelector('b')).toBeNull()
  })

  it('shows two replies in full', () => {
    setup(comment({ replies: [reply('r1'), reply('r2')] }))
    expect(screen.getByText('reply r1')).toBeInTheDocument()
    expect(screen.getByText('reply r2')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Show \d+ repl/ })).not.toBeInTheDocument()
  })

  it('folds more than two replies down to the newest, behind "Show N replies"', () => {
    setup(comment({ replies: [reply('r1'), reply('r2'), reply('r3'), reply('r4')] }))
    expect(screen.queryByText('reply r1')).not.toBeInTheDocument()
    expect(screen.queryByText('reply r3')).not.toBeInTheDocument()
    expect(screen.getByText('reply r4')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show 3 replies' }))
    for (const id of ['r1', 'r2', 'r3', 'r4']) expect(screen.getByText(`reply ${id}`)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Show \d+ repl/ })).not.toBeInTheDocument()
  })

  it('shows the edited marker and Resolved by', () => {
    setup(comment({ editedAt: '2026-10-01T00:05:00Z', status: 'RESOLVED', resolvedAt: '2026-10-02T00:00:00Z', resolvedByName: 'Sam' }))
    expect(screen.getByText('edited')).toBeInTheDocument()
    expect(screen.getByText('Resolved by Sam')).toBeInTheDocument()
  })

  it('shows an agent with its own avatar, and a neutral one when the agent is unknown', () => {
    const { unmount } = render(
      <CommentCard
        comment={comment({
          authorId: null,
          authorName: 'CEO',
          authorKind: 'AGENT',
          authorAgent: { id: 'a1', name: 'CEO', avatar: { emoji: '🧭', color: 'amber' } },
        })}
        currentUserId="u1"
        onReply={vi.fn()}
        onEdit={vi.fn()}
        onEditReply={vi.fn()}
        onResolve={vi.fn()}
        onReopen={vi.fn()}
        onDelete={vi.fn()}
        onDeleteReply={vi.fn()}
      />
    )
    expect(screen.getByText('🧭')).toBeInTheDocument()
    expect(screen.getByText('Agent')).toBeInTheDocument()
    unmount()
    setup(comment({ authorId: null, authorName: 'Agent (run a1b2)', authorKind: 'AGENT', authorAgent: null }))
    expect(screen.queryByText('🧭')).not.toBeInTheDocument()
    expect(screen.getByText('Agent (run a1b2)')).toBeInTheDocument()
  })

  it('gives someone else’s comment no menu at all (unless you are an admin)', () => {
    setup(comment({ authorId: 'u2', authorName: 'Sam' }))
    expect(screen.queryByRole('button', { name: 'More actions' })).not.toBeInTheDocument()
  })

  it('edits the author’s own comment in place', async () => {
    const h = setup(comment())
    const card = document.querySelector('[data-comment-id="c1"]') as HTMLElement
    await openMenu(card)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Edit' }))
    const box = screen.getByRole('textbox', { name: 'Edit comment' })
    expect(box).toHaveFocus()
    expect(box).toHaveValue('Whose 9am?')
    fireEvent.change(box, { target: { value: 'Whose 9am, exactly?' } })
    fireEvent.keyDown(box, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(h.onEdit).toHaveBeenCalledWith('c1', 'Whose 9am, exactly?'))
  })

  it('deletes through a confirm modal, for the author or an admin', async () => {
    const h = setup(comment({ authorId: 'u2', authorName: 'Sam' }), { isAdmin: true })
    const card = document.querySelector('[data-comment-id="c1"]') as HTMLElement
    await openMenu(card)
    // An admin may delete but not edit someone else's comment.
    expect(screen.queryByRole('menuitem', { name: 'Edit' })).not.toBeInTheDocument()
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
    expect(h.onDelete).not.toHaveBeenCalled()
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(h.onDelete).toHaveBeenCalledWith('c1')
  })

  it('offers Reopen on a resolved thread', async () => {
    const h = setup(comment({ status: 'RESOLVED', resolvedAt: '2026-10-02T00:00:00Z' }))
    const card = document.querySelector('[data-comment-id="c1"]') as HTMLElement
    await openMenu(card)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Reopen' }))
    expect(h.onReopen).toHaveBeenCalledWith('c1')
  })

  it('replies from the focused card with Cmd+Enter, and resolves it', async () => {
    const h = setup(comment(), { active: true })
    const box = screen.getByRole('textbox', { name: 'Reply' })
    // The reply box sits in the card without taking focus away from the keyboard navigation.
    expect(box).not.toHaveFocus()
    fireEvent.change(box, { target: { value: 'Workspace.' } })
    fireEvent.keyDown(box, { key: 'Enter', metaKey: true })
    await waitFor(() => expect(h.onReply).toHaveBeenCalledWith('c1', 'Workspace.'))
    fireEvent.click(screen.getByRole('button', { name: 'Resolve' }))
    expect(h.onResolve).toHaveBeenCalledWith('c1')
  })

  it('has no reply box until it is focused, and focuses on click', () => {
    const h = setup(comment())
    expect(screen.queryByRole('textbox', { name: 'Reply' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByText('Whose 9am?'))
    expect(h.onActivate).toHaveBeenCalledWith('c1')
  })

  it('shows the note above the body', () => {
    setup(comment(), { note: 'Was on “x”' })
    expect(screen.getByText('Was on “x”')).toBeInTheDocument()
  })

  it('lets the author edit and delete a reply of their own, and not someone else’s', async () => {
    const h = setup(comment({ authorId: 'u2', authorName: 'Sam', replies: [reply('mine', { authorId: 'u1' }), reply('other')] }))
    const mineRow = document.querySelector('[data-reply-id="mine"]') as HTMLElement
    const otherRow = document.querySelector('[data-reply-id="other"]') as HTMLElement
    expect(within(otherRow).queryByRole('button', { name: 'More actions' })).not.toBeInTheDocument()

    await openMenu(mineRow)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Edit' }))
    const box = screen.getByRole('textbox', { name: 'Edit reply' })
    fireEvent.change(box, { target: { value: 'changed' } })
    fireEvent.keyDown(box, { key: 'Enter', metaKey: true })
    await waitFor(() => expect(h.onEditReply).toHaveBeenCalledWith('c1', 'mine', 'changed'))

    await openMenu(mineRow)
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Delete' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(h.onDeleteReply).toHaveBeenCalledWith('c1', 'mine')
  })
})
