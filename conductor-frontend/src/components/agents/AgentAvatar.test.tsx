import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { AgentAvatar, AVATAR_SIZE_CLASSES, AVATAR_COLOR_CLASSES } from './AgentAvatar'

describe('AgentAvatar', () => {
  it('renders the emoji, hidden from the accessibility tree', () => {
    render(<AgentAvatar emoji="📚" color="violet" />)

    const emoji = screen.getByText('📚')
    expect(emoji).toHaveAttribute('aria-hidden', 'true')
  })

  it.each(['sm', 'md', 'lg'] as const)('applies the %s size class', (size) => {
    const { container } = render(<AgentAvatar emoji="🤖" color="blue" size={size} />)

    for (const cls of AVATAR_SIZE_CLASSES[size].split(' ')) {
      expect(container.firstChild).toHaveClass(cls)
    }
  })

  it('defaults to md size when none is given', () => {
    const { container } = render(<AgentAvatar emoji="🤖" color="blue" />)

    for (const cls of AVATAR_SIZE_CLASSES.md.split(' ')) {
      expect(container.firstChild).toHaveClass(cls)
    }
  })

  it.each(['gray', 'blue', 'amber', 'violet', 'teal', 'green', 'rose', 'slate'] as const)(
    'maps the %s token to its color class',
    (token) => {
      const { container } = render(<AgentAvatar emoji="🤖" color={token} />)

      expect(container.firstChild).toHaveClass(AVATAR_COLOR_CLASSES[token])
    }
  )

  it('falls back to the muted neutral for an unknown color token', () => {
    const { container } = render(<AgentAvatar emoji="🤖" color="chartreuse" />)

    expect(container.firstChild).toHaveClass('bg-muted')
  })

  it('falls back to the muted neutral for an empty color', () => {
    const { container } = render(<AgentAvatar emoji="🤖" color="" />)

    expect(container.firstChild).toHaveClass('bg-muted')
  })

  it('applies rounded-full so the avatar renders as a circle', () => {
    const { container } = render(<AgentAvatar emoji="🤖" color="blue" />)

    expect(container.firstChild).toHaveClass('rounded-full')
  })
})
