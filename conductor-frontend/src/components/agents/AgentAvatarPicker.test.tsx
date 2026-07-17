import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AgentAvatarPicker } from './AgentAvatarPicker'

describe('AgentAvatarPicker', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('calls onChange with the clicked emoji, keeping the current color', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<AgentAvatarPicker emoji="🤖" color="blue" onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Use 📚' }))

    expect(onChange).toHaveBeenCalledWith({ emoji: '📚', color: 'blue' })
  })

  it('calls onChange with the clicked color, keeping the current emoji', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<AgentAvatarPicker emoji="🤖" color="blue" onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: 'Use violet' }))

    expect(onChange).toHaveBeenCalledWith({ emoji: '🤖', color: 'violet' })
  })

  it('marks the selected emoji and color as pressed', () => {
    render(<AgentAvatarPicker emoji="📚" color="violet" onChange={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Use 📚' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Use violet' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Use 🤖' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByRole('button', { name: 'Use gray' })).toHaveAttribute('aria-pressed', 'false')
  })

  it('shuffle picks a deterministic emoji+color pair via onChange', async () => {
    // First curated emoji is 🤖 (index 0), first color token is gray (index 0) — force both
    // Math.random() calls inside randomAvatar() to land on index 0 so the assertion is deterministic.
    vi.spyOn(Math, 'random').mockReturnValue(0)
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<AgentAvatarPicker emoji="📚" color="violet" onChange={onChange} />)

    await user.click(screen.getByRole('button', { name: /shuffle/i }))

    expect(onChange).toHaveBeenCalledWith({ emoji: '🤖', color: 'gray' })
  })

  it('free-text entry updates the emoji once non-empty', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<AgentAvatarPicker emoji="🤖" color="blue" onChange={onChange} />)

    await user.clear(screen.getByLabelText('Custom emoji'))
    await user.type(screen.getByLabelText('Custom emoji'), '🦄')

    expect(onChange).toHaveBeenLastCalledWith({ emoji: '🦄', color: 'blue' })
  })

  it('free-text entry does not fire onChange while the field is empty', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    render(<AgentAvatarPicker emoji="🤖" color="blue" onChange={onChange} />)

    await user.clear(screen.getByLabelText('Custom emoji'))

    expect(onChange).not.toHaveBeenCalled()
  })
})
