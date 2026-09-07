import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { DateTimePicker, describeWallClock, parseWallClock } from './date-time-picker'

function renderPicker(props: Partial<React.ComponentProps<typeof DateTimePicker>> = {}) {
  const onChange = vi.fn()
  const Harness = () => {
    const [value, setValue] = useState(props.value ?? '')
    return (
      <DateTimePicker
        label="When"
        {...props}
        value={value}
        onChange={(v) => { setValue(v); onChange(v) }}
      />
    )
  }
  render(<Harness />)
  return { onChange }
}

describe('DateTimePicker', () => {
  it('is closed until the field is clicked, then shows a month grid and a time row', async () => {
    renderPicker()
    expect(screen.queryByRole('grid')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'When' }))
    expect(screen.getByRole('grid')).toBeInTheDocument()
    expect(screen.getByLabelText('Hour')).toBeInTheDocument()
    expect(screen.getByLabelText('Minute')).toBeInTheDocument()
    expect(screen.getByLabelText('AM or PM')).toBeInTheDocument()
  })

  it('emits a wall-clock value from a picked day and time, and shows it on the field', async () => {
    const { onChange } = renderPicker()
    await userEvent.click(screen.getByRole('button', { name: 'When' }))
    await userEvent.selectOptions(screen.getByLabelText('Month'), '7')
    await userEvent.selectOptions(screen.getByLabelText('Year'), '2026')
    await userEvent.click(screen.getByRole('gridcell', { name: 'July 4, 2026' }))
    // A first pick lands on 9:00 rather than midnight.
    expect(onChange).toHaveBeenLastCalledWith('2026-07-04T09:00')
    await userEvent.selectOptions(screen.getByLabelText('Hour'), '2')
    await userEvent.selectOptions(screen.getByLabelText('Minute'), '30')
    await userEvent.selectOptions(screen.getByLabelText('AM or PM'), 'PM')
    expect(onChange).toHaveBeenLastCalledWith('2026-07-04T14:30')
    expect(screen.getByRole('button', { name: 'When' })).toHaveTextContent(describeWallClock('2026-07-04T14:30'))
    await userEvent.click(screen.getByRole('button', { name: 'Done' }))
    expect(screen.queryByRole('grid')).not.toBeInTheDocument()
  })

  it('opens on the month of an existing value and marks that day', async () => {
    renderPicker({ value: '2027-02-14T08:15' })
    await userEvent.click(screen.getByRole('button', { name: 'When' }))
    expect(screen.getByLabelText('Month')).toHaveValue('2')
    expect(screen.getByLabelText('Year')).toHaveValue('2027')
    expect(screen.getByRole('gridcell', { name: 'February 14, 2027' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('Hour')).toHaveValue('8')
    expect(screen.getByLabelText('Minute')).toHaveValue('15')
  })

  it('disables days before min and can clear when told it may', async () => {
    const { onChange } = renderPicker({ value: '2026-03-10T09:00', min: '2026-03-05', clearable: true })
    await userEvent.click(screen.getByRole('button', { name: 'When' }))
    expect(screen.getByRole('gridcell', { name: 'March 4, 2026' })).toBeDisabled()
    expect(screen.getByRole('gridcell', { name: 'March 5, 2026' })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(onChange).toHaveBeenLastCalledWith('')
  })

  it('closes when something outside it is pressed', async () => {
    renderPicker()
    await userEvent.click(screen.getByRole('button', { name: 'When' }))
    expect(screen.getByRole('grid')).toBeInTheDocument()
    await userEvent.click(document.body)
    expect(screen.queryByRole('grid')).not.toBeInTheDocument()
  })

  it('closes on Escape', async () => {
    renderPicker()
    await userEvent.click(screen.getByRole('button', { name: 'When' }))
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('grid')).not.toBeInTheDocument()
  })

  it('parses and formats the datetime-local shape it shares with the old input', () => {
    expect(parseWallClock('2026-07-04T09:05')).toEqual({ y: 2026, m: 7, d: 4, h: 9, mi: 5 })
    expect(parseWallClock('nope')).toBeNull()
    expect(describeWallClock('2026-07-04T09:05')).toMatch(/Jul 4, 2026/)
  })
})
