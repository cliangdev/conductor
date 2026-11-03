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

  it('interleaves slide-text changes with reviews, newest first, saying what they did to the round', () => {
    render(
      <ActivityTab
        comments={[]}
        reviews={[{ reviewerId: 'u1', name: 'Rita Reviewer', verdict: 'APPROVED', submittedAt: '2026-09-10T05:00:00Z' }]}
        contentChanges={[
          {
            id: 'c2', creativeId: 'cr', creativeDisplayId: '158a', revisionId: 'r2', source: 'REVIEWER', actorName: 'Rita Reviewer',
            reviewRoundBefore: 2, reviewRoundAfter: 2, createdAt: '2026-09-10T08:00:00Z',
            textDiff: [{ slide: 1, path: 'texts[0].t', old: 'Thanksgving', new: 'Thanksgiving' }],
          },
          {
            id: 'c1', creativeId: 'cr', creativeDisplayId: '158a', revisionId: 'r1', source: 'EDITOR', actorName: 'Ada Admin',
            reviewRoundBefore: 1, reviewRoundAfter: 2, createdAt: '2026-09-10T06:00:00Z',
            textDiff: [{ slide: 3, path: 'bubbles[0].t', old: 'Hi', new: 'Hello' }],
          },
        ]}
      />
    )
    const entries = screen.getAllByTestId(/^activity-/)
    expect(entries.map((e) => e.getAttribute('data-testid'))).toEqual(['activity-content-c2', 'activity-content-c1', 'activity-review-u1-2026-09-10T05:00:00Z'])
    expect(entries[0]).toHaveTextContent('changed the slide text on 158a · fixed during review round 2')
    expect(entries[0]).toHaveTextContent('Slide 1 · Text 1')
    expect(entries[1]).toHaveTextContent('opened review round 2')
    expect(entries[1]).toHaveTextContent('Slide 3 · Bubble 1')
  })
})
