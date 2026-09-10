import { useState } from 'react'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { TimeZonePicker, zoneDisplayLabel } from './time-zone-picker'

function renderPicker(initial = 'America/Los_Angeles') {
  const onChange = vi.fn()
  const Harness = () => {
    const [value, setValue] = useState(initial)
    return (
      <TimeZonePicker
        value={value}
        onChange={(v) => {
          setValue(v)
          onChange(v)
        }}
      />
    )
  }
  render(<Harness />)
  return { onChange }
}

describe('TimeZonePicker', () => {
  it('shows a readable name for the current zone, not the bare id', () => {
    renderPicker('America/Los_Angeles')
    expect(screen.queryByText('America/Los_Angeles')).not.toBeInTheDocument()
    expect(screen.getByText(zoneDisplayLabel('America/Los_Angeles'))).toBeInTheDocument()
  })

  it('opens a searchable list on Change, current zone first', async () => {
    renderPicker('America/Los_Angeles')
    await userEvent.click(screen.getByRole('button', { name: 'Change' }))
    const listbox = screen.getByRole('listbox')
    expect(within(listbox).getByRole('option', { name: zoneDisplayLabel('America/Los_Angeles') })).toBeInTheDocument()
  })

  it('filters as you type by id or long name', async () => {
    renderPicker('UTC')
    await userEvent.click(screen.getByRole('button', { name: 'Change' }))
    await userEvent.type(screen.getByLabelText('Search time zones'), 'Tokyo')
    const listbox = screen.getByRole('listbox')
    expect(within(listbox).getByRole('option', { name: /Asia\/Tokyo|Tokyo/ })).toBeInTheDocument()
    expect(within(listbox).queryByRole('option', { name: /America\/New_York|Eastern/ })).not.toBeInTheDocument()
  })

  it('calls onChange when a zone is chosen and closes the list', async () => {
    const { onChange } = renderPicker('UTC')
    await userEvent.click(screen.getByRole('button', { name: 'Change' }))
    await userEvent.type(screen.getByLabelText('Search time zones'), 'Asia/Tokyo')
    await userEvent.click(screen.getByRole('option', { name: /Asia\/Tokyo|Tokyo/ }))
    expect(onChange).toHaveBeenCalledWith('Asia/Tokyo')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })

  it('closes on Escape without changing the value', async () => {
    const { onChange } = renderPicker('UTC')
    await userEvent.click(screen.getByRole('button', { name: 'Change' }))
    expect(screen.getByRole('listbox')).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('closes on Cancel', async () => {
    renderPicker('UTC')
    await userEvent.click(screen.getByRole('button', { name: 'Change' }))
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
  })
})
