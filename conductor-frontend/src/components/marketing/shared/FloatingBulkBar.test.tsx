import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { FloatingBulkBar } from './FloatingBulkBar'

describe('FloatingBulkBar', () => {
  it('shows the count, hidden note, actions and a working Clear', () => {
    const onClear = vi.fn()
    render(
      <FloatingBulkBar count={3} hiddenCount={2} onClear={onClear}>
        <button>Do it</button>
      </FloatingBulkBar>,
    )
    expect(screen.getByRole('toolbar', { name: 'Selection actions' })).toBeInTheDocument()
    expect(screen.getByText('3 selected')).toBeInTheDocument()
    expect(screen.getByText('(2 hidden by filters)')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Do it' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(onClear).toHaveBeenCalled()
  })

  it('omits Clear and the hidden note when not given', () => {
    render(<FloatingBulkBar count={1} />)
    expect(screen.queryByRole('button', { name: 'Clear' })).toBeNull()
    expect(screen.queryByText(/hidden by filters/)).toBeNull()
  })
})
