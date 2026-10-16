import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TargetNote } from './TargetNote'
import { metric } from './test-fixtures'

describe('TargetNote', () => {
  it('offers to set a target when there is none', () => {
    const onEdit = vi.fn()
    render(<TargetNote metric={metric()} canEdit onEdit={onEdit} />)
    expect(screen.getByText(/No target set/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Set target for Clicks' }))
    expect(onEdit).toHaveBeenCalled()
  })

  it('hides the button for someone who cannot edit', () => {
    render(<TargetNote metric={metric()} canEdit={false} onEdit={vi.fn()} />)
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('shows a read-only target with its source and never a button', () => {
    render(
      <TargetNote
        metric={metric({ value: 100, target: { value: 200, direction: 'UP', readOnly: true, source: 'Set in Rexipe backend' } })}
        canEdit
        onEdit={vi.fn()}
      />,
    )
    expect(screen.getByText(/Target 200/)).toBeInTheDocument()
    expect(screen.getByText('Set in Rexipe backend')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })

  it('prefixes a bare source name', () => {
    render(
      <TargetNote
        metric={metric({ target: { value: 2000, direction: 'UP', readOnly: true, source: 'Rexipe' } })}
        canEdit
        onEdit={vi.fn()}
      />,
    )
    expect(screen.getByText('Set in Rexipe')).toBeInTheDocument()
  })

  it('offers no button and names the owner when the channel does not allow editing', () => {
    render(<TargetNote metric={metric()} canEdit editable={false} ownerLabel="Rexipe backend" onEdit={vi.fn()} />)
    expect(screen.getByText('No target set · set in Rexipe backend')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
