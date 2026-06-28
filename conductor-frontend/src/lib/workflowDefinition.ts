// COND-18: TypeScript mirror of the statechart Workflow definition (schemaVersion 1).
//
// Authoritative shape: conductor-backend/src/main/resources/schema/workflow-definition-v1.schema.json,
// validated in Java by WorkflowDefinitionValidator. The form-based editor reads/writes THIS shape and
// the backend re-validates on save/publish — so this file only models the structure and provides
// seeds/clones; it does not re-implement the semantic rules.

import type { ReviewOutcome, WorkflowStatusCategory, WorkflowView } from '@/types/workItem'

export type DefaultView = 'list' | 'board' | 'calendar'
export type ReviewerRole = 'ADMIN' | 'CREATOR' | 'REVIEWER'
export type StepKind = 'skill' | 'http' | 'notify' | 'set_field' | 'create_sub_items'
export type StepMode = 'BLOCKING' | 'ASYNC'
export type TransitionTrigger = 'pr_merged'
export type MetricDirection = 'higher_better' | 'lower_better'

export interface StatechartStatus {
  id: string
  label?: string
  category: WorkflowStatusCategory
  initial?: boolean
  terminal?: boolean
}

export interface StatechartStep {
  kind: StepKind
  mode: StepMode
  skill?: string
  config?: Record<string, unknown>
}

export interface StatechartTransition {
  from: string
  to: string
  label: string
  requiresReview?: boolean
  reviewOutcomes?: ReviewOutcome[]
  reviewerRole?: ReviewerRole
  trigger?: TransitionTrigger
  steps?: StatechartStep[]
}

export interface StatechartMetric {
  name: string
  unit?: string
  direction: MetricDirection
}

export interface StatechartDefinition {
  schemaVersion: 1
  id: string
  area: string
  version: number
  state: 'DRAFT' | 'PUBLISHED'
  noun: string
  default_view: DefaultView
  types: string[]
  asset_types?: string[]
  metric?: StatechartMetric | null
  statuses: StatechartStatus[]
  transitions: StatechartTransition[]
}

// ── Editor option lists ─────────────────────────────────────────────────────

export const CATEGORY_OPTIONS: { value: WorkflowStatusCategory; label: string }[] = [
  { value: 'open', label: 'Open' },
  { value: 'in_progress', label: 'In progress' },
  { value: 'terminal', label: 'Terminal' },
]

export const DEFAULT_VIEW_OPTIONS: { value: DefaultView; label: string }[] = [
  { value: 'list', label: 'List' },
  { value: 'board', label: 'Board' },
  { value: 'calendar', label: 'Calendar' },
]

export const REVIEWER_ROLE_OPTIONS: ReviewerRole[] = ['ADMIN', 'CREATOR', 'REVIEWER']

export const REVIEW_OUTCOME_OPTIONS: { value: ReviewOutcome; label: string }[] = [
  { value: 'approve', label: 'Approve' },
  { value: 'request_changes', label: 'Request changes' },
  { value: 'comment', label: 'Comment' },
]

export const STEP_KIND_OPTIONS: StepKind[] = ['skill', 'http', 'notify', 'set_field', 'create_sub_items']
export const STEP_MODE_OPTIONS: StepMode[] = ['BLOCKING', 'ASYNC']
export const METRIC_DIRECTION_OPTIONS: MetricDirection[] = ['higher_better', 'lower_better']

/** Skills bindable on a skill step. Must match the backend skill registry (AC-P0-2.5). */
export const SKILL_OPTIONS: string[] = ['conductor:prd', 'conductor:implement']

// ── Seeds ───────────────────────────────────────────────────────────────────

/** A minimal, near-valid starting definition for a brand-new lifecycle Workflow. */
export function emptyDefinition(): StatechartDefinition {
  return {
    schemaVersion: 1,
    id: '',
    area: '',
    version: 1,
    state: 'DRAFT',
    noun: 'Work Item',
    default_view: 'list',
    types: ['TASK'],
    asset_types: [],
    metric: null,
    statuses: [
      { id: 'TODO', label: 'To Do', category: 'open', initial: true },
      { id: 'IN_PROGRESS', label: 'In Progress', category: 'in_progress' },
      { id: 'DONE', label: 'Done', category: 'terminal', terminal: true },
    ],
    transitions: [
      { from: 'TODO', to: 'IN_PROGRESS', label: 'Start' },
      { from: 'IN_PROGRESS', to: 'DONE', label: 'Complete' },
    ],
  }
}

/**
 * Build an editable DRAFT definition from a built-in's WorkflowView so users can clone it as a
 * template. The lean view omits step/trigger detail and review outcomes, so review-gated edges get a
 * sensible default outcome set (the schema requires >= 2 when requiresReview).
 */
export function definitionFromWorkflowView(view: WorkflowView): StatechartDefinition {
  return {
    schemaVersion: 1,
    id: `${view.slug}_COPY`,
    area: view.slug,
    version: 1,
    state: 'DRAFT',
    noun: view.noun || 'Work Item',
    default_view: (['list', 'board', 'calendar'].includes(view.defaultView)
      ? view.defaultView
      : 'list') as DefaultView,
    types: view.types.length ? [...view.types] : ['TASK'],
    asset_types: view.assetTypes ? [...view.assetTypes] : [],
    metric: view.metric
      ? {
          name: view.metric.name,
          unit: view.metric.unit ?? undefined,
          direction: (view.metric.direction === 'lower_better'
            ? 'lower_better'
            : 'higher_better') as MetricDirection,
        }
      : null,
    statuses: view.statuses.map((s) => ({
      id: s.id,
      label: s.label,
      category: (s.category as WorkflowStatusCategory) ?? 'open',
      initial: s.initial,
      terminal: s.terminal,
    })),
    transitions: view.transitions.map((t) => ({
      from: t.from,
      to: t.to,
      label: t.label,
      requiresReview: t.requiresReview,
      reviewOutcomes: t.requiresReview
        ? (t.reviewOutcomes ?? ['approve', 'request_changes'])
        : undefined,
      reviewerRole: (t.reviewerRole as ReviewerRole) ?? undefined,
      trigger: t.trigger === 'pr_merged' ? 'pr_merged' : undefined,
    })),
  }
}

/** Whether a WorkflowDefinitionDto is a lifecycle (statechart) Workflow vs a YAML automation. */
export function isLifecycleDefinition(definition: unknown): definition is StatechartDefinition {
  return !!definition && typeof definition === 'object'
}
