import { describe, expect, it } from 'vitest'
import { applyTagChange, hasFailedTarget, isEditLocked, isLivePost, isRescheduleLocked, parseTags, shiftSchedule, tagsDiffer } from './postBulk'

describe('isLivePost', () => {
  it('is live with a published destination, a live link or a Published status', () => {
    expect(isLivePost({ status: 'SCHEDULED', publishTargets: [{ platform: 'tiktok', state: 'PUBLISHED' }] })).toBe(true)
    expect(isLivePost({ status: 'SCHEDULED', externalLinks: [{ url: 'https://x', type: 'instagram_post' }] })).toBe(true)
    expect(isLivePost({ status: 'published' })).toBe(true)
  })
  it('is not live while waiting, failed or draft', () => {
    expect(
      isLivePost({
        status: 'SCHEDULED',
        publishTargets: [
          { platform: 'tiktok', state: 'PENDING' },
          { platform: 'instagram', state: 'FAILED' },
        ],
      }),
    ).toBe(false)
    expect(isLivePost({ status: 'DRAFT' })).toBe(false)
  })
})

describe('hasFailedTarget', () => {
  it('detects a failed destination only', () => {
    expect(hasFailedTarget({ publishTargets: [{ platform: 'tiktok', state: 'FAILED' }] })).toBe(true)
    expect(hasFailedTarget({ publishTargets: [{ platform: 'tiktok', state: 'PUBLISHED' }] })).toBe(false)
    expect(hasFailedTarget({})).toBe(false)
  })
})

describe('shiftSchedule', () => {
  it('moves by calendar days', () => {
    expect(shiftSchedule('2026-03-04T15:00:00.000Z', 'UTC', 3)).toBe('2026-03-07T15:00:00.000Z')
    expect(shiftSchedule('2026-03-04T15:00:00.000Z', 'UTC', -4)).toBe('2026-02-28T15:00:00.000Z')
  })
  it('keeps the wall-clock time across a DST change', () => {
    // 9:00 New York on Sat 7 Mar 2026 (EST, UTC-5); +2 days is after the 8 Mar spring-forward (EDT, UTC-4).
    expect(shiftSchedule('2026-03-07T14:00:00.000Z', 'America/New_York', 2)).toBe('2026-03-09T13:00:00.000Z')
  })
  it('returns null when there is no schedule', () => {
    expect(shiftSchedule(null, 'UTC', 1)).toBeNull()
  })
})

describe('tags', () => {
  it('parses, lower-cases and de-duplicates', () => {
    expect(parseTags(' Autumn, launch ,autumn,,\nPaid')).toEqual(['autumn', 'launch', 'paid'])
  })
  it('adds without duplicating and removes only what is present', () => {
    expect(applyTagChange(['a'], ['a', 'b'], 'add')).toEqual(['a', 'b'])
    expect(applyTagChange(['a', 'b'], ['b', 'z'], 'remove')).toEqual(['a'])
  })
  it('returns the same array when nothing changes', () => {
    const cur = ['a']
    expect(applyTagChange(cur, ['a'], 'add')).toBe(cur)
    expect(applyTagChange(cur, ['z'], 'remove')).toBe(cur)
  })
})

describe('isEditLocked', () => {
  it('locks live, approved, scheduled and handed-off posts', () => {
    expect(isEditLocked({ status: 'APPROVED' })).toBe(true)
    expect(isEditLocked({ status: 'SCHEDULED' })).toBe(true)
    expect(isEditLocked({ status: 'DRAFT', publishTargets: [{ platform: 'tiktok', state: 'HANDED_OFF' }] })).toBe(true)
    expect(isEditLocked({ status: 'DRAFT', publishTargets: [{ platform: 'tiktok', state: 'PUBLISHED' }] })).toBe(true)
  })
  it('leaves drafts, in-review, changes-requested and failed posts editable', () => {
    for (const status of ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'FAILED']) {
      expect(isEditLocked({ status, publishTargets: [{ platform: 'tiktok', state: 'PENDING' }] })).toBe(false)
    }
  })
})

describe('tagsDiffer', () => {
  it('compares contents, so an unchanged or missing list is not a change', () => {
    expect(tagsDiffer(undefined, ['a'], 'remove')).toBe(false)
    expect(tagsDiffer([], ['a'], 'remove')).toBe(false)
    expect(tagsDiffer(['a'], ['a'], 'add')).toBe(false)
    expect(tagsDiffer(undefined, ['a'], 'add')).toBe(true)
    expect(tagsDiffer(['a', 'b'], ['b'], 'remove')).toBe(true)
  })
})

describe('isRescheduleLocked', () => {
  it('only locks live posts, matching the single-post schedule rule', () => {
    for (const status of ['DRAFT', 'IN_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'SCHEDULED', 'FAILED']) {
      expect(isRescheduleLocked({ status })).toBe(false)
    }
    expect(isRescheduleLocked({ status: 'DRAFT', publishTargets: [{ platform: 'tiktok', state: 'HANDED_OFF' }] })).toBe(false)
    expect(isRescheduleLocked({ status: 'PUBLISHED' })).toBe(true)
    expect(isRescheduleLocked({ status: 'DRAFT', publishTargets: [{ platform: 'tiktok', state: 'PUBLISHED' }] })).toBe(true)
  })
})
