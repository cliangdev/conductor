import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import { TargetDialog } from './TargetDialog'
import { metric } from './test-fixtures'

const readOnly = metric({
  metric: 'trial_started',
  label: 'Trials started',
  target: { value: 200, direction: 'UP', readOnly: true, source: 'Set in Rexipe backend' },
})

describe('TargetDialog for a read-only target', () => {
  it('explains where the target is set and offers no way to edit or remove it', () => {
    const onSave = vi.fn()
    const onRemove = vi.fn()
    const onOpenChange = vi.fn()
    render(<TargetDialog open onOpenChange={onOpenChange} metric={readOnly} onSave={onSave} onRemove={onRemove} />)
    expect(screen.getByText(/Set in Rexipe backend/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Target')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save target' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Remove target' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(onSave).not.toHaveBeenCalled()
    expect(onRemove).not.toHaveBeenCalled()
  })
})
