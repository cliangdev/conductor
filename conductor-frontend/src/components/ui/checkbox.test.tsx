import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Checkbox } from './checkbox'

describe('Checkbox', () => {
  it('is found by its label and reports a change', async () => {
    const onCheckedChange = vi.fn()
    render(<Checkbox checked={false} onCheckedChange={onCheckedChange} label="Publish as soon as it's approved" />)
    const box = screen.getByLabelText("Publish as soon as it's approved")
    expect(box).not.toBeChecked()
    await userEvent.click(box)
    expect(onCheckedChange).toHaveBeenCalledWith(true)
  })

  it('reflects checked', () => {
    render(<Checkbox checked label="Consent" onCheckedChange={vi.fn()} />)
    expect(screen.getByLabelText('Consent')).toBeChecked()
  })

  it('renders a description under the label', () => {
    render(
      <Checkbox
        checked={false}
        onCheckedChange={vi.fn()}
        label="Facebook Page"
        description="a person posts it by hand"
      />
    )
    expect(screen.getByText('a person posts it by hand')).toBeInTheDocument()
  })

  it('shows a visible disabled reason, not just a title attribute', () => {
    render(
      <Checkbox
        checked={false}
        onCheckedChange={vi.fn()}
        label="TikTok creator"
        disabled
        disabledReason="Reconnect this account before it can publish."
      />
    )
    const box = screen.getByLabelText('TikTok creator')
    expect(box).toBeDisabled()
    expect(screen.getByText('Reconnect this account before it can publish.')).toBeInTheDocument()
    expect(box).not.toHaveAttribute('title')
  })

  it('does not render a disabled reason while enabled', () => {
    render(
      <Checkbox
        checked={false}
        onCheckedChange={vi.fn()}
        label="TikTok creator"
        disabledReason="Reconnect this account before it can publish."
      />
    )
    expect(screen.queryByText('Reconnect this account before it can publish.')).not.toBeInTheDocument()
  })

  it('does not fire onCheckedChange while disabled', async () => {
    const onCheckedChange = vi.fn()
    render(<Checkbox checked={false} onCheckedChange={onCheckedChange} label="Locked" disabled />)
    await userEvent.click(screen.getByLabelText('Locked'), { pointerEventsCheck: 0 })
    expect(onCheckedChange).not.toHaveBeenCalled()
  })

  it('accepts an explicit id', () => {
    render(<Checkbox checked={false} onCheckedChange={vi.fn()} label="Named" id="my-checkbox" />)
    expect(screen.getByLabelText('Named')).toHaveAttribute('id', 'my-checkbox')
  })
})
