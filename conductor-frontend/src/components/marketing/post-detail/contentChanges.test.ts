import { describe, it, expect } from 'vitest'
import { changedSinceLastReview, type PostContentChange } from './contentChanges'

const change = (over: Partial<PostContentChange> = {}): PostContentChange => ({
  id: 'c1',
  creativeId: 'cr',
  revisionId: 'r1',
  source: 'EDITOR',
  reviewRoundBefore: 1,
  reviewRoundAfter: 2,
  textDiff: [],
  createdAt: '2026-11-02T10:00:00Z',
  ...over,
})

describe('changedSinceLastReview', () => {
  it('is the newest change when it opened the Post’s current round', () => {
    const newest = change({ id: 'new' })
    expect(changedSinceLastReview([newest, change({ id: 'old', reviewRoundAfter: 1 })], 2)).toBe(newest)
  })

  it('is nothing once the Post has moved on to a later round', () => {
    expect(changedSinceLastReview([change()], 3)).toBeNull()
  })

  it('counts a reviewer’s fix inside the current round', () => {
    const fix = change({ source: 'REVIEWER', reviewRoundBefore: 2, reviewRoundAfter: 2 })
    expect(changedSinceLastReview([fix], 2)).toBe(fix)
  })

  it('ignores a change made while the Post was a draft', () => {
    expect(changedSinceLastReview([change({ reviewRoundBefore: 0, reviewRoundAfter: 0 })], 0)).toBeNull()
  })

  it('is nothing with no changes', () => {
    expect(changedSinceLastReview([], 2)).toBeNull()
  })

  it('without the Post’s round (older server), holds until a review is submitted after the change', () => {
    const c = change()
    expect(changedSinceLastReview([c], undefined, [])).toBe(c)
    expect(changedSinceLastReview([c], null, [{ submittedAt: '2026-11-02T09:00:00Z' }])).toBe(c)
    expect(changedSinceLastReview([c], null, [{ submittedAt: '2026-11-02T11:00:00Z' }])).toBeNull()
  })
})
