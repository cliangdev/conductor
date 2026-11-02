import { describe, it, expect } from 'vitest'
import type { Milestone } from '@/types/workItem'
import { milestoneBarSpan, nextOpenMilestoneId, roadmapBucket, type RoadmapItem } from './roadmap'

const today = new Date(2026, 10, 1) // 1 Nov 2026

function ms(id: string, targetOn: string | null, state: 'OPEN' | 'CLOSED' = 'OPEN'): Milestone {
  return { id, projectId: 'p', name: id, targetOn, state, sortOrder: 0, createdAt: '', updatedAt: '' }
}
const milestones = { soon: ms('soon', '2026-11-10'), later: ms('later', '2027-03-01'), mid: ms('mid', '2026-12-20') }

function item(over: Partial<RoadmapItem> = {}): RoadmapItem {
  return { status: 'DRAFT', createdAt: '2026-10-01T00:00:00Z', ...over }
}
function sig(ready: boolean) {
  return { blocked: false, blockedBy: [], ready, stale: false, needsInput: false }
}
const cat = (c: string) => () => c

describe('roadmapBucket', () => {
  it('puts in-progress items in Now', () => {
    expect(roadmapBucket(item(), {}, today, cat('in_progress'))).toBe('now')
  })
  it('excludes terminal items', () => {
    expect(roadmapBucket(item({ priority: 'P0' }), milestones, today, cat('terminal'))).toBeNull()
  })
  it('puts items whose milestone is due within 14 days in Now', () => {
    expect(roadmapBucket(item({ milestoneId: 'soon' }), milestones, today, cat('open'))).toBe('now')
  })
  it('treats an overdue milestone as Now', () => {
    const m = { old: ms('old', '2026-10-01') }
    expect(roadmapBucket(item({ milestoneId: 'old' }), m, today, cat('open'))).toBe('now')
  })
  it('puts a ready P0 item in Next', () => {
    expect(roadmapBucket(item({ priority: 'P0', signals: sig(true) }), {}, today, cat('open'))).toBe('next')
  })
  it('puts a ready item in the next open milestone in Next', () => {
    const m = { mid: milestones.mid, later: milestones.later }
    expect(roadmapBucket(item({ milestoneId: 'mid', signals: sig(true) }), m, today, cat('open'))).toBe('next')
    expect(roadmapBucket(item({ milestoneId: 'later', signals: sig(true) }), m, today, cat('open'))).toBe('later')
  })
  it('keeps a ready P2 item with no milestone in Later', () => {
    expect(roadmapBucket(item({ priority: 'P2', signals: sig(true) }), {}, today, cat('open'))).toBe('later')
  })
  it('keeps a P0 item that is not ready in Later', () => {
    expect(roadmapBucket(item({ priority: 'P0', signals: sig(false) }), {}, today, cat('open'))).toBe('later')
  })
  it('puts an item with no milestone and no signals in Later', () => {
    expect(roadmapBucket(item(), milestones, today, cat('open'))).toBe('later')
  })
})

describe('nextOpenMilestoneId', () => {
  it('skips closed and past milestones, falling back to the earliest when all are past', () => {
    expect(nextOpenMilestoneId({ a: ms('a', '2026-10-01'), b: ms('b', '2026-12-01'), c: ms('c', '2026-11-05', 'CLOSED') }, today)).toBe('b')
    expect(nextOpenMilestoneId({ a: ms('a', '2026-09-01'), b: ms('b', '2026-10-01') }, today)).toBe('a')
    expect(nextOpenMilestoneId({ a: ms('a', null) }, today)).toBeNull()
  })
})

describe('milestoneBarSpan', () => {
  const t = (iso: string) => Date.parse(iso)
  it('runs from startedAt to completedAt', () => {
    const span = milestoneBarSpan(item({ startedAt: '2026-10-05T00:00:00Z', completedAt: '2026-10-20T00:00:00Z' }), null, today)
    expect(span).toEqual({ startMs: t('2026-10-05T00:00:00Z'), endMs: t('2026-10-20T00:00:00Z') })
  })
  it('falls back to createdAt and the appetite, then the milestone target, then today', () => {
    const withAppetite = milestoneBarSpan(item({ startedAt: '2026-10-05T00:00:00Z', appetiteDays: 14 }), milestones.soon, today)
    expect(withAppetite.endMs).toBe(t('2026-10-19T00:00:00Z'))
    const byMilestone = milestoneBarSpan(item(), milestones.mid, today)
    expect(byMilestone.startMs).toBe(t('2026-10-01T00:00:00Z'))
    expect(byMilestone.endMs).toBe(new Date(2026, 11, 20).getTime())
    expect(milestoneBarSpan(item(), null, today).endMs).toBe(today.getTime())
  })
  it('clamps the end so it is never before the start', () => {
    const span = milestoneBarSpan(item({ startedAt: '2026-10-05T00:00:00Z', completedAt: '2026-10-01T00:00:00Z' }), null, today)
    expect(span.endMs).toBe(span.startMs)
    const early = milestoneBarSpan(item({ createdAt: '2026-12-31T00:00:00Z' }), milestones.soon, today)
    expect(early.endMs).toBe(early.startMs)
  })
})
