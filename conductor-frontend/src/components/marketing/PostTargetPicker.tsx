'use client'

// COND-23 T3.6: which accounts a Post goes out to.
//
// The list of choices is derived by the backend from the project's ACTIVE social connections
// (GET /projects/{id}/publish-targets) — a platform with no connection is simply absent, because
// there is nothing a human could pick it for. The Post's own choice is a set-replace
// (PUT .../work-items/{id}/publish-targets): every toggle sends the whole selection, so the server
// diffs it and touches only the rows that changed. That matters — a row may already be handed off to
// a platform and carry the id of a post that is live.
//
// Two states get explicit faces rather than a silent drop:
//   * an UNHEALTHY connection is offered disabled, with the platform's own explanation, so a human
//     sees why the account they expected cannot be chosen (reconnecting it is a Settings trip);
//   * a selected account whose connection has since gone away is shown checked with a note, and is
//     dropped from the payload on the next save rather than being sent back to a server that would
//     refuse it.

import { useCallback, useEffect, useMemo, useState } from 'react'
import { Share2 } from 'lucide-react'
import { Alert } from '@/components/ui/alert'
import { Card, CardHeader } from '@/components/ui/card'
import { EmptyState } from '@/components/ui/empty-state'
import { Skeleton } from '@/components/ui/skeleton'
import { statusHueClasses } from '@/components/ui/status-badge'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiGet, apiPut } from '@/lib/api'
import { cn } from '@/lib/utils'
import { isApprovedOrLater } from '@/components/workitems/MediaUploadPanel'
import type { WorkflowView } from '@/types/workItem'

export type PublishPlatform = 'facebook' | 'instagram' | 'youtube' | 'tiktok'
export type PublishLane = 'NATIVE' | 'APP_MANAGED'

/** One selectable destination, derived from an ACTIVE connection. */
export interface PublishTargetOption {
  platform: PublishPlatform
  connectorId: string
  connectionId: string
  label: string
  lane: PublishLane
  healthStatus?: string | null
  healthMessage?: string | null
}

/** One destination actually selected on this Post (a persisted post_publish_target row). */
export interface SelectedPublishTarget {
  id: string
  workItemId: string
  platform: PublishPlatform
  connectorId: string
  connectionId: string
  label?: string | null
  lane: PublishLane
  state: string
  platformPostId?: string | null
}

/** Render order, so the groups don't reshuffle as connections come and go. */
const PLATFORM_ORDER: PublishPlatform[] = ['facebook', 'instagram', 'youtube', 'tiktok']

const PLATFORM_LABELS: Record<PublishPlatform, string> = {
  facebook: 'Facebook',
  instagram: 'Instagram',
  youtube: 'YouTube',
  tiktok: 'TikTok',
}

/**
 * Client-side mirror of PostScheduleValidator.declaresPublishTargets: a Workflow treats publishing as
 * a concept when one of its declared asset types is named for a publishable platform
 * (`instagram_post`, `youtube_video`, or a bare `tiktok`). It is the gate for offering this picker at
 * all — an ENGINEERING item declares `github_pr` and never sees it.
 */
export function workflowDeclaresPublishTargets(view: WorkflowView | undefined): boolean {
  return (view?.assetTypes ?? []).some((assetType) => {
    const head = assetType.trim().toLowerCase().split('_')[0]
    return (PLATFORM_ORDER as string[]).includes(head)
  })
}

/** (platform, connection) is a target's identity — the same pair the backend's uniqueness is on. */
function targetKey(platform: string, connectionId: string): string {
  return `${platform} ${connectionId}`
}

function isUnhealthy(option: PublishTargetOption): boolean {
  return option.healthStatus === 'UNHEALTHY'
}

interface PostTargetPickerProps {
  projectId: string
  workItemId: string
  token: string
  /** The Post's current status, for the "this will send it back for review" warning. */
  status?: string
  workflowView?: WorkflowView
  /** Fired after every successful save, so a parent can refresh anything bundle-derived. */
  onChanged?: (targets: SelectedPublishTarget[]) => void
}

export function PostTargetPicker({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  onChanged,
}: PostTargetPickerProps) {
  const [options, setOptions] = useState<PublishTargetOption[]>([])
  const [selected, setSelected] = useState<SelectedPublishTarget[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  // Both lists in one pass: the choices are project-scoped and the selection is item-scoped, but a
  // picker that rendered one before the other would flash rows as unchecked before checking them.
  // `loading` starts true and is only ever cleared here — the deps are route-stable, so there is no
  // re-entry to re-arm it for.
  useEffect(() => {
    let cancelled = false
    Promise.all([
      apiGet<PublishTargetOption[]>(`/api/v2/projects/${projectId}/publish-targets`, token),
      apiGet<SelectedPublishTarget[]>(
        `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
        token
      ),
    ])
      .then(([available, current]) => {
        if (cancelled) return
        setOptions(available)
        setSelected(current)
        setLoadError(null)
      })
      .catch((err) => {
        if (cancelled) return
        setLoadError(apiErrorMessage(err, 'Could not load publishing accounts'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, workItemId, token])

  const availableKeys = useMemo(
    () => new Set(options.map((o) => targetKey(o.platform, o.connectionId))),
    [options]
  )
  const selectedKeys = useMemo(
    () => new Set(selected.map((t) => targetKey(t.platform, t.connectionId))),
    [selected]
  )

  /**
   * Every row the picker shows: the project's options, plus any still-selected target whose
   * connection has since disappeared, so it can be seen and unchecked instead of vanishing.
   */
  const rows = useMemo(() => {
    const orphans: PublishTargetOption[] = selected
      .filter((t) => !availableKeys.has(targetKey(t.platform, t.connectionId)))
      .map((t) => ({
        platform: t.platform,
        connectorId: t.connectorId,
        connectionId: t.connectionId,
        label: t.label ?? t.connectionId,
        lane: t.lane,
      }))
    return [...options, ...orphans]
  }, [options, selected, availableKeys])

  const groups = useMemo(
    () =>
      PLATFORM_ORDER.map((platform) => ({
        platform,
        targets: rows.filter((r) => r.platform === platform),
      })).filter((group) => group.targets.length > 0),
    [rows]
  )

  const save = useCallback(
    async (nextKeys: Set<string>) => {
      // Only ever send targets the project can still publish to; an orphan would be refused.
      const payload = options
        .filter((o) => nextKeys.has(targetKey(o.platform, o.connectionId)))
        .map((o) => ({ platform: o.platform, connectionId: o.connectionId }))
      setSaving(true)
      try {
        const updated = await apiPut<SelectedPublishTarget[]>(
          `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
          { targets: payload },
          token
        )
        setSelected(updated)
        onChanged?.(updated)
      } catch (err) {
        toastError(apiErrorMessage(err, 'Could not update publishing accounts'))
      } finally {
        setSaving(false)
      }
    },
    [options, projectId, workItemId, token, onChanged]
  )

  const toggle = useCallback(
    (option: PublishTargetOption) => {
      const key = targetKey(option.platform, option.connectionId)
      const next = new Set(selectedKeys)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      void save(next)
    },
    [selectedKeys, save]
  )

  const revertsOnEdit = isApprovedOrLater(workflowView, status)
  const noun = workflowView?.noun ?? 'Post'

  if (loading) {
    return (
      <Card>
        <CardHeader>
          <h2 className="text-sm font-medium text-foreground">Publishing to</h2>
        </CardHeader>
        <div className="space-y-2.5 px-4 py-3">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-4 w-32" />
        </div>
      </Card>
    )
  }

  return (
    <Card>
      <CardHeader>
        <h2 className="text-sm font-medium text-foreground">Publishing to</h2>
        <span className="text-xs text-muted-foreground">
          {selected.length === 0
            ? 'No accounts selected'
            : `${selected.length} account${selected.length === 1 ? '' : 's'} selected`}
        </span>
      </CardHeader>

      {loadError && (
        <div className="px-4 py-3">
          <Alert variant="destructive">{loadError}</Alert>
        </div>
      )}

      {!loadError && groups.length === 0 && (
        <EmptyState
          icon={Share2}
          title="No connected accounts"
          description={`Connect a Facebook Page, Instagram, YouTube or TikTok account in Integrations to choose where this ${noun} publishes.`}
        />
      )}

      {!loadError && groups.length > 0 && (
        <>
          {revertsOnEdit && (
            <div className="px-4 pt-3">
              <Alert variant="warning">
                Changing accounts sends this {noun} back for review and takes back anything already
                scheduled on a platform.
              </Alert>
            </div>
          )}
          <fieldset disabled={saving} className="divide-y divide-border">
            <legend className="sr-only">Publishing accounts</legend>
            {groups.map((group) => (
              <div key={group.platform} className="py-1.5">
                <div className="px-4 py-1 text-[11.5px] font-semibold uppercase tracking-[0.06em] text-muted-foreground">
                  {PLATFORM_LABELS[group.platform]}
                </div>
                {group.targets.map((option) => (
                  <TargetRow
                    key={targetKey(option.platform, option.connectionId)}
                    option={option}
                    checked={selectedKeys.has(targetKey(option.platform, option.connectionId))}
                    unavailable={!availableKeys.has(targetKey(option.platform, option.connectionId))}
                    onToggle={() => toggle(option)}
                  />
                ))}
              </div>
            ))}
          </fieldset>
        </>
      )}
    </Card>
  )
}

interface TargetRowProps {
  option: PublishTargetOption
  checked: boolean
  /** The connection behind an already-selected target has gone away. */
  unavailable: boolean
  onToggle: () => void
}

function TargetRow({ option, checked, unavailable, onToggle }: TargetRowProps) {
  const unhealthy = isUnhealthy(option)
  // An unhealthy account can't be added — its credentials no longer work — but one already on the
  // Post stays actionable, or a human could never take it back off.
  const disabled = unhealthy && !checked
  const noteId = `${option.platform}-${option.connectionId}-note`
  const note = unavailable
    ? 'This account is no longer connected — it will be removed when you change the selection.'
    : unhealthy
      ? (option.healthMessage ?? 'This account needs to be reconnected before it can publish.')
      : null

  return (
    <label
      className={cn(
        'flex items-start gap-2.5 px-4 py-2',
        disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer hover:bg-muted/50'
      )}
    >
      <input
        type="checkbox"
        className="mt-0.5 rounded border-border"
        checked={checked}
        disabled={disabled}
        aria-describedby={note ? noteId : undefined}
        onChange={onToggle}
      />
      <span className="min-w-0">
        <span className="block text-sm text-foreground">{option.label}</span>
        {note && (
          <span id={noteId} className={cn('block text-xs', statusHueClasses('amber').text)}>
            {note}
          </span>
        )}
      </span>
    </label>
  )
}
