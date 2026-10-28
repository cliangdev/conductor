// Render cost of a few hundred Posts: the plain row (what the list drew before), the enriched Post row, and
// the grid. Prints the numbers (see the PR); asserts only a generous bound, since CI timing is no contract.
// Decision input for server-side paging: if 300 rows rendered too slowly, paging would be worth building.

import { describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }), usePathname: () => '/', useSearchParams: () => new URLSearchParams() }))
vi.mock('@/lib/api', () => ({
  apiGet: vi.fn().mockResolvedValue([]),
  apiPatch: vi.fn(),
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_e: unknown, f: string) => f,
}))

import { WorkItemGridView } from './WorkItemGridView'
import { WorkItemGroup } from './WorkItemGroup'
import type { IssueWithReviewers, WorkItemGroupData } from './listTypes'

const N = 300

function issues(enriched: boolean): IssueWithReviewers[] {
  return Array.from({ length: N }, (_, i) => ({
    id: `i${i}`,
    title: `Post number ${i}`,
    type: 'POST',
    status: 'SCHEDULED',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
    displayId: `RE-${i}`,
    scheduledFor: '2026-03-04T15:00:00Z',
    scheduleTimezone: 'UTC',
    tags: ['autumn'],
    reviewers: [],
    ...(enriched
      ? {
          thumbnailUrl: `https://storage.example/t${i}.jpg?sig=${'x'.repeat(300)}`,
          publishTargets: [
            { platform: 'instagram', state: 'PENDING' },
            { platform: 'tiktok', state: i % 20 === 0 ? 'FAILED' : 'PENDING' },
          ],
          creative: { id: 'c1', displayId: '14a', name: 'Pasta' },
        }
      : {}),
  }))
}

function timeRender(ui: () => React.ReactElement): number {
  const runs: number[] = []
  for (let i = 0; i < 3; i++) {
    const t = performance.now()
    const { unmount } = render(ui())
    runs.push(performance.now() - t)
    unmount()
  }
  runs.sort((a, b) => a - b)
  return runs[1]!
}

function group(items: IssueWithReviewers[]): WorkItemGroupData {
  return { statusId: 'SCHEDULED', label: 'Scheduled', category: 'in_progress', items }
}

const rowProps = {
  projectId: 'p',
  slug: 'MARKETING',
  detailArea: 'marketing',
  noun: 'Post',
  userRole: 'ADMIN' as const,
  accessToken: 't',
  members: [],
  canEdit: true,
  selectedIds: new Set<string>(),
  tabStopId: null,
  onStatusChanged: vi.fn(),
  onAssigneeChanged: vi.fn(),
  onReviewersChanged: vi.fn(),
  onRowFocus: vi.fn(),
  registerStatusTriggerRef: () => () => {},
  registerAssigneeTriggerRef: () => () => {},
  registerRowLinkRef: () => () => {},
}

describe('render cost of 300 Posts', () => {
  it('stays well inside a frame budget multiple for list and grid', () => {
    const plain = issues(false)
    const rich = issues(true)
    const before = timeRender(() => <WorkItemGroup group={group(plain)} {...rowProps} />)
    const after = timeRender(() => <WorkItemGroup group={group(rich)} {...rowProps} publishing />)
    const grid = timeRender(() => (
      <WorkItemGridView
        issues={rich}
        projectId="p"
        detailArea="marketing"
        noun="Post"
        workflowView={undefined}
        canEdit
        selectedIds={new Set()}
        onToggleSelect={() => {}}
      />
    ))
    console.log(
      `RENDER ${N} rows: plain list ${before.toFixed(0)} ms, enriched list ${after.toFixed(0)} ms, grid ${grid.toFixed(0)} ms`,
    )
    expect(after).toBeLessThan(15000)
    expect(grid).toBeLessThan(15000)
  }, 60_000)
})
