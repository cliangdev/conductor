import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DestinationMetrics, DestinationTotals, compactCount, unreportedNote } from './DestinationMetrics'

describe('unreportedNote', () => {
  it('says in words which numbers a platform does not hand out', () => {
    expect(unreportedNote(['tiktok'])).toBeNull()
    expect(unreportedNote(['facebook'])).toBe("Facebook doesn't report views.")
    expect(unreportedNote(['instagram', 'facebook', 'instagram'])).toBe(
      "Instagram doesn't report views or shares; Facebook doesn't report views."
    )
    expect(unreportedNote(['mastodon'])).toBeNull()
  })
})

describe('compactCount', () => {
  it('reads large numbers the way a row has room for, and a gap as a dash', () => {
    expect(compactCount(84)).toBe('84')
    expect(compactCount(1234)).toBe('1.2K')
    expect(compactCount(1_200_000)).toBe('1.2M')
    expect(compactCount(null)).toBe('—')
    expect(compactCount(undefined)).toBe('—')
  })
})

describe('DestinationMetrics', () => {
  it('lists only the counters this platform reports, with a dash for a missing one', () => {
    render(
      <DestinationMetrics
        metrics={{ targetId: 't', platform: 'facebook', latest: { observedAt: '2026-09-12T10:00:00Z', likes: 84, comments: null, shares: 3 }, series: [] }}
      />
    )
    // Facebook reports no views, so views is not even listed; comments is reported but empty.
    expect(screen.getByTestId('destination-metrics')).toHaveTextContent('84 likes · — comments · 3 shares')
  })

  it('says when the platform no longer returns the post', () => {
    render(<DestinationMetrics metrics={{ targetId: 't', platform: 'tiktok', latest: { observedAt: '2026-09-12T10:00:00Z', unavailable: true }, series: [] }} />)
    expect(screen.getByText('No longer on the platform')).toBeInTheDocument()
  })
})

describe('DestinationTotals', () => {
  it('renders the totals line and the footnote, and nothing when there is neither', () => {
    const { container } = render(<DestinationTotals totals={null} observedAt={null} note={null} />)
    expect(container).toBeEmptyDOMElement()

    render(
      <DestinationTotals
        totals={{ observedAt: '2026-09-12T10:00:00Z', views: 3400, likes: 294, comments: 31, shares: null }}
        observedAt="2026-09-12T10:00:00Z"
        note="Facebook doesn't report views."
      />
    )
    const footer = screen.getByTestId('post-performance')
    expect(footer).toHaveTextContent('All destinations')
    expect(footer).toHaveTextContent('3.4K views · 294 likes · 31 comments · — shares')
    expect(footer).toHaveTextContent("Facebook doesn't report views. A dash means the platform doesn’t report that number.")
  })
})
