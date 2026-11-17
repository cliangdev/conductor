import { describe, it, expect, vi, afterEach } from 'vitest'
import { render } from '@testing-library/react'
import { MarginRail } from './MarginRail'

function railEl(id: string): HTMLElement {
  return document.querySelector(`[data-rail-id="${id}"]`) as HTMLElement
}

describe('MarginRail', () => {
  afterEach(() => vi.restoreAllMocks())

  it('places each card at its anchor minus the offset, and pushes overlapping ones apart', () => {
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
      const h = this.hasAttribute('data-rail-id') ? 50 : 0
      return { top: 0, left: 0, right: 0, bottom: h, width: 0, height: h, x: 0, y: 0, toJSON() {} } as DOMRect
    })
    const items = [
      { id: 'a', top: 100, node: <p>A</p> },
      { id: 'b', top: 110, node: <p>B</p> },
      { id: 'far', top: 400, node: <p>F</p> },
    ]
    const { rerender } = render(<MarginRail items={items} activeId={null} offset={20} />)
    expect(railEl('a').style.transform).toBe('translate3d(0, 80px, 0)')
    // 100 + 50 + 8 = 158, minus the offset.
    expect(railEl('b').style.transform).toBe('translate3d(0, 138px, 0)')
    expect(railEl('far').style.transform).toBe('translate3d(0, 380px, 0)')

    // The active card is pinned at its own anchor and the one above it moves up.
    rerender(<MarginRail items={items} activeId="b" offset={20} />)
    expect(railEl('b').style.transform).toBe('translate3d(0, 90px, 0)')
    expect(railEl('a').style.transform).toBe('translate3d(0, 32px, 0)')
  })

  it('moves cards with transform only, so scrolling does not reflow', () => {
    render(<MarginRail items={[{ id: 'a', top: 10, node: <p>A</p> }]} activeId={null} offset={0} />)
    expect(railEl('a').style.top).toBe('')
    expect(railEl('a').style.transform).toContain('translate3d')
  })

  it('moves the cards when only the scroll offset changes', () => {
    const items = [{ id: 'a', top: 300, node: <p>A</p> }]
    const { rerender } = render(<MarginRail items={items} activeId={null} offset={0} />)
    expect(railEl('a').style.transform).toBe('translate3d(0, 300px, 0)')
    rerender(<MarginRail items={items} activeId={null} offset={120} />)
    expect(railEl('a').style.transform).toBe('translate3d(0, 180px, 0)')
  })
})
