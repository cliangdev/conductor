import { describe, it, expect } from 'vitest'
import {
  categoriesForView,
  categoryVariant,
  humanizeId,
  reviewGateForStatus,
  statusHasReviewGate,
  statusMeta,
} from '@/lib/workflows'
import { definitionFromWorkflowView } from '@/lib/workflowDefinition'
import type { WorkflowView } from '@/types/workItem'

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
