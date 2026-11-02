import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'

const { roadmapBucketSpy, nextOpenSpy } = vi.hoisted(() => ({ roadmapBucketSpy: vi.fn(), nextOpenSpy: vi.fn() }))
vi.mock('@/lib/roadmap', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/roadmap')>()
  return {
    ...actual,
    roadmapBucket: (...args: Parameters<typeof actual.roadmapBucket>) => {
      roadmapBucketSpy(...args)
      return actual.roadmapBucket(...args)
    },
    nextOpenMilestoneId: (...args: Parameters<typeof actual.nextOpenMilestoneId>) => {
      nextOpenSpy(...args)
      return actual.nextOpenMilestoneId(...args)
    },
  }
})

import { NowNextLaterBoard } from './NowNextLaterBoard'
import type { Issue } from '@/components/workitems/listTypes'
import type { Milestone } from '@/types/workItem'

const milestones: Milestone[] = [
  { id: 'm1', projectId: 'p', name: 'Beta', targetOn: '2099-01-01', state: 'OPEN', sortOrder: 0, createdAt: '', updatedAt: '' },
]
const items = [1, 2, 3].map((n) => ({
  id: `i${n}`, displayId: `PRD-${n}`, title: `Item ${n}`, status: 'DRAFT', type: 'PRD', workflow: 'PRDS',
  milestoneId: 'm1', createdAt: '', updatedAt: '',
})) as unknown as Issue[]
const categoryOf = () => 'open'
const hrefOf = () => null

describe('NowNextLaterBoard', () => {
  it('buckets once per item and finds the next milestone once, even across re-renders', () => {
    const { rerender } = render(<NowNextLaterBoard items={items} milestones={milestones} categoryOf={categoryOf} hrefOf={hrefOf} />)
    expect(screen.getByText('Item 1')).toBeInTheDocument()
    expect(roadmapBucketSpy).toHaveBeenCalledTimes(3)
    expect(nextOpenSpy).toHaveBeenCalledTimes(1)

    rerender(<NowNextLaterBoard items={items} milestones={milestones} categoryOf={categoryOf} hrefOf={hrefOf} />)
    expect(roadmapBucketSpy).toHaveBeenCalledTimes(3)
    expect(nextOpenSpy).toHaveBeenCalledTimes(1)
  })
})
