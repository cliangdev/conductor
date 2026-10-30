import { useState } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { DocumentTabs, fitTabs, handleTabListKeyDown, type DocumentTab } from './DocumentTabs'

describe('fitTabs', () => {
  it('shows every tab when they all fit', () => {
    expect(fitTabs([100, 100, 100], 300, 80, 0)).toEqual({ shown: [0, 1, 2], hidden: [] })
  })

  it('keeps room for the more tab and stops at the first tab that would overflow', () => {
    expect(fitTabs([100, 100, 100, 100], 350, 80, 0)).toEqual({ shown: [0, 1], hidden: [2, 3] })
  })

  it('always shows the kept tab, in its own place, even when it is past the cut', () => {
    expect(fitTabs([100, 100, 100, 100, 100], 400, 80, 4)).toEqual({ shown: [0, 1, 4], hidden: [2, 3] })
  })

  it('shows the kept tab alone when nothing else fits', () => {
    expect(fitTabs([100, 100, 100], 120, 80, 2)).toEqual({ shown: [2], hidden: [0, 1] })
  })

  it('fills from the start when no tab has to stay', () => {
    expect(fitTabs([100, 100, 100, 100], 300, 80, -1)).toEqual({ shown: [0, 1], hidden: [2, 3] })
  })
})

const DOCS: DocumentTab[] = [
  { id: 'd1', filename: 'prd.html', commentCount: 3, external: false },
  { id: 'd2', filename: 'architecture.md', commentCount: 0, external: false },
  { id: 'd3', filename: 'wireframes.md', commentCount: 1, external: false },
  { id: 'd4', filename: 'data-model.md', commentCount: 0, external: false },
  { id: 'd5', filename: 'curator-spec.md', commentCount: 2, external: false },
]

function Harness({ initial = 'd1', onSelect }: { initial?: string; onSelect?: (id: string) => void }) {
  const [active, setActive] = useState(initial)
  const select = (id: string) => {
    setActive(id)
    onSelect?.(id)
  }
  return (
    <div role="tablist" aria-label="Documents" onKeyDown={(e) => handleTabListKeyDown(e, select)}>
      <DocumentTabs
        documents={DOCS}
        activeId={active}
        onSelect={select}
        tabId={(id) => `tab-${id}`}
        panelId="panel"
      />
      <button role="tab" data-tab-id="activity" aria-selected={false} tabIndex={-1}>
        Activity
      </button>
    </div>
  )
}

describe('DocumentTabs', () => {
  // Every tab (and the "+N more" stand-in) measures 100px wide; the strip is 350px.
  const originalRO = globalThis.ResizeObserver
  beforeEach(() => {
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(100)
    globalThis.ResizeObserver = class {
      constructor(private cb: ResizeObserverCallback) {}
      observe() {
        this.cb([{ contentRect: { width: 350 } } as ResizeObserverEntry], this as unknown as ResizeObserver)
      }
      unobserve() {}
      disconnect() {}
    } as unknown as typeof ResizeObserver
  })
  afterEach(() => {
    vi.restoreAllMocks()
    globalThis.ResizeObserver = originalRO
  })

  it('shows the tabs that fit and says how many documents, and comments, are behind "+N more"', () => {
    render(<Harness />)
    expect(screen.getByRole('tab', { name: 'prd.html, 3 unresolved comments' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'architecture.md' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: /wireframes\.md/ })).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: '3 more documents, 3 unresolved comments' })).toHaveTextContent(/\+3\s*more/)
  })

  it('lists every document in the menu, the hidden ones first, and opens the one picked', async () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)
    await userEvent.click(screen.getByRole('tab', { name: /3 more documents/ }))

    const menu = await screen.findByRole('menu')
    expect(within(menu).getAllByRole('menuitem').map((el) => el.getAttribute('aria-label'))).toEqual([
      'wireframes.md, 1 unresolved comment',
      'data-model.md',
      'curator-spec.md, 2 unresolved comments',
      'prd.html, 3 unresolved comments',
      'architecture.md',
    ])
    expect(within(menu).getByRole('menuitem', { name: /prd\.html/ })).toHaveAttribute('aria-current', 'true')

    await userEvent.click(within(menu).getByRole('menuitem', { name: /curator-spec\.md/ }))
    expect(onSelect).toHaveBeenCalledWith('d5')
    // The picked document takes a visible slot, so the selection is never hidden in the menu.
    expect(screen.getByRole('tab', { name: /curator-spec\.md/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: /prd\.html/ })).toBeInTheDocument()
  })

  it('moves across the visible tabs with the arrow keys, focusing "+N more" without opening it', async () => {
    const onSelect = vi.fn()
    render(<Harness onSelect={onSelect} />)
    screen.getByRole('tab', { name: /prd\.html/ }).focus()

    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'architecture.md' })).toHaveFocus()
    expect(onSelect).toHaveBeenLastCalledWith('d2')

    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: /3 more documents/ })).toHaveFocus()
    expect(screen.queryByRole('menu')).not.toBeInTheDocument()

    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Activity' })).toHaveFocus()
    expect(onSelect).toHaveBeenLastCalledWith('activity')
  })

  it('shows every tab and no "+N more" when they fit', () => {
    vi.spyOn(HTMLElement.prototype, 'offsetWidth', 'get').mockReturnValue(40)
    render(<Harness />)
    expect(screen.getAllByRole('tab')).toHaveLength(DOCS.length + 1)
    expect(screen.queryByRole('tab', { name: /more document/ })).not.toBeInTheDocument()
  })
})
