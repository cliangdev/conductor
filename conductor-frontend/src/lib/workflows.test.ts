import { describe, it, expect, vi } from 'vitest'
import {
  categoriesForView,
  categoryVariant,
  humanizeId,
  isLifecycleWorkflow,
  pluralizeNoun,
  resolveWorkflowByAreaNoun,
  reviewGateForStatus,
  statusHasReviewGate,
  statusMeta,
  workItemDetailPath,
  workItemListPath,
} from '@/lib/workflows'
import { definitionFromWorkflowView } from '@/lib/workflowDefinition'
import { apiGet } from '@/lib/api'
import type { WorkflowView } from '@/types/workItem'
import type { WorkflowDefinitionDto } from '@/types/workflow'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPut: vi.fn(),
}))

const VIEW: WorkflowView = {
  slug: 'ENGINEERING',
  noun: 'Issue',
  defaultView: 'list',
  version: 3,
  types: ['PRD', 'TASK'],
  assetTypes: ['github_pr'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open', initial: true },
    { id: 'CODE_REVIEW', label: 'Code Review', category: 'in_progress' },
    { id: 'DONE', label: 'Done', category: 'terminal', terminal: true },
  ],
  transitions: [
    { from: 'DRAFT', to: 'CODE_REVIEW', label: 'Start review', requiresReview: true, reviewerRole: 'REVIEWER' },
    { from: 'CODE_REVIEW', to: 'DONE', label: 'Merge' },
  ],
}

describe('humanizeId', () => {
  it('title-cases an UPPER_SNAKE id', () => {
    expect(humanizeId('READY_FOR_DEVELOPMENT')).toBe('Ready For Development')
    expect(humanizeId('DRAFT')).toBe('Draft')
  })
})

describe('categoryVariant', () => {
  it('maps each category to one Badge variant (open→grey, in_progress→blue, terminal→green)', () => {
    expect(categoryVariant('open')).toBe('status-draft')
    expect(categoryVariant('in_progress')).toBe('status-review')
    expect(categoryVariant('terminal')).toBe('status-done')
    expect(categoryVariant('something-else')).toBe('status-draft')
  })
})

describe('statusMeta', () => {
  it('resolves label + category from the view', () => {
    expect(statusMeta(VIEW, 'CODE_REVIEW')).toEqual({ label: 'Code Review', category: 'in_progress' })
  })
  it('falls back to a humanized id + open when unknown or unloaded', () => {
    expect(statusMeta(VIEW, 'SOMETHING_NEW')).toEqual({ label: 'Something New', category: 'open' })
    expect(statusMeta(undefined, 'DRAFT')).toEqual({ label: 'Draft', category: 'open' })
  })
})

describe('categoriesForView', () => {
  it('buckets Active as open+in_progress and Done as terminal', () => {
    expect(categoriesForView('active')).toEqual(['open', 'in_progress'])
    expect(categoriesForView('done')).toEqual(['terminal'])
    expect(categoriesForView('all')).toEqual(['open', 'in_progress', 'terminal'])
  })
})

describe('review gate helpers', () => {
  it('detects an outgoing review-gated transition', () => {
    expect(statusHasReviewGate(VIEW, 'DRAFT')).toBe(true)
    expect(statusHasReviewGate(VIEW, 'CODE_REVIEW')).toBe(false)
    expect(statusHasReviewGate(undefined, 'DRAFT')).toBe(false)
  })
  it('returns the gated transition for a status', () => {
    expect(reviewGateForStatus(VIEW, 'DRAFT')?.to).toBe('CODE_REVIEW')
    expect(reviewGateForStatus(VIEW, 'DONE')).toBeUndefined()
  })
})

describe('definitionFromWorkflowView (clone seed)', () => {
  it('maps statuses/transitions and supplies default outcomes for review-gated edges', () => {
    const def = definitionFromWorkflowView(VIEW)
    expect(def.id).toBe('ENGINEERING_COPY')
    expect(def.state).toBe('DRAFT')
    expect(def.types).toEqual(['PRD', 'TASK'])
    expect(def.statuses.find((s) => s.id === 'DRAFT')?.initial).toBe(true)
    const gated = def.transitions.find((t) => t.from === 'DRAFT')
    expect(gated?.requiresReview).toBe(true)
    expect(gated?.reviewOutcomes).toEqual(['approve', 'request_changes'])
    expect(gated?.reviewerRole).toBe('REVIEWER')
    // Non-gated edges carry no outcomes.
    expect(def.transitions.find((t) => t.from === 'CODE_REVIEW')?.reviewOutcomes).toBeUndefined()
  })
})

describe('pluralizeNoun', () => {
  it('pluralizes simple and irregular nouns', () => {
    expect(pluralizeNoun('Issue')).toBe('Issues')
    expect(pluralizeNoun('Story')).toBe('Stories')
    expect(pluralizeNoun('Deal')).toBe('Deals')
  })
})

describe('workItem URL builders', () => {
  it('builds the list path with both segments lowercased and the noun pluralized', () => {
    expect(workItemListPath('proj-1', 'ENGINEERING', 'Issue')).toBe(
      '/app/projects/proj-1/engineering/issues',
    )
    expect(workItemListPath('proj-1', 'SALES_OPS', 'Deal')).toBe(
      '/app/projects/proj-1/sales_ops/deals',
    )
  })

  it('builds the detail path by appending the displayId', () => {
    expect(workItemDetailPath('proj-1', 'ENGINEERING', 'Issue', 'COND-22')).toBe(
      '/app/projects/proj-1/engineering/issues/COND-22',
    )
  })
})

describe('resolveWorkflowByAreaNoun', () => {
  function wf(overrides: Partial<WorkflowDefinitionDto>): WorkflowDefinitionDto {
    return {
      id: 'wf', projectId: 'p', name: 'ENGINEERING', enabled: true,
      slug: 'ENGINEERING', noun: 'Issue', area: 'ENGINEERING',
      createdAt: '', updatedAt: '', ...overrides,
    }
  }

  it('matches case-insensitively on area and pluralized noun, returning the real slug', async () => {
    (apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([
      wf({}),
      wf({ id: 'wf2', slug: 'SALES', noun: 'Deal', area: 'SALES_OPS' }),
    ])
    // Distinct projectId per assertion to dodge the module-scope cache.
    const eng = await resolveWorkflowByAreaNoun('proj-a', 'engineering', 'issues', 'token')
    expect(eng?.slug).toBe('ENGINEERING')

    const sales = await resolveWorkflowByAreaNoun('proj-b', 'sales_ops', 'deals', 'token')
    expect(sales?.slug).toBe('SALES')
  })

  it('returns undefined when no workflow matches the area/noun pair', async () => {
    (apiGet as ReturnType<typeof vi.fn>).mockResolvedValue([wf({})])
    expect(await resolveWorkflowByAreaNoun('proj-c', 'marketing', 'campaigns', 'token')).toBeUndefined()
  })
})

describe('isLifecycleWorkflow', () => {
  const base: WorkflowDefinitionDto = {
    id: 'wf', projectId: 'p', name: 'X', enabled: true, createdAt: '', updatedAt: '',
  }

  it('is true only when the server-derived kind is LIFECYCLE', () => {
    expect(isLifecycleWorkflow({ ...base, kind: 'LIFECYCLE' })).toBe(true)
    expect(isLifecycleWorkflow({ ...base, kind: 'AUTOMATION' })).toBe(false)
  })

  it('does not infer lifecycle from an empty {} definition (the my-workflow regression)', () => {
    // An automation that leaked definition:{} must not be treated as a lifecycle workflow.
    expect(isLifecycleWorkflow({ ...base, kind: 'AUTOMATION', definition: {} })).toBe(false)
  })
})
