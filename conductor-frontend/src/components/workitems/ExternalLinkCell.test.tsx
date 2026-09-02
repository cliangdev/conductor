import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ExternalLinkCell } from './ExternalLinkCell'

describe('ExternalLinkCell', () => {
  it('renders nothing when the item has no recorded links', () => {
    const { container } = render(<ExternalLinkCell />)
    expect(container).toBeEmptyDOMElement()

    const empty = render(<ExternalLinkCell links={[]} />)
    expect(empty.container).toBeEmptyDOMElement()
  })

  it('links straight out to the thing itself, in a new tab', () => {
    render(
      <ExternalLinkCell
        links={[{ url: 'https://instagram.com/p/Cx4', type: 'instagram_post', label: '@acme' }]}
      />
    )

    const link = screen.getByRole('link')
    expect(link).toHaveAttribute('href', 'https://instagram.com/p/Cx4')
    expect(link).toHaveAttribute('target', '_blank')
    // Without noopener the opened page gets a handle on this one via window.opener.
    expect(link.getAttribute('rel')).toContain('noopener')
  })

  it('names the destination so the icon is not a mystery', () => {
    render(
      <ExternalLinkCell
        links={[{ url: 'https://www.instagram.com/p/Cx4', type: 'instagram_post', label: '@acme' }]}
      />
    )

    // The host, not the whole URL: "instagram.com" reads as a place, the URL reads as noise. And the
    // www. is stripped, because it tells a reader nothing.
    expect(screen.getByRole('link')).toHaveAccessibleName('Open @acme — instagram.com')
  })

  it('shows one icon per destination rather than collapsing them into a count', () => {
    // Two accounts is the ordinary case. "2 links" would put back the trip this exists to remove.
    render(
      <ExternalLinkCell
        links={[
          { url: 'https://instagram.com/p/1', type: 'instagram_post' },
          { url: 'https://facebook.com/p/2', type: 'facebook_post' },
        ]}
      />
    )

    expect(screen.getAllByRole('link')).toHaveLength(2)
  })

  it('carries no vocabulary from any one Workflow', () => {
    // Same rule the calendar and list surfaces hold themselves to: this renders an Issue's pull request
    // exactly as it renders a published social post, and must not know which is which.
    render(<ExternalLinkCell links={[{ url: 'https://github.com/a/b/pull/1', type: 'github_pr' }]} />)

    expect(screen.getByRole('link')).toHaveAttribute('href', 'https://github.com/a/b/pull/1')
  })

  it('survives a ref that is not a parseable URL', () => {
    render(<ExternalLinkCell links={[{ url: 'not a url', type: 'note' }]} />)

    expect(screen.getByRole('link')).toHaveAccessibleName('Open not a url')
  })
})
