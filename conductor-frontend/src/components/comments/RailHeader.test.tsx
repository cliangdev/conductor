import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RailHeader } from './RailHeader'

function setup(props: Partial<React.ComponentProps<typeof RailHeader>> = {}) {
  const handlers = { onFilterChange: vi.fn(), onPrev: vi.fn(), onNext: vi.fn() }
  render(<RailHeader filter="open" index={2} total={7} {...handlers} {...props} />)
  return handlers
}

describe('RailHeader', () => {
  it('shows the three filters with the current one pressed, and changes filter', () => {
    const h = setup({ filter: 'mine' })
    expect(screen.getByRole('button', { name: 'Mine' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Open' })).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(screen.getByRole('button', { name: 'Resolved' }))
    expect(h.onFilterChange).toHaveBeenCalledWith('resolved')
  })

  it('shows "2 of 7" and steps with the arrows', () => {
    const h = setup()
    expect(screen.getByTestId('rail-position')).toHaveTextContent('2 of 7')
    fireEvent.click(screen.getByRole('button', { name: 'Next thread' }))
    fireEvent.click(screen.getByRole('button', { name: 'Previous thread' }))
    expect(h.onNext).toHaveBeenCalledTimes(1)
    expect(h.onPrev).toHaveBeenCalledTimes(1)
  })

  it('shows a dash when no thread is focused, and disables the arrows with no threads', () => {
    setup({ index: null, total: 0 })
    expect(screen.getByTestId('rail-position')).toHaveTextContent('– of 0')
    expect(screen.getByRole('button', { name: 'Next thread' })).toBeDisabled()
  })

  it('j and k move between threads', () => {
    const h = setup()
    fireEvent.keyDown(document.body, { key: 'j' })
    fireEvent.keyDown(document.body, { key: 'k' })
    fireEvent.keyDown(document.body, { key: 'k' })
    expect(h.onNext).toHaveBeenCalledTimes(1)
    expect(h.onPrev).toHaveBeenCalledTimes(2)
  })

  it('ignores j and k while typing, and with a modifier', () => {
    const h = setup()
    const area = document.createElement('textarea')
    const input = document.createElement('input')
    document.body.append(area, input)
    fireEvent.keyDown(area, { key: 'j' })
    fireEvent.keyDown(input, { key: 'k' })
    fireEvent.keyDown(document.body, { key: 'j', metaKey: true })
    fireEvent.keyDown(document.body, { key: 'k', ctrlKey: true })
    expect(h.onNext).not.toHaveBeenCalled()
    expect(h.onPrev).not.toHaveBeenCalled()
    area.remove()
    input.remove()
  })

  it('stops listening for keys when turned off, and on unmount', () => {
    const h = { onFilterChange: vi.fn(), onPrev: vi.fn(), onNext: vi.fn() }
    const { rerender, unmount } = render(<RailHeader filter="open" index={1} total={2} {...h} keyboard={false} />)
    fireEvent.keyDown(document.body, { key: 'j' })
    expect(h.onNext).not.toHaveBeenCalled()
    rerender(<RailHeader filter="open" index={1} total={2} {...h} />)
    fireEvent.keyDown(document.body, { key: 'j' })
    expect(h.onNext).toHaveBeenCalledTimes(1)
    unmount()
    fireEvent.keyDown(document.body, { key: 'j' })
    expect(h.onNext).toHaveBeenCalledTimes(1)
  })
})
