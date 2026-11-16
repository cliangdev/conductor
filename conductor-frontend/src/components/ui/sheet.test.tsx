import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { Sheet } from './sheet'

describe('Sheet', () => {
  it('renders title, description, and children when open', () => {
    render(
      <Sheet open onOpenChange={vi.fn()} title="Step details" description="http step">
        <div>step body</div>
      </Sheet>
    )
    expect(screen.getByText('Step details')).toBeInTheDocument()
    expect(screen.getByText('http step')).toBeInTheDocument()
    expect(screen.getByText('step body')).toBeInTheDocument()
  })

  it('renders nothing when closed', () => {
    render(
      <Sheet open={false} onOpenChange={vi.fn()} title="Step details">
        <div>step body</div>
      </Sheet>
    )
    expect(screen.queryByText('Step details')).not.toBeInTheDocument()
    expect(screen.queryByText('step body')).not.toBeInTheDocument()
  })

  it('renders the footer when provided', () => {
    render(
      <Sheet open onOpenChange={vi.fn()} title="Step details" footer={<button>Close</button>}>
        <div>step body</div>
      </Sheet>
    )
    expect(screen.getByRole('button', { name: 'Close' })).toBeInTheDocument()
  })

  it('calls onOpenChange(false) when the backdrop is clicked', () => {
    const onOpenChange = vi.fn()
    render(
      <Sheet open onOpenChange={onOpenChange} title="Step details">
        <div>step body</div>
      </Sheet>
    )
    // Dialog.Portal renders the backdrop into document.body, outside RTL's `container`.
    const backdrop = document.querySelector('.fixed.inset-0')
    expect(backdrop).not.toBeNull()
    fireEvent.click(backdrop!)
    expect(onOpenChange.mock.calls[0][0]).toBe(false)
  })

  it('slides up from the bottom, clear of the safe-area insets, with a close button', () => {
    const onOpenChange = vi.fn()
    render(
      <Sheet open onOpenChange={onOpenChange} title="Comments" side="bottom" showClose>
        <div>body</div>
      </Sheet>
    )
    const popup = screen.getByRole('dialog')
    expect(popup.className).toContain('bottom-0')
    expect(popup.className).toContain('safe-area-inset-bottom')
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onOpenChange.mock.calls[0][0]).toBe(false)
  })

  it('has no close button unless asked for', () => {
    render(
      <Sheet open onOpenChange={vi.fn()} title="Step details">
        <div>body</div>
      </Sheet>
    )
    expect(screen.queryByRole('button', { name: 'Close' })).not.toBeInTheDocument()
  })

  it('locks the page scroll while open and traps focus inside', async () => {
    render(
      <>
        <button>outside</button>
        <Sheet open onOpenChange={vi.fn()} title="Comments" showClose>
          <button>inside</button>
        </Sheet>
      </>
    )
    await vi.waitFor(() => expect(screen.getByRole('dialog').contains(document.activeElement)).toBe(true))
    expect([document.documentElement.style.overflowY, document.body.style.overflowY]).toContain('hidden')
  })

  it('calls onOpenChange(false) on Escape', () => {
    const onOpenChange = vi.fn()
    render(
      <Sheet open onOpenChange={onOpenChange} title="Step details">
        <div>step body</div>
      </Sheet>
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onOpenChange.mock.calls[0][0]).toBe(false)
  })
})
