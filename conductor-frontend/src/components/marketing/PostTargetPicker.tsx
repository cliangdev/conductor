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
import {
  EMPTY_TIKTOK_OPTIONS,
  TikTokPublishOptions,
  normalizeTikTokOptions,
  tiktokOptionsProblem,
  type TikTokPublishOptionValues,
} from '@/components/marketing/TikTokPublishOptions'
import type { TikTokConsentTarget } from '@/components/marketing/TikTokConsentStep'
import type { WorkflowView } from '@/types/workItem'

export type PublishPlatform = 'facebook' | 'instagram' | 'youtube' | 'tiktok'
export type PublishLane = 'NATIVE' | 'APP_MANAGED' | 'MANUAL'

/**
 * One selectable destination.
 *
 * Automated options are derived from an ACTIVE connection. The MANUAL option for each platform is not
 * derived from anything — it is always offered, with `connectionId` null, and is what a project with no
 * social integration publishes through: a human posts it and pastes the link back.
 */
export interface PublishTargetOption {
  platform: PublishPlatform
  connectorId: string | null
  /** Null on the MANUAL lane: there is no account, and one manual destination per platform. */
  connectionId: string | null
  label: string
  lane: PublishLane
  healthStatus?: string | null
  healthMessage?: string | null
  /**
   * TIK-2. TikTok reports a different set of privacy levels per creator (a private account is
   * offered fewer than a public one), so the choices come from the connection rather than from a
   * table here. Absent/empty means TikTok has told us nothing — which is a broken connection, not
   * permission to guess.
   */
  privacyLevelOptions?: string[] | null
  /** The handle the creator would recognise, for the consent step's "you are posting to @…". */
  creatorNickname?: string | null
}

/** One destination actually selected on this Post (a persisted post_publish_target row). */
export interface SelectedPublishTarget {
  id: string
  workItemId: string
  platform: PublishPlatform
  connectorId: string | null
  connectionId: string | null
  label?: string | null
  lane: PublishLane
  state: string
  platformPostId?: string | null
  /** Per-target publish options, currently TikTok-only. Partial — an older row carries nothing. */
  publishOptions?: Partial<TikTokPublishOptionValues> | null
}

/** What a selection sends back. `publishOptions` rides along only where the platform has any. */
interface PublishTargetSelectionPayload {
  platform: PublishPlatform
  /** Omitted (null) selects the platform's manual destination. */
  connectionId: string | null
  publishOptions?: TikTokPublishOptionValues
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

/**
 * (platform, connection) is a target's identity — the same pair the backend's uniqueness is on. A manual
 * destination has no connection, so it keys on the same `manual` sentinel the backend uses, which is also
 * what makes "one manual destination per platform" fall out for free.
 */
function targetKey(platform: string, connectionId: string | null): string {
  return `${platform} ${connectionId ?? 'manual'}`
}

function isManual(option: PublishTargetOption | SelectedPublishTarget): boolean {
  return option.lane === 'MANUAL'
}

function isUnhealthy(option: PublishTargetOption): boolean {
  return option.healthStatus === 'UNHEALTHY'
}

/**
 * The TikTok options carried by a set of persisted targets. `fallback` covers a backend that hasn't
 * started echoing publishOptions yet — without it, a round-trip would silently blank an edit.
 */
function seedTikTokOptions(
  targets: SelectedPublishTarget[],
  fallback: Record<string, TikTokPublishOptionValues> = {}
): Record<string, TikTokPublishOptionValues> {
  const seeded: Record<string, TikTokPublishOptionValues> = {}
  for (const target of targets) {
    if (target.platform !== 'tiktok') continue
    const key = targetKey(target.platform, target.connectionId)
    seeded[key] = normalizeTikTokOptions(target.publishOptions ?? fallback[key])
  }
  return seeded
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
  /**
   * Every selected TikTok destination with the options it currently carries, whenever that changes.
   * The consent step lives beside the creative rather than in here (it needs the Post's media), so
   * the picker publishes what it knows instead of owning that surface.
   */
  onTikTokChange?: (targets: TikTokConsentTarget[]) => void
}

export function PostTargetPicker({
  projectId,
  workItemId,
  token,
  status,
  workflowView,
  onChanged,
  onTikTokChange,
}: PostTargetPickerProps) {
  const [options, setOptions] = useState<PublishTargetOption[]>([])
  const [selected, setSelected] = useState<SelectedPublishTarget[]>([])
  // Per-target TikTok options, keyed the same way a target is. Kept beside `selected` rather than
  // inside it because it is the thing being edited, and an edit that fails to save must not linger.
  const [optionsByKey, setOptionsByKey] = useState<Record<string, TikTokPublishOptionValues>>({})
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
        setOptionsByKey(seedTikTokOptions(current))
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
        label: t.label ?? t.connectionId ?? 'Manual',
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
    async (nextKeys: Set<string>, nextOptions: Record<string, TikTokPublishOptionValues>) => {
      // Only ever send targets the project can still publish to; an orphan would be refused.
      const payload: PublishTargetSelectionPayload[] = options
        .filter((o) => nextKeys.has(targetKey(o.platform, o.connectionId)))
        .map((o) =>
          // Options ride along only for an API TikTok target. A manual one never uses them — they are
          // the payload we would send TikTok, and on that lane the creator sets every one of them in
          // TikTok's own composer — and sending them anyway would store a bag of meaningless falses on
          // the row that then counts as part of the publish bundle.
          o.platform === 'tiktok' && !isManual(o)
            ? {
                platform: o.platform,
                connectionId: o.connectionId,
                publishOptions:
                  nextOptions[targetKey(o.platform, o.connectionId)] ?? EMPTY_TIKTOK_OPTIONS,
              }
            : { platform: o.platform, connectionId: o.connectionId }
        )
      setSaving(true)
      try {
        const updated = await apiPut<SelectedPublishTarget[]>(
          `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
          { targets: payload },
          token
        )
        setSelected(updated)
        // What was just sent is only committed once the server took it — an edit that 400s reverts
        // to what is on the server. The server's echo wins where it has one, so a value it clamps
        // shows as clamped rather than as what was typed.
        setOptionsByKey({ ...nextOptions, ...seedTikTokOptions(updated, nextOptions) })
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
      void save(next, optionsByKey)
    },
    [selectedKeys, optionsByKey, save]
  )

  const changeTikTokOptions = useCallback(
    (option: PublishTargetOption, next: TikTokPublishOptionValues) => {
      void save(selectedKeys, {
        ...optionsByKey,
        [targetKey(option.platform, option.connectionId)]: next,
      })
    },
    [selectedKeys, optionsByKey, save]
  )

  /** Every selected TikTok destination, with the options it carries and why it isn't postable yet. */
  const tiktokTargets = useMemo<TikTokConsentTarget[]>(
    () =>
      options
        .filter(
          (o) =>
            o.platform === 'tiktok' &&
            // A manual TikTok destination needs no consent: the creator posts it inside TikTok, seeing
            // TikTok's own preview. Mirrors PublishConsentService.requiresConsent exactly — if these two
            // disagreed, the UI would ask for a consent the approval gate does not want.
            !isManual(o) &&
            selectedKeys.has(targetKey(o.platform, o.connectionId))
        )
        .map((o) => {
          const values = optionsByKey[targetKey(o.platform, o.connectionId)] ?? EMPTY_TIKTOK_OPTIONS
          return {
            connectionId: o.connectionId ?? '',
            label: o.label,
            creatorNickname: o.creatorNickname ?? null,
            options: values,
            problem: tiktokOptionsProblem(values),
          }
        }),
    [options, selectedKeys, optionsByKey]
  )

  useEffect(() => {
    onTikTokChange?.(tiktokTargets)
  }, [tiktokTargets, onTikTokChange])

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
        // Unreachable against a current backend, which always offers a manual destination per platform.
        // Kept as the honest rendering of an empty list rather than removed, so an older or partial
        // response degrades into an explanation instead of a blank card.
        <EmptyState
          icon={Share2}
          title="Nowhere to publish"
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
                    saving={saving}
                    tiktokOptions={
                      optionsByKey[targetKey(option.platform, option.connectionId)] ??
                      EMPTY_TIKTOK_OPTIONS
                    }
                    onToggle={() => toggle(option)}
                    onTikTokOptionsChange={(next) => changeTikTokOptions(option, next)}
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
  saving: boolean
  tiktokOptions: TikTokPublishOptionValues
  onToggle: () => void
  onTikTokOptionsChange: (next: TikTokPublishOptionValues) => void
}

function TargetRow({
  option,
  checked,
  unavailable,
  saving,
  tiktokOptions,
  onToggle,
  onTikTokOptionsChange,
}: TargetRowProps) {
  const unhealthy = isUnhealthy(option)
  // An unhealthy account can't be added — its credentials no longer work — but one already on the
  // Post stays actionable, or a human could never take it back off.
  const disabled = unhealthy && !checked
  const noteId = `${option.platform}-${option.connectionId ?? 'manual'}-note`
  const note = unavailable
    ? 'This account is no longer connected — it will be removed when you change the selection.'
    : unhealthy
      ? (option.healthMessage ?? 'This account needs to be reconnected before it can publish.')
      : isManual(option)
        ? "Conductor won't post this one. It still goes through review and onto the calendar; when it's due you'll be asked to post it yourself and paste the link back."
        : null

  // TikTok is the one platform whose post carries per-target choices, and TikTok's own guidelines
  // require them to be made per account rather than once for the Post. They open with the account,
  // and sit outside the <label> so a click on a control doesn't also un-pick the destination.
  // Only for an API target: the per-target options are the payload we send TikTok, and on the manual
  // lane the creator sets all of them in TikTok's own composer.
  const showTikTokOptions =
    option.platform === 'tiktok' && checked && !unavailable && !isManual(option)

  return (
    <div>
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
            <span
              id={noteId}
              className={cn(
                'block text-xs',
                // A manual destination is a normal choice, not a problem to warn about — amber is
                // reserved for the two rows a human has to do something about.
                isManual(option) && !unavailable && !unhealthy
                  ? 'text-muted-foreground'
                  : statusHueClasses('amber').text
              )}
            >
              {note}
            </span>
          )}
        </span>
      </label>
      {showTikTokOptions && (
        <TikTokPublishOptions
          idPrefix={`tiktok-${option.connectionId}`}
          accountLabel={option.label}
          privacyLevelOptions={option.privacyLevelOptions ?? []}
          value={tiktokOptions}
          disabled={saving}
          onChange={onTikTokOptionsChange}
        />
      )}
    </div>
  )
}
