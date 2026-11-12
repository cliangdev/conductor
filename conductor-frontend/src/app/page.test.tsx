import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import LandingPage from './page'

describe('landing page', () => {
  it('has exactly one h1 with the headline', () => {
    render(<LandingPage />)
    const h1s = screen.getAllByRole('heading', { level: 1 })
    expect(h1s).toHaveLength(1)
    expect(h1s[0]).toHaveTextContent('You make the calls')
  })

  it('links to sign-up from more than one place', () => {
    const { container } = render(<LandingPage />)
    expect(container.querySelectorAll('a[href="/login?mode=signup"]').length).toBeGreaterThanOrEqual(2)
  })

  it('keeps the YouTube disclosure and legal links reviewers read', () => {
    const { container } = render(<LandingPage />)
    expect(screen.getByRole('heading', { name: 'How Conductor uses YouTube' })).toBeInTheDocument()
    expect(screen.getByText(/Limited Use requirements/)).toBeInTheDocument()
    for (const href of ['/privacy', '/data-deletion', '/terms']) {
      expect(container.querySelector(`a[href="${href}"]`)).not.toBeNull()
    }
    expect(
      container.querySelector('a[href="https://developers.google.com/terms/api-services-user-data-policy"]'),
    ).not.toBeNull()
    expect(container.querySelector('#google-data')).not.toBeNull()
  })
})
