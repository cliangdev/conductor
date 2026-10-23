'use client'

// The typed fields a Workflow declares on its Work Items, and the item's due date.
//
// Generic by construction: the keys, labels, types and the statuses that require them all come from the
// bound Workflow's definition, so a lifecycle gains structured data by declaring `fields` — not by a new
// component. Each row edits in place and saves on its own (a partial PATCH merged key by key on the
// server), so filling in one never rewrites the others.
//
// A move the Workflow refuses for want of a required field names the missing keys back (`missing`), and
// the matching rows are marked here so the person sees what to fill in without reading an error.

import { useState } from 'react'
import { Pencil } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardHeader } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiPatch } from '@/lib/api'
import type { WorkflowFieldView, WorkflowView } from '@/types/workItem'

export interface WorkItemFieldsCardProps {
  projectId: string
  workItemId: string
  token: string
  workflowView: WorkflowView
  status: string
  fields?: Record<string, unknown> | null
  dueOn?: string | null
  canEdit: boolean
  /** Keys of required fields a refused status move reported empty; those rows are marked. */
  missing?: string[]
  onSaved: (patch: { fields?: Record<string, unknown> | null; dueOn?: string | null }) => void
}

/** "did_not_work" -> "Did not work": option values are keys, shown as words. */
export function optionLabel(value: string): string {
  const spaced = value.replace(/_/g, ' ').trim()
  return spaced.charAt(0).toUpperCase() + spaced.slice(1)
}

/** A `yyyy-MM-dd` date as a readable day, zone-free so it never shifts by a timezone. */
export function formatDay(value: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value)
  if (!m) return value
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'UTC',
  }).format(new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]))))
}

function todayKey(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function isEmpty(value: unknown): boolean {
  return value === undefined || value === null || (typeof value === 'string' && value.trim() === '')
}

function FieldValue({ field, value }: { field: WorkflowFieldView; value: unknown }) {
  if (isEmpty(value)) return <span className="text-sm text-muted-foreground">Not set</span>
  const text = String(value)
  switch (field.type) {
    case 'longtext':
      return <p className="whitespace-pre-wrap text-sm leading-relaxed text-foreground">{text}</p>
    case 'date':
      return <span className="text-sm text-foreground">{formatDay(text)}</span>
    case 'enum':
      return <span className="text-sm text-foreground">{optionLabel(text)}</span>
    case 'url':
      return (
        <a
          href={text}
          target="_blank"
          rel="noopener noreferrer"
          className="break-all text-sm text-primary hover:underline"
        >
          {text}
        </a>
      )
    default:
      return <span className="text-sm text-foreground">{text}</span>
  }
}

function FieldInput({
  id,
  field,
  value,
  onChange,
}: {
  id: string
  field: WorkflowFieldView
  value: string
  onChange: (value: string) => void
}) {
  switch (field.type) {
    case 'longtext':
      return (
        <textarea
          id={id}
          autoFocus
          rows={5}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          className="w-full rounded-md border border-border bg-background px-2.5 py-1.5 text-sm text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring"
        />
      )
    case 'enum':
      return (
        <Select id={id} autoFocus value={value} onChange={(e) => onChange(e.target.value)}>
          <option value="">Not set</option>
          {(field.options ?? []).map((o) => (
            <option key={o} value={o}>
              {optionLabel(o)}
            </option>
          ))}
        </Select>
      )
    default:
      return (
        <Input
          id={id}
          autoFocus
          type={field.type === 'date' ? 'date' : field.type === 'number' ? 'number' : field.type === 'url' ? 'url' : 'text'}
          step={field.type === 'number' ? 'any' : undefined}
          value={value}
          onChange={(e) => onChange(e.target.value)}
        />
      )
  }
}

export function WorkItemFieldsCard({
  projectId,
  workItemId,
  token,
  workflowView,
  status,
  fields,
  dueOn,
  canEdit,
  missing = [],
  onSaved,
}: WorkItemFieldsCardProps) {
  // One row edits at a time: 'due' for the due date, a field key otherwise.
  const [editing, setEditing] = useState<string | null>(null)
  const [draft, setDraft] = useState('')
  const [saving, setSaving] = useState(false)

  const declared = workflowView.fields ?? []
  const values = fields ?? {}
  const dueLabel = workflowView.dueLabel ?? 'Due'
  const statusLabelOf = (id: string) => workflowView.statuses.find((s) => s.id === id)?.label ?? id
  const terminal = workflowView.statuses.find((s) => s.id === status)?.terminal === true
  const overdue = !!dueOn && !terminal && dueOn < todayKey()
  const missingLabels = declared.filter((f) => missing.includes(f.key)).map((f) => f.label)

  function startEdit(key: string, current: string) {
    setDraft(current)
    setEditing(key)
  }

  async function save(key: string) {
    setSaving(true)
    try {
      const type = declared.find((f) => f.key === key)?.type
      // A number goes over the wire as a number; the server refuses "12" for a number field.
      const sent = draft === '' ? null : type === 'number' ? Number(draft) : draft
      const body = key === 'due' ? { dueOn: draft } : { fields: { [key]: sent } }
      const updated = await apiPatch<{ fields?: Record<string, unknown> | null; dueOn?: string | null }>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}`,
        body,
        token
      )
      onSaved(key === 'due' ? { dueOn: updated?.dueOn ?? null } : { fields: updated?.fields ?? null })
      setEditing(null)
    } catch (err) {
      // The server's own words: it names the type or the key that was wrong.
      toastError(apiErrorMessage(err, 'Could not save'))
    } finally {
      setSaving(false)
    }
  }

  function editor(key: string, label: string, input: React.ReactNode) {
    return (
      <div className="space-y-2">
        {input}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" size="sm" onClick={() => setEditing(null)} disabled={saving}>
            Cancel
          </Button>
          <Button size="sm" onClick={() => void save(key)} disabled={saving} aria-label={`Save ${label}`}>
            {saving ? 'Saving…' : 'Save'}
          </Button>
        </div>
      </div>
    )
  }

  return (
    <Card data-testid="fields-card">
      <CardHeader>
        <h2 className="text-sm font-medium text-foreground">Details</h2>
      </CardHeader>

      {missingLabels.length > 0 && (
        <div className="px-4 pt-3">
          <Alert variant="warning">
            Fill in {missingLabels.join(', ')} before moving this {workflowView.noun.toLowerCase()} on.
          </Alert>
        </div>
      )}

      <dl className="divide-y divide-border">
        {workflowView.dueLabel || dueOn ? (
          <div className="grid gap-1 px-4 py-3 sm:grid-cols-[10rem_1fr] sm:gap-4">
            <dt className="text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground">
              <label htmlFor={`due-${workItemId}`}>{dueLabel}</label>
            </dt>
            <dd className="flex items-start justify-between gap-2">
              {editing === 'due' ? (
                <div className="w-full">
                  {editor(
                    'due',
                    dueLabel,
                    <Input id={`due-${workItemId}`} autoFocus type="date" value={draft} onChange={(e) => setDraft(e.target.value)} />
                  )}
                </div>
              ) : (
                <>
                  <span className="text-sm text-foreground">
                    {dueOn ? formatDay(dueOn) : <span className="text-muted-foreground">Not set</span>}
                    {overdue && <span className="ml-2 text-xs font-medium text-destructive">Overdue</span>}
                  </span>
                  {canEdit && (
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label={`Edit ${dueLabel}`}
                      onClick={() => startEdit('due', dueOn ?? '')}
                    >
                      <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
                    </Button>
                  )}
                </>
              )}
            </dd>
          </div>
        ) : null}

        {declared.map((field) => {
          const value = values[field.key]
          const required = (field.requiredIn ?? []).map(statusLabelOf)
          const isMissing = missing.includes(field.key)
          const inputId = `field-${workItemId}-${field.key}`
          return (
            <div key={field.key} className="grid gap-1 px-4 py-3 sm:grid-cols-[10rem_1fr] sm:gap-4" data-field={field.key}>
              <dt className="space-y-0.5">
                <label htmlFor={inputId} className="block text-[11.5px] font-semibold uppercase tracking-wide text-muted-foreground">
                  {field.label}
                </label>
                {required.length > 0 && (
                  <span className={isMissing ? 'block text-xs text-destructive' : 'block text-xs text-muted-foreground'}>
                    {isMissing ? 'Needed to continue' : `Needed for ${required.join(', ')}`}
                  </span>
                )}
              </dt>
              <dd className="flex items-start justify-between gap-2">
                {editing === field.key ? (
                  <div className="w-full">
                    {editor(
                      field.key,
                      field.label,
                      <FieldInput id={inputId} field={field} value={draft} onChange={setDraft} />
                    )}
                  </div>
                ) : (
                  <>
                    <div className="min-w-0">
                      <FieldValue field={field} value={value} />
                    </div>
                    {canEdit && (
                      <Button
                        variant="ghost"
                        size="sm"
                        aria-label={`Edit ${field.label}`}
                        onClick={() => startEdit(field.key, isEmpty(value) ? '' : String(value))}
                      >
                        <Pencil className="h-3.5 w-3.5" aria-hidden="true" />
                      </Button>
                    )}
                  </>
                )}
              </dd>
            </div>
          )
        })}
      </dl>
    </Card>
  )
}
