import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ActivityTab } from './ActivityTab'

describe('ActivityTab', () => {
  it('says what a review did, and shows the reason under it', () => {
    render(
      <ActivityTab
        comments={[]}
        reviews={[
          { reviewerId: 'u1', name: 'Rita Reviewer', verdict: 'CHANGES_REQUESTED', body: 'The caption still says Tiktok.', submittedAt: '2026-09-10T05:00:00Z' },
          { reviewerId: 'u2', name: 'Ada Admin', verdict: 'APPROVED', submittedAt: '2026-09-10T06:00:00Z' },
        ]}
      />
    )
    expect(screen.getByText('requested changes')).toBeInTheDocument()
    expect(screen.getByText('The caption still says Tiktok.')).toBeInTheDocument()
    expect(screen.getByText('approved')).toBeInTheDocument()
  })
})
