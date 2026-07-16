import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Tabs, type TabItem } from './tabs'

const items: TabItem[] = [
  { value: 'a', label: 'Alpha', count: 2 },
  { value: 'b', label: 'Beta', count: 0 },
  { value: 'c', label: 'Gamma' },
]

describe('Tabs', () => {
  it('renders tablist/tab roles with aria-selected and count pills', () => {
    render(<Tabs items={items} value="a" ariaLabel="Test tabs" />)

    expect(screen.getByRole('tablist', { name: 'Test tabs' })).toBeInTheDocument()
    const tabs = screen.getAllByRole('tab')
    expect(tabs).toHaveLength(3)
    expect(screen.getByRole('tab', { name: /Alpha/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: /Beta/ })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByText('2')).toBeInTheDocument()
    // Beta's count is 0 (still rendered — falsy but defined); Gamma has no `count` at all, no pill.
    expect(screen.getByText('0')).toBeInTheDocument()
  })

  it('uses roving tabindex — only the active tab is focusable via Tab key', () => {
    render(<Tabs items={items} value="b" ariaLabel="Test tabs" />)

    expect(screen.getByRole('tab', { name: /Alpha/ })).toHaveAttribute('tabIndex', '-1')
    expect(screen.getByRole('tab', { name: /Beta/ })).toHaveAttribute('tabIndex', '0')
    expect(screen.getByRole('tab', { name: /Gamma/ })).toHaveAttribute('tabIndex', '-1')
  })

  it('moves selection with ArrowRight/ArrowLeft and wraps around', () => {
    const onValueChange = vi.fn()
    render(<Tabs items={items} value="a" onValueChange={onValueChange} ariaLabel="Test tabs" />)

    fireEvent.keyDown(screen.getByRole('tab', { name: /Alpha/ }), { key: 'ArrowRight' })
    expect(onValueChange).toHaveBeenCalledWith('b')

    fireEvent.keyDown(screen.getByRole('tab', { name: /Alpha/ }), { key: 'ArrowLeft' })
    expect(onValueChange).toHaveBeenCalledWith('c')
  })

  it('calls onValueChange on click for controlled (button) tabs', () => {
    const onValueChange = vi.fn()
    render(<Tabs items={items} value="a" onValueChange={onValueChange} ariaLabel="Test tabs" />)

    fireEvent.click(screen.getByRole('tab', { name: /Gamma/ }))
    expect(onValueChange).toHaveBeenCalledWith('c')
  })

  it('renders href items as links with aria-current on the active one', () => {
    const linkItems: TabItem[] = [
      { value: 'overview', label: 'Overview', href: '/x/overview' },
      { value: 'settings', label: 'Settings', href: '/x/settings' },
    ]
    render(<Tabs items={linkItems} value="overview" ariaLabel="Route tabs" />)

    const active = screen.getByRole('tab', { name: 'Overview' })
    expect(active.tagName).toBe('A')
    expect(active).toHaveAttribute('aria-current', 'page')
    expect(active).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Settings' })).not.toHaveAttribute('aria-current')
  })

  it('wires aria-controls via getPanelId', () => {
    render(
      <Tabs
        items={items}
        value="a"
        ariaLabel="Test tabs"
        getPanelId={(v) => `panel-${v}`}
      />,
    )

    expect(screen.getByRole('tab', { name: /Alpha/ })).toHaveAttribute('aria-controls', 'panel-a')
  })
})
