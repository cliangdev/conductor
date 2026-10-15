import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { TargetDialog } from './TargetDialog'
import { metric } from './test-fixtures'

const ctr = metric({ metric: 'ctr', label: 'Click-through rate', unit: 'PERCENT', value: 0.031 })

function setup(m = ctr, handlers: { onSave?: () => Promise<void>; onRemove?: () => Promise<void> } = {}) {
  const onSave = vi.fn(handlers.onSave ?? (() => Promise.resolve()))
  const onRemove = vi.fn(handlers.onRemove ?? (() => Promise.resolve()))
  const onOpenChange = vi.fn()
  render(<TargetDialog open onOpenChange={onOpenChange} metric={m} onSave={onSave} onRemove={onRemove} />)
  return { onSave, onRemove, onOpenChange }
}

describe('TargetDialog', () => {
  it('converts a typed percentage to a fraction', async () => {
    const { onSave, onOpenChange } = setup()
    fireEvent.change(screen.getByLabelText('Target (%)'), { target: { value: '4' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save target' }))
    await waitFor(() => expect(onSave).toHaveBeenCalledWith({ target: 0.04, direction: 'UP' }))
    await waitFor(() => expect(onOpenChange).toHaveBeenCalledWith(false))
  })

  it('defaults the direction to the metric good direction', () => {
    setup(metric({ metric: 'position', label: 'Average position', unit: 'POSITION', goodDirection: 'DOWN' }))
    expect(screen.getByLabelText('Good direction')).toHaveValue('DOWN')
  })

  it('rejects an invalid number without calling the server', () => {
    const { onSave } = setup()
    fireEvent.change(screen.getByLabelText('Target (%)'), { target: { value: '-3' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save target' }))
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a number of zero or more.')
    expect(onSave).not.toHaveBeenCalled()
  })

  it('shows the server validation message inline and stays open', async () => {
    const { onOpenChange } = setup(ctr, {
      onSave: () => Promise.reject(Object.assign(new Error('bad'), { detail: 'Target must be below 100%' })),
    })
    fireEvent.change(screen.getByLabelText('Target (%)'), { target: { value: '400' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save target' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Target must be below 100%')
    expect(onOpenChange).not.toHaveBeenCalled()
  })

  it('prefills an existing target and removes it', async () => {
    const { onRemove } = setup(metric({ ...ctr, target: { value: 0.04, direction: 'UP' } }))
    expect(screen.getByLabelText('Target (%)')).toHaveValue('4')
    fireEvent.click(screen.getByRole('button', { name: 'Remove target' }))
    await waitFor(() => expect(onRemove).toHaveBeenCalled())
  })

  it('offers no remove button when there is no target', () => {
    setup()
    expect(screen.queryByRole('button', { name: 'Remove target' })).not.toBeInTheDocument()
  })
})
