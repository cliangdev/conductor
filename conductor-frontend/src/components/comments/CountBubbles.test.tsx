import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { CountBubbles, BUBBLE_SIZE, BUBBLE_GAP } from './CountBubbles'
import { selectionButtonTop } from './selectionPlacement'

const y = (id: string) => (document.querySelector(`[data-bubble-id="${id}"]`) as HTMLElement).style.transform

describe('CountBubbles', () => {
  it('lines each bubble up with its highlight, shifted by the scroll offset', () => {
    render(
      <CountBubbles
        items={[
          { id: 'a', top: 120, count: 1 },
          { id: 'b', top: 500, count: 3 },
        ]}
        activeId={null}
        offset={100}
        onSelect={vi.fn()}
      />
    )
    expect(y('a')).toBe('translate3d(0, 20px, 0)')
    expect(y('b')).toBe('translate3d(0, 400px, 0)')
    expect(screen.getByRole('button', { name: '3 comments on this text' })).toHaveTextContent('3')
  })

  it('pushes overlapping bubbles apart so none sits on another', () => {
    render(
      <CountBubbles
        items={[
          { id: 'a', top: 100, count: 1 },
          { id: 'b', top: 104, count: 1 },
          { id: 'c', top: 106, count: 1 },
        ]}
        activeId={null}
        onSelect={vi.fn()}
      />
    )
    const step = BUBBLE_SIZE + BUBBLE_GAP
    expect(y('a')).toBe('translate3d(0, 100px, 0)')
    expect(y('b')).toBe(`translate3d(0, ${100 + step}px, 0)`)
    expect(y('c')).toBe(`translate3d(0, ${100 + 2 * step}px, 0)`)
  })

  it('keeps the active bubble exactly at its highlight and moves its neighbours instead', () => {
    render(
      <CountBubbles
        items={[
          { id: 'a', top: 100, count: 1 },
          { id: 'b', top: 104, count: 1 },
        ]}
        activeId="b"
        onSelect={vi.fn()}
      />
    )
    expect(y('b')).toBe('translate3d(0, 104px, 0)')
    expect(y('a')).toBe(`translate3d(0, ${104 - BUBBLE_SIZE - BUBBLE_GAP}px, 0)`)
    expect(document.querySelector('[data-bubble-id="b"]')).toHaveAttribute('data-active', 'true')
  })

  it('reports a tap', () => {
    const onSelect = vi.fn()
    render(<CountBubbles items={[{ id: 'a', top: 0, count: 1 }]} activeId={null} onSelect={onSelect} />)
    fireEvent.click(screen.getByRole('button', { name: '1 comment on this text' }))
    expect(onSelect).toHaveBeenCalledWith('a')
  })
})

describe('selectionButtonTop', () => {
  const sel = { rectTop: 200, rectBottom: 220, buttonHeight: 32 }
  it('sits above a mouse selection', () => {
    expect(selectionButtonTop({ ...sel, coarse: false })).toBe(160)
  })
  it('drops below a mouse selection with no room above', () => {
    expect(selectionButtonTop({ ...sel, rectTop: 10, rectBottom: 30, coarse: false, minTop: 8 })).toBe(38)
  })
  it('always sits below a touch selection, past the drag handles', () => {
    expect(selectionButtonTop({ ...sel, coarse: true })).toBe(250)
    expect(selectionButtonTop({ ...sel, rectTop: 10, rectBottom: 30, coarse: true })).toBe(60)
  })
})
