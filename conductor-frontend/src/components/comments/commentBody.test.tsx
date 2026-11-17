import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/react'
import {
  displayToTokens,
  hasAgentMention,
  involvesUser,
  makeToken,
  plainCommentText,
  renderCommentBody,
  tokensToDisplay,
} from './commentBody'
import type { Comment } from './types'

function thread(over: Partial<Comment> = {}): Comment {
  return {
    id: 'c1',
    documentId: 'd1',
    authorId: 'u9',
    authorName: 'Sam',
    authorKind: 'USER',
    content: 'hello',
    createdAt: '2026-10-01T00:00:00Z',
    replies: [],
    ...over,
  }
}

describe('mention tokens', () => {
  it('shows tokens as @Name and puts them back (edit round-trip)', () => {
    const stored = 'Ask @[Ada Lovelace](user:u1) and @[Scribe](agent:a1) to look.\nThanks'
    const { text, map } = tokensToDisplay(stored)
    expect(text).toBe('Ask @Ada Lovelace and @Scribe to look.\nThanks')
    expect(displayToTokens(text, map)).toBe(stored)
  })

  it('only converts names still in the text, and not half-typed ones', () => {
    const { map } = tokensToDisplay('@[Ada](user:u1)')
    expect(displayToTokens('no mention', map)).toBe('no mention')
    expect(displayToTokens('@Adam is not Ada', map)).toBe('@Adam is not Ada')
    expect(displayToTokens('hi @Ada!', map)).toBe('hi @[Ada](user:u1)!')
  })

  it('makes a token and detects an agent mention', () => {
    expect(makeToken('Ada', 'user', 'u1').token).toBe('@[Ada](user:u1)')
    expect(hasAgentMention('x @[Scribe](agent:a1)')).toBe(true)
    expect(hasAgentMention('x @[Ada](user:u1)')).toBe(false)
  })

  it('reduces tokens to names for plain text', () => {
    expect(plainCommentText('see @[Ada](user:u1)')).toBe('see @Ada')
  })
})

describe('renderCommentBody', () => {
  it('renders a chip with the name only, never the raw token, and keeps line breaks', () => {
    const { container } = render(
      <p style={{ whiteSpace: 'pre-wrap' }}>
        {renderCommentBody('Hi @[Ada](user:u1),\nplease fix @[Scribe](agent:a1)', [
          { kind: 'USER', id: 'u1', name: 'Ada L.' },
        ])}
      </p>
    )
    const chips = container.querySelectorAll('[data-mention]')
    expect(chips).toHaveLength(2)
    expect(chips[0]).toHaveTextContent('@Ada L.')
    expect(chips[0].getAttribute('data-mention')).toBe('user')
    expect(chips[1]).toHaveTextContent('@Scribe')
    expect(chips[1].getAttribute('data-mention')).toBe('agent')
    expect(container.textContent).not.toContain('user:u1')
    expect(container.textContent).toContain('\nplease fix')
  })

  it('returns plain text unchanged', () => {
    const { container } = render(<p>{renderCommentBody('just words')}</p>)
    expect(container.textContent).toBe('just words')
    expect(container.querySelector('[data-mention]')).toBeNull()
  })
})

describe('involvesUser (the Mine filter)', () => {
  it('includes threads I wrote', () => {
    expect(involvesUser(thread({ authorId: 'me' }), 'me')).toBe(true)
  })
  it('includes threads that mention me, in the body or a reply', () => {
    expect(involvesUser(thread({ mentions: [{ kind: 'USER', id: 'me', name: 'Me' }] }), 'me')).toBe(true)
    expect(
      involvesUser(
        thread({
          replies: [
            {
              id: 'r1',
              authorId: 'u2',
              authorName: 'Lee',
              content: 'cc @[Me](user:me)',
              mentions: [{ kind: 'USER', id: 'me', name: 'Me' }],
              createdAt: '2026-10-01T00:00:00Z',
            },
          ],
        }),
        'me'
      )
    ).toBe(true)
  })
  it('excludes threads about someone else, and an agent mention with my id', () => {
    expect(involvesUser(thread(), 'me')).toBe(false)
    expect(involvesUser(thread({ mentions: [{ kind: 'AGENT', id: 'me', name: 'A' }], content: 'x' }), 'me')).toBe(false)
    expect(involvesUser(thread({ authorKind: 'AGENT', authorId: 'me' }), 'me')).toBe(false)
  })
})
