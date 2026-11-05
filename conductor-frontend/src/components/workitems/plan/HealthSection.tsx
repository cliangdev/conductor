'use client'

// Health + forecast on the properties panel (docs/prd-tracking.md). Health is a person's call: an agent
// only drafts, and a draft here is confirmed (optionally edited) before it becomes the current health.
// Reviewers see it read-only.

import { useEffect, useState } from 'react'
import { apiErrorMessage, apiGet } from '@/lib/api'
import { confirmHealthUpdate, createHealthUpdate, fetchForecast, forecastLine, formatForecastDate } from '@/lib/delivery'
import { timeAgo } from '@/lib/format'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Select } from '@/components/ui/select'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge, type StatusHue } from '@/components/ui/status-badge'
import { toastError } from '@/components/ui/toast'
import { PanelSection } from '@/components/workitems/PanelSection'
import type { HealthStatus, WorkItemForecast, WorkItemHealth } from '@/types/workItem'

const HEALTHS: HealthStatus[] = ['ON_TRACK', 'AT_RISK', 'OFF_TRACK']

const HEALTH_LABEL: Record<HealthStatus, string> = {
  ON_TRACK: 'On track',
  AT_RISK: 'At risk',
  OFF_TRACK: 'Off track',
}

const HEALTH_HUE: Record<HealthStatus, StatusHue> = {
  ON_TRACK: 'green',
  AT_RISK: 'amber',
  OFF_TRACK: 'red',
}

/** The health chip; "No health yet" (muted) when nothing has been confirmed. */
export function HealthBadge({ health }: { health: HealthStatus | null | undefined }) {
  return health ? (
    <StatusBadge status={health} label={HEALTH_LABEL[health]} hue={HEALTH_HUE[health]} />
  ) : (
    <StatusBadge status="none" label="No health yet" hue="gray" />
  )
}

export function HealthSection({
  projectId,
  issueId,
  token,
  canEdit,
  health,
  childCount,
  onChanged,
}: {
  projectId: string
  issueId: string
  token: string
  canEdit: boolean
  health?: WorkItemHealth | null
  childCount?: number
  /** The item's health as the server now holds it, for the page to merge into its copy. */
  onChanged: (patch: { health: WorkItemHealth | null }) => void
}) {
  const [forecast, setForecast] = useState<WorkItemForecast | null>(null)
  const [forecastFailed, setForecastFailed] = useState(false)
  const [editing, setEditing] = useState(false)
  const [posting, setPosting] = useState(false)
  const [draftHealth, setDraftHealth] = useState<HealthStatus>('ON_TRACK')
  const [draftNote, setDraftNote] = useState('')
  const [formOpen, setFormOpen] = useState(false)
  const [newHealth, setNewHealth] = useState<HealthStatus>('ON_TRACK')
  const [newNote, setNewNote] = useState('')

  const pending = health?.pendingDraft ?? null
  // Health is a PRD-level call; a plain task with no sub-items and no health has nothing to show.
  const hidden = (childCount ?? 0) === 0 && !health?.health && !pending

  useEffect(() => {
    if (!token || hidden) return
    let cancelled = false
    fetchForecast(projectId, issueId, token)
      .then((f) => !cancelled && (setForecast(f), setForecastFailed(false)))
      .catch(() => !cancelled && setForecastFailed(true))
    return () => {
      cancelled = true
    }
  }, [projectId, issueId, token, hidden])

  if (hidden) return null

  async function refresh(fallback: WorkItemHealth) {
    try {
      const fresh = await apiGet<{ health?: WorkItemHealth | null }>(`/api/v2/projects/${projectId}/work-items/${issueId}`, token)
      onChanged({ health: fresh.health ?? fallback })
    } catch {
      onChanged({ health: fallback })
    }
  }

  async function confirm() {
    if (!pending) return
    setPosting(true)
    try {
      const edits = editing ? { health: draftHealth, note: draftNote.trim() } : {}
      const saved = await confirmHealthUpdate(projectId, issueId, pending.updateId, edits, token)
      setEditing(false)
      await refresh({
        health: saved?.health ?? edits.health ?? pending.health,
        note: saved?.note ?? pending.note,
        updatedAt: saved?.confirmedAt ?? new Date().toISOString(),
        stale: false,
        atRisk: (saved?.health ?? pending.health) !== 'ON_TRACK',
        pendingDraft: null,
      })
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not confirm the health update'))
    } finally {
      setPosting(false)
    }
  }

  async function post() {
    const note = newNote.trim()
    if (!note) return
    setPosting(true)
    try {
      const saved = await createHealthUpdate(projectId, issueId, { health: newHealth, note, draft: false }, token)
      setFormOpen(false)
      setNewNote('')
      await refresh({
        health: saved?.health ?? newHealth,
        note: saved?.note ?? note,
        updatedAt: saved?.confirmedAt ?? new Date().toISOString(),
        stale: false,
        atRisk: newHealth !== 'ON_TRACK',
        pendingDraft: health?.pendingDraft ?? null,
      })
    } catch (err) {
      toastError(apiErrorMessage(err, 'Could not record the health update'))
    } finally {
      setPosting(false)
    }
  }

  return (
    <PanelSection label="Health">
      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <HealthBadge health={health?.health} />
          {health?.updatedAt && (
            <span className="text-xs text-muted-foreground">
              Updated {timeAgo(health.updatedAt)}
              {health.stale ? ' · stale, worth a fresh update' : ''}
            </span>
          )}
        </div>
        {health?.note && <p className="text-sm text-foreground">{health.note}</p>}

        {pending && (
          <div className="space-y-2 rounded-md border border-border bg-surface-3 p-2.5" data-testid="health-draft">
            <p className="text-xs text-muted-foreground">
              Draft from {pending.draftedByLabel ?? 'an agent'}: <span className="text-foreground">{HEALTH_LABEL[pending.health]}</span> &mdash;{' '}
              {pending.note}
            </p>
            {canEdit && editing && (
              <div className="space-y-2">
                <Select aria-label="Draft health" value={draftHealth} onChange={(e) => setDraftHealth(e.target.value as HealthStatus)}>
                  {HEALTHS.map((h) => (
                    <option key={h} value={h}>
                      {HEALTH_LABEL[h]}
                    </option>
                  ))}
                </Select>
                <Input aria-label="Draft note" value={draftNote} onChange={(e) => setDraftNote(e.target.value)} />
              </div>
            )}
            {canEdit && (
              <div className="flex items-center gap-2">
                <Button size="sm" disabled={posting || (editing && !draftNote.trim())} onClick={() => void confirm()}>
                  Confirm
                </Button>
                {!editing && (
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={posting}
                    onClick={() => {
                      setDraftHealth(pending.health)
                      setDraftNote(pending.note)
                      setEditing(true)
                    }}
                  >
                    Edit
                  </Button>
                )}
              </div>
            )}
          </div>
        )}

        {canEdit &&
          (formOpen ? (
            <form
              className="space-y-2"
              onSubmit={(e) => {
                e.preventDefault()
                void post()
              }}
            >
              <Select aria-label="Health" value={newHealth} onChange={(e) => setNewHealth(e.target.value as HealthStatus)}>
                {HEALTHS.map((h) => (
                  <option key={h} value={h}>
                    {HEALTH_LABEL[h]}
                  </option>
                ))}
              </Select>
              <Input aria-label="Health note" placeholder="What is the state of play?" value={newNote} onChange={(e) => setNewNote(e.target.value)} />
              <div className="flex items-center gap-2">
                <Button type="submit" size="sm" disabled={posting || !newNote.trim()}>
                  Post update
                </Button>
                <Button type="button" size="sm" variant="ghost" onClick={() => setFormOpen(false)}>
                  Cancel
                </Button>
              </div>
            </form>
          ) : (
            <Button size="sm" variant="outline" onClick={() => setFormOpen(true)}>
              Post update
            </Button>
          ))}

        {forecastFailed ? (
          <p className="text-xs text-muted-foreground">Couldn&rsquo;t load the forecast.</p>
        ) : !forecast ? (
          <Skeleton className="h-4 w-2/3" aria-label="Loading forecast" />
        ) : (
          <p className="text-xs text-muted-foreground" data-testid="forecast-line">
            {forecastLine(forecast, formatForecastDate)}
          </p>
        )}
      </div>
    </PanelSection>
  )
}
