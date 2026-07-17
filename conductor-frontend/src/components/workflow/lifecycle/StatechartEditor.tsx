'use client'

// COND-18: form-based (not drag-canvas) editor for a lifecycle Workflow's statechart. The component
// is controlled — the parent owns the StatechartDefinition and persists it (POST/PUT); the backend
// re-validates on save/publish, so this editor only shapes the document and offers light guidance.

import { cloneElement, isValidElement, useId, useState } from 'react'
import { ChevronRightIcon, PlusIcon, Trash2Icon, XIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Label } from '@/components/ui/label'
import { cn } from '@/lib/utils'
import {
  CATEGORY_OPTIONS,
  DEFAULT_VIEW_OPTIONS,
  METRIC_DIRECTION_OPTIONS,
  REVIEWER_ROLE_OPTIONS,
  REVIEW_OUTCOME_OPTIONS,
  SKILL_OPTIONS,
  STEP_KIND_OPTIONS,
  STEP_MODE_OPTIONS,
  type DefaultView,
  type MetricDirection,
  type ReviewerRole,
  type StatechartDefinition,
  type StatechartStatus,
  type StatechartStep,
  type StatechartTransition,
  type StepKind,
  type StepMode,
} from '@/lib/workflowDefinition'
import type { ReviewOutcome, WorkflowStatusCategory } from '@/types/workItem'

function Section({ title, hint, children }: { title: string; hint?: string; children: React.ReactNode }) {
  return (
    <section className="border border-border rounded-lg p-4 space-y-3">
      <div>
        <h3 className="text-sm font-semibold text-foreground">{title}</h3>
        {hint && <p className="text-xs text-muted-foreground mt-0.5">{hint}</p>}
      </div>
      {children}
    </section>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  const generatedId = useId()
  // Associate the label with its control via htmlFor/id — cloning in an id only when the
  // child (an Input/Select) doesn't already carry one of its own.
  const control = isValidElement<{ id?: string }>(children)
    ? cloneElement(children, { id: children.props.id ?? generatedId })
    : children
  return (
    <div>
      <Label htmlFor={generatedId} className="text-xs font-medium text-muted-foreground mb-1">
        {label}
      </Label>
      {control}
    </div>
  )
}

/** Comma/Enter-separated tag editor for the string-array fields (types, asset_types). */
function TagInput({ values, onChange }: { values: string[]; onChange: (next: string[]) => void }) {
  const [draft, setDraft] = useState('')
  function commit() {
    const v = draft.trim().toUpperCase()
    if (v && !values.includes(v)) onChange([...values, v])
    setDraft('')
  }
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {values.map((v) => (
        <span
          key={v}
          className="inline-flex items-center gap-1 rounded-full border border-border bg-muted px-2 py-0.5 text-xs"
        >
          {v}
          <button
            type="button"
            onClick={() => onChange(values.filter((x) => x !== v))}
            className="text-muted-foreground hover:text-destructive"
            aria-label={`Remove ${v}`}
          >
            <XIcon className="h-3 w-3" />
          </button>
        </span>
      ))}
      <input
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === 'Enter' || e.key === ',') {
            e.preventDefault()
            commit()
          }
        }}
        onBlur={commit}
        placeholder="Add…"
        className="min-w-[80px] flex-1 px-2 py-0.5 text-xs border border-border rounded bg-background focus:outline-none focus:ring-1 focus:ring-ring"
      />
    </div>
  )
}

/** 11.5px caps column-header row, shared by the statuses/transitions/steps tables so their rows all
 *  align under the same grid template. */
function ColumnHeaderRow({ gridCols, columns }: { gridCols: string; columns: string[] }) {
  return (
    <div className={cn('grid gap-2 px-1', gridCols)} aria-hidden="true">
      {columns.map((label, i) => (
        <span key={i} className="text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground truncate">
          {label}
        </span>
      ))}
    </div>
  )
}

/** Icon-only ghost remove action — replaces the old "Remove"/"Remove step" text links. */
function RemoveButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <Button
      type="button"
      variant="ghost"
      size="sm"
      onClick={onClick}
      aria-label={label}
      title={label}
      className="h-8 w-8 p-0 text-muted-foreground hover:text-destructive hover:bg-destructive/10"
    >
      <Trash2Icon className="h-3.5 w-3.5" />
    </Button>
  )
}

/** Outline "+ Add …" action — replaces the old bare "+ Add step" text link. */
function AddButton({ label, onClick }: { label: string; onClick: () => void }) {
  return (
    <Button type="button" variant="outline" size="sm" onClick={onClick} className="gap-1.5">
      <PlusIcon className="h-3.5 w-3.5" />
      {label}
    </Button>
  )
}

const STATUS_GRID = 'grid-cols-[1fr_1fr_9rem_4.5rem_5rem_2.5rem] min-w-[34rem]'
const TRANSITION_GRID = 'grid-cols-[8rem_1.25rem_8rem_1fr_2.5rem] min-w-[26rem]'
const STEP_GRID = 'grid-cols-[7rem_7rem_1fr_2.5rem] min-w-[20rem]'

export function StatechartEditor({
  value,
  onChange,
  creating = false,
}: {
  value: StatechartDefinition
  onChange: (def: StatechartDefinition) => void
  /** When creating, the slug id is editable; once saved it is the stable reference and is locked. */
  creating?: boolean
}) {
  const update = (patch: Partial<StatechartDefinition>) => onChange({ ...value, ...patch })

  // ── Statuses ──────────────────────────────────────────────────────────────
  const updateStatus = (i: number, patch: Partial<StatechartStatus>) =>
    update({ statuses: value.statuses.map((s, idx) => (idx === i ? { ...s, ...patch } : s)) })
  const setInitial = (i: number) =>
    update({ statuses: value.statuses.map((s, idx) => ({ ...s, initial: idx === i })) })
  const addStatus = () =>
    update({ statuses: [...value.statuses, { id: '', category: 'open' }] })
  const removeStatus = (i: number) =>
    update({ statuses: value.statuses.filter((_, idx) => idx !== i) })

  // ── Transitions ───────────────────────────────────────────────────────────
  const updateTransition = (i: number, patch: Partial<StatechartTransition>) =>
    update({ transitions: value.transitions.map((t, idx) => (idx === i ? { ...t, ...patch } : t)) })
  const addTransition = () =>
    update({ transitions: [...value.transitions, { from: '', to: '', label: '' }] })
  const removeTransition = (i: number) =>
    update({ transitions: value.transitions.filter((_, idx) => idx !== i) })

  const toggleReviewOutcome = (i: number, outcome: ReviewOutcome) => {
    const current = value.transitions[i].reviewOutcomes ?? []
    const next = current.includes(outcome)
      ? current.filter((o) => o !== outcome)
      : [...current, outcome]
    updateTransition(i, { reviewOutcomes: next })
  }

  // ── Steps within a transition ───────────────────────────────────────────────
  const updateStep = (ti: number, si: number, patch: Partial<StatechartStep>) => {
    const steps = (value.transitions[ti].steps ?? []).map((s, idx) =>
      idx === si ? { ...s, ...patch } : s,
    )
    updateTransition(ti, { steps })
  }
  const addStep = (ti: number) =>
    updateTransition(ti, {
      steps: [...(value.transitions[ti].steps ?? []), { kind: 'skill', mode: 'BLOCKING' }],
    })
  const removeStep = (ti: number, si: number) =>
    updateTransition(ti, { steps: (value.transitions[ti].steps ?? []).filter((_, idx) => idx !== si) })

  const statusIds = value.statuses.map((s) => s.id).filter(Boolean)

  return (
    <div className="space-y-4">
      {/* ── Meta ── */}
      <Section title="Workflow" hint="Identity and display settings for this Workflow's Work Items.">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <Field label="Slug (UPPER_SNAKE, stable id)">
            <Input
              value={value.id}
              disabled={!creating}
              placeholder="E.G. CONTENT_REVIEW"
              onChange={(e) => update({ id: e.target.value.toUpperCase() })}
            />
          </Field>
          <Field label="Area (nav grouping slug)">
            <Input
              value={value.area}
              placeholder="E.G. MARKETING"
              onChange={(e) => update({ area: e.target.value.toUpperCase() })}
            />
          </Field>
          <Field label="Noun (what a Work Item is called)">
            <Input
              value={value.noun}
              placeholder="E.g. Post"
              onChange={(e) => update({ noun: e.target.value })}
            />
          </Field>
          <Field label="Default view">
            <Select
              value={value.default_view}
              onChange={(e) => update({ default_view: e.target.value as DefaultView })}
            >
              {DEFAULT_VIEW_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </Select>
          </Field>
        </div>

        <Field label="Types (allowed Work Item types)">
          <TagInput values={value.types} onChange={(types) => update({ types })} />
        </Field>
        <Field label="Asset types (produced outputs, e.g. GITHUB_PR)">
          <TagInput
            values={value.asset_types ?? []}
            onChange={(asset_types) => update({ asset_types })}
          />
        </Field>

        {/* Optional metric */}
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={!!value.metric}
            onChange={(e) =>
              update({
                metric: e.target.checked ? { name: '', direction: 'higher_better' } : null,
              })
            }
          />
          Track an outcome metric
        </label>
        {value.metric && (
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 pl-6">
            <Field label="Metric name">
              <Input
                value={value.metric.name}
                onChange={(e) => update({ metric: { ...value.metric!, name: e.target.value } })}
              />
            </Field>
            <Field label="Unit (optional)">
              <Input
                value={value.metric.unit ?? ''}
                onChange={(e) =>
                  update({ metric: { ...value.metric!, unit: e.target.value || undefined } })
                }
              />
            </Field>
            <Field label="Direction">
              <Select
                value={value.metric.direction}
                onChange={(e) =>
                  update({ metric: { ...value.metric!, direction: e.target.value as MetricDirection } })
                }
              >
                {METRIC_DIRECTION_OPTIONS.map((d) => (
                  <option key={d} value={d}>
                    {d.replace(/_/g, ' ')}
                  </option>
                ))}
              </Select>
            </Field>
          </div>
        )}
      </Section>

      {/* ── Statuses ── */}
      <Section
        title="Statuses"
        hint="Exactly one initial, at least one terminal, and every status must reach a terminal."
      >
        <div className="overflow-x-auto">
        <div className="space-y-1.5">
          {value.statuses.length > 0 && (
            <ColumnHeaderRow gridCols={STATUS_GRID} columns={['Status ID', 'Label', 'Category', 'Initial', 'Terminal', '']} />
          )}
          {value.statuses.map((s, i) => (
            <div key={i} className={cn('grid gap-2 items-center', STATUS_GRID)}>
              <Input
                aria-label="Status ID"
                value={s.id}
                placeholder="STATUS_ID"
                className="min-w-0"
                onChange={(e) => updateStatus(i, { id: e.target.value.toUpperCase() })}
              />
              <Input
                aria-label="Label"
                value={s.label ?? ''}
                placeholder="Label"
                className="min-w-0"
                onChange={(e) => updateStatus(i, { label: e.target.value || undefined })}
              />
              <div className="min-w-0">
                <Select
                  aria-label="Category"
                  value={s.category}
                  onChange={(e) =>
                    updateStatus(i, {
                      category: e.target.value as WorkflowStatusCategory,
                      terminal: e.target.value === 'terminal' ? s.terminal : false,
                    })
                  }
                >
                  {CATEGORY_OPTIONS.map((c) => (
                    <option key={c.value} value={c.value}>
                      {c.label}
                    </option>
                  ))}
                </Select>
              </div>
              <div className="flex items-center justify-center" title="Initial status">
                <input
                  type="radio"
                  name="initial-status"
                  aria-label={`Set ${s.id || `status ${i + 1}`} as the initial status`}
                  checked={!!s.initial}
                  onChange={() => setInitial(i)}
                />
              </div>
              <div className="flex items-center justify-center" title="Terminal (end) status">
                <input
                  type="checkbox"
                  aria-label={`Mark ${s.id || `status ${i + 1}`} as terminal`}
                  checked={!!s.terminal}
                  onChange={(e) =>
                    updateStatus(i, {
                      terminal: e.target.checked,
                      category: e.target.checked ? 'terminal' : s.category,
                    })
                  }
                />
              </div>
              <RemoveButton label={`Remove status ${s.id || i + 1}`} onClick={() => removeStatus(i)} />
            </div>
          ))}
        </div>
        </div>
        <AddButton label="Add status" onClick={addStatus} />
      </Section>

      {/* ── Transitions ── */}
      <Section
        title="Transitions"
        hint="Allowed moves between statuses. ≤ 5 per source status; ≤ 3 may require review."
      >
        <div className="space-y-3">
          {value.transitions.map((t, i) => (
            <div key={i} className="border border-border rounded-md p-3 space-y-2.5 overflow-x-auto">
              <ColumnHeaderRow gridCols={TRANSITION_GRID} columns={['From', '', 'To', 'Label', '']} />
              <div className={cn('grid gap-2 items-center', TRANSITION_GRID)}>
                <div className="min-w-0">
                  <Select
                    aria-label="From status"
                    value={t.from}
                    onChange={(e) => updateTransition(i, { from: e.target.value })}
                  >
                    <option value="">from…</option>
                    {statusIds.map((id) => (
                      <option key={id} value={id}>
                        {id}
                      </option>
                    ))}
                  </Select>
                </div>
                <ChevronRightIcon className="h-3.5 w-3.5 text-muted-foreground shrink-0 justify-self-center" />
                <div className="min-w-0">
                  <Select
                    aria-label="To status"
                    value={t.to}
                    onChange={(e) => updateTransition(i, { to: e.target.value })}
                  >
                    <option value="">to…</option>
                    {statusIds.map((id) => (
                      <option key={id} value={id}>
                        {id}
                      </option>
                    ))}
                  </Select>
                </div>
                <Input
                  aria-label="Action label"
                  value={t.label}
                  placeholder="Action label (e.g. Submit for review)"
                  className="min-w-0"
                  onChange={(e) => updateTransition(i, { label: e.target.value })}
                />
                <RemoveButton label={`Remove transition ${i + 1}`} onClick={() => removeTransition(i)} />
              </div>

              <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
                <label className="flex items-center gap-1.5 text-xs">
                  <input
                    type="checkbox"
                    checked={!!t.requiresReview}
                    onChange={(e) =>
                      updateTransition(i, {
                        requiresReview: e.target.checked,
                        reviewOutcomes: e.target.checked
                          ? t.reviewOutcomes ?? ['approve', 'request_changes']
                          : undefined,
                      })
                    }
                  />
                  Requires review
                </label>
                <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
                  Trigger
                  <Select
                    className="w-auto"
                    value={t.trigger ?? ''}
                    onChange={(e) =>
                      updateTransition(i, {
                        trigger: e.target.value === 'pr_merged' ? 'pr_merged' : undefined,
                      })
                    }
                  >
                    <option value="">none (human-driven)</option>
                    <option value="pr_merged">pr_merged</option>
                  </Select>
                </label>
              </div>

              {t.requiresReview && (
                <div className="flex flex-wrap items-center gap-x-4 gap-y-2 pl-4 border-l-2 border-border">
                  <div className="flex items-center gap-2 text-xs">
                    <span className="text-muted-foreground">Outcomes:</span>
                    {REVIEW_OUTCOME_OPTIONS.map((o) => (
                      <label key={o.value} className="flex items-center gap-1">
                        <input
                          type="checkbox"
                          checked={(t.reviewOutcomes ?? []).includes(o.value)}
                          onChange={() => toggleReviewOutcome(i, o.value)}
                        />
                        {o.label}
                      </label>
                    ))}
                  </div>
                  <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
                    Reviewer role
                    <Select
                      className="w-auto"
                      value={t.reviewerRole ?? ''}
                      onChange={(e) =>
                        updateTransition(i, {
                          reviewerRole: (e.target.value || undefined) as ReviewerRole | undefined,
                        })
                      }
                    >
                      <option value="">any</option>
                      {REVIEWER_ROLE_OPTIONS.map((r) => (
                        <option key={r} value={r}>
                          {r}
                        </option>
                      ))}
                    </Select>
                  </label>
                </div>
              )}

              {/* Steps */}
              <div className="space-y-1.5 pl-4 border-l-2 border-border">
                {(t.steps ?? []).length > 0 && (
                  <ColumnHeaderRow gridCols={STEP_GRID} columns={['Kind', 'Mode', 'Skill', '']} />
                )}
                {(t.steps ?? []).map((step, si) => (
                  <div key={si} className={cn('grid gap-2 items-center', STEP_GRID)}>
                    <div className="min-w-0">
                      <Select
                        aria-label="Step kind"
                        value={step.kind}
                        onChange={(e) => updateStep(i, si, { kind: e.target.value as StepKind })}
                      >
                        {STEP_KIND_OPTIONS.map((k) => (
                          <option key={k} value={k}>
                            {k}
                          </option>
                        ))}
                      </Select>
                    </div>
                    <div className="min-w-0">
                      <Select
                        aria-label="Step mode"
                        value={step.mode}
                        onChange={(e) => updateStep(i, si, { mode: e.target.value as StepMode })}
                      >
                        {STEP_MODE_OPTIONS.map((m) => (
                          <option key={m} value={m}>
                            {m}
                          </option>
                        ))}
                      </Select>
                    </div>
                    {step.kind === 'skill' ? (
                      <div className="min-w-0">
                        <Select
                          aria-label="Skill"
                          value={step.skill ?? ''}
                          onChange={(e) => updateStep(i, si, { skill: e.target.value || undefined })}
                        >
                          <option value="">pick a skill…</option>
                          {SKILL_OPTIONS.map((s) => (
                            <option key={s} value={s}>
                              {s}
                            </option>
                          ))}
                        </Select>
                      </div>
                    ) : (
                      <span aria-hidden="true" />
                    )}
                    <RemoveButton label={`Remove step ${si + 1}`} onClick={() => removeStep(i, si)} />
                  </div>
                ))}
                <AddButton label="Add step" onClick={() => addStep(i)} />
              </div>
            </div>
          ))}
        </div>
        <AddButton label="Add transition" onClick={addTransition} />
      </Section>
    </div>
  )
}
