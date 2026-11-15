import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { GetStarted } from './GetStarted'

describe('GetStarted', () => {
  it('renders the section with its id', () => {
    const { container } = render(<GetStarted />)
    expect(container.querySelector('section')).toHaveAttribute('id', 'get-started')
  })

  it('renders the four step headings', () => {
    render(<GetStarted />)
    for (const name of [
      'Create your workspace',
      'Install Claude Code',
      'Install Conductor and connect it',
      'Give it work',
    ]) {
      expect(screen.getByRole('heading', { level: 3, name })).toBeInTheDocument()
    }
  })

  it('links to signup', () => {
    render(<GetStarted />)
    expect(screen.getByRole('link', { name: 'Create your workspace' })).toHaveAttribute(
      'href',
      '/login?mode=signup',
    )
  })

  it('shows the conductor init command and copy buttons', () => {
    render(<GetStarted />)
    expect(screen.getByText(/cd your-project && conductor init/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Copy command' })).toHaveLength(3)
  })
})
