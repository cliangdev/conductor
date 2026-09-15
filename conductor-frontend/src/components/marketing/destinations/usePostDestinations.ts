'use client'

// The server-backed state behind a Post's Destinations panel: the project's options, the Post's
// selection, its metrics and its TikTok consent, plus every mutation that changes them. Consumes the
// preflight the page already polls (`usePublishReadiness`) rather than asking for it again, and never
// bumps the page's refresh key itself — it tells the page something changed (`onChanged`), and the page
// bumps the key, which re-reads both the preflight and this hook's selection.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { toastError } from '@/components/ui/toast'
import { apiErrorMessage, apiGet, apiPost, apiPut } from '@/lib/api'
import { statusMeta } from '@/lib/workflows'
import type { WorkflowView } from '@/types/workItem'
import type { PublishPreflight } from '@/components/marketing/PublishReadinessCard'
import { tiktokOptionsProblem } from '@/components/marketing/TikTokPublishOptions'
import { unreportedNote, type PublishMetricsResponse } from './DestinationMetrics'
import {
  fetchPublishConsent,
  recordPublishConsent,
  tiktokSubmissionBlockedReason,
  type PublishConsentState,
  type TikTokConsentTarget,
} from '@/components/marketing/TikTokConsentStep'
import {
  isApprovedOrLater,
  isUnderReviewOrLater,
  type MediaAsset,
} from '@/components/workitems/MediaUploadPanel'
import { buildRows, summarize, type DestinationActions, type DestinationMode, type DestinationsState } from './model'
import {
  EMPTY_DRAFT,
  buildSelectionPayload,
  draftFromTargets,
  type DestinationDraft,
} from './selectionState'
import type { PublishTargetOption, RetryPublishResponse, SelectedPublishTarget } from './types'

export interface UsePostDestinationsArgs {
  projectId: string
  workItemId: string
  token: string
  /** False for a Work Item whose Workflow does not publish: nothing is fetched, rows stay empty. */
  enabled: boolean
  status: string
  workflowView?: WorkflowView
  assets: MediaAsset[]
  caption: string | null
  preflight: PublishPreflight | null
  /** The page's signal that the answer may have moved — the same key the preflight re-polls on. */
  refreshKey?: number | string
  /** After every successful change, with the targets as the server now holds them. */
  onChanged?: (targets: SelectedPublishTarget[]) => void
  /** After the creator's consent is recorded; consent is one of the gate's inputs. */
  onConsentChanged?: (given: boolean) => void
}

export interface PostDestinationsState extends DestinationsState {
  /** Every selected, non-manual TikTok destination, as the consent gate reads it. */
  tiktokTargets: TikTokConsentTarget[]
  /** Why the Post can't be sent for review yet on TikTok's account, or null — the gate provider's input. */
  tiktokBlockedReason: string | null
  failedCount: number
  awaitingCount: number
}

export function usePostDestinations({
  projectId,
  workItemId,
  token,
  enabled,
  status,
  workflowView,
  assets,
  caption,
  preflight,
  refreshKey,
  onChanged,
  onConsentChanged,
}: UsePostDestinationsArgs): PostDestinationsState {
  const [options, setOptions] = useState<PublishTargetOption[]>([])
  const [selected, setSelected] = useState<SelectedPublishTarget[]>([])
  const [draft, setDraft] = useState<DestinationDraft>(EMPTY_DRAFT)
  const [loading, setLoading] = useState(enabled)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [retrying, setRetrying] = useState(false)
  // The status the person asked to edit under; once the status moves on (a save reverted it, an
  // approval landed) the explicit edit is over without an effect having to notice.
  const [editingStatus, setEditingStatus] = useState<string | null>(null)
  const editing = editingStatus === status
  const [metrics, setMetrics] = useState<PublishMetricsResponse | null>(null)
  const [consentServer, setConsentServer] = useState<PublishConsentState | null>(null)
  const [consentError, setConsentError] = useState<string | null>(null)
  const [consentSaving, setConsentSaving] = useState(false)

  // The draft is read inside event handlers and async work; a ref keeps that read current without
  // re-creating the callbacks. The parent's handlers are inline arrows, so the same for them.
  const draftRef = useRef(draft)
  useEffect(() => {
    draftRef.current = draft
  }, [draft])
  const onChangedRef = useRef(onChanged)
  useEffect(() => {
    onChangedRef.current = onChanged
  }, [onChanged])
  const onConsentChangedRef = useRef(onConsentChanged)
  useEffect(() => {
    onConsentChangedRef.current = onConsentChanged
  }, [onConsentChanged])

  const approvedOrLater = isApprovedOrLater(workflowView, status)
  const underReviewOrLater = isUnderReviewOrLater(workflowView, status)
  const locked = underReviewOrLater && !approvedOrLater
  const mode: DestinationMode = underReviewOrLater && !editing ? 'outcome' : 'pick'

  // Options are project-scoped and read once; the selection is item-scoped and read again whenever the
  // page says something moved, so a status change that reverts or revokes targets is seen here too.
  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    apiGet<PublishTargetOption[]>(`/api/v2/projects/${projectId}/publish-targets`, token)
      .then((available) => {
        if (!cancelled) setOptions(available)
      })
      .catch((err) => {
        if (!cancelled) setLoadError(apiErrorMessage(err, 'Could not load publishing accounts'))
      })
    return () => {
      cancelled = true
    }
  }, [enabled, projectId, token])

  useEffect(() => {
    if (!enabled) return
    let cancelled = false
    apiGet<SelectedPublishTarget[]>(
      `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
      token
    )
      .then((current) => {
        if (cancelled) return
        setSelected(current)
        setDraft(draftFromTargets(current, draftRef.current))
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
  }, [enabled, projectId, workItemId, token, status, refreshKey])

  // Metrics exist only once something has published; a draft never asks.
  const finished = statusMeta(workflowView, status).category === 'terminal'
  const anyPublished = selected.some((t) => t.state === 'PUBLISHED')
  const wantsMetrics = enabled && (finished || anyPublished)
  useEffect(() => {
    if (!wantsMetrics) return
    let cancelled = false
    apiGet<PublishMetricsResponse>(
      `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-metrics`,
      token
    )
      .then((data) => {
        if (!cancelled) setMetrics(data)
      })
      .catch(() => {
        // The numbers are a nicety on the row; a failed read leaves the row without them, not broken.
        if (!cancelled) setMetrics(null)
      })
    return () => {
      cancelled = true
    }
  }, [wantsMetrics, projectId, workItemId, token, status, refreshKey])

  /** Every selected TikTok destination, with the options it carries and why it isn't postable yet. */
  const tiktokTargets = useMemo<TikTokConsentTarget[]>(() => {
    // Built from the same rows the panel shows, so the consent gate and the panel never disagree.
    const rows = buildRows({
      options,
      selected,
      draft,
      preflight: null,
      metrics: null,
      consent: null,
      approvedOrLater,
      mode: 'pick',
      assets,
    })
    return rows
      .filter((r) => r.option.platform === 'tiktok' && !r.manual && r.checked)
      .map((r) => ({
        connectionId: r.option.connectionId ?? '',
        label: r.option.label,
        creatorNickname: r.option.creatorNickname ?? null,
        options: r.tiktokOptions,
        problem: tiktokOptionsProblem(r.tiktokOptions),
        caption: r.content.captionOverride ?? caption,
        ...(r.content.assetIds === null ? {} : { assetIds: r.content.assetIds }),
      }))
  }, [options, selected, draft, approvedOrLater, assets, caption])

  // What the creator is being asked to consent to. When it changes — an account swapped, a privacy
  // level edited, a different cut uploaded — the server's answer changes with it, so re-read.
  const consentSubject = JSON.stringify([tiktokTargets, assets.map((a) => a.id)])
  const consentRequired = enabled && tiktokTargets.length > 0
  useEffect(() => {
    if (!consentRequired) return
    let cancelled = false
    fetchPublishConsent(projectId, workItemId, token)
      .then((state) => {
        if (cancelled) return
        setConsentError(null)
        setConsentServer(state)
      })
      .catch((err) => {
        if (cancelled) return
        // Fail closed: an unreadable consent is not a given one.
        setConsentError(apiErrorMessage(err, 'Could not read this post’s TikTok consent.'))
        setConsentServer(null)
      })
    return () => {
      cancelled = true
    }
  }, [consentRequired, projectId, workItemId, token, consentSubject, refreshKey])

  const consentGiven = Boolean(consentServer?.valid)
  const tiktokBlockedReason = tiktokSubmissionBlockedReason(tiktokTargets, consentGiven, consentServer?.verdict)

  const save = useCallback(
    async (next: DestinationDraft) => {
      setDraft(next)
      setSaving(true)
      try {
        const updated = await apiPut<SelectedPublishTarget[]>(
          `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets`,
          { targets: buildSelectionPayload(options, next) },
          token
        )
        setSelected(updated)
        // What was just sent is only committed once the server took it; the server's echo wins where
        // it has one, so a value it clamps shows as clamped rather than as what was typed.
        setDraft(draftFromTargets(updated, next))
        onChangedRef.current?.(updated)
      } catch (err) {
        toastError(apiErrorMessage(err, 'Could not update publishing accounts'))
        // An edit that 400s reverts to what is on the server.
        setDraft(draftFromTargets(selected, draftRef.current))
      } finally {
        setSaving(false)
      }
    },
    [options, projectId, workItemId, token, selected]
  )

  const actions = useMemo<DestinationActions>(
    () => ({
      toggle: (row) => {
        const keys = new Set(draftRef.current.keys)
        if (keys.has(row.key)) keys.delete(row.key)
        else keys.add(row.key)
        void save({ ...draftRef.current, keys })
      },
      setFormat: (row, next) =>
        void save({ ...draftRef.current, formats: { ...draftRef.current.formats, [row.key]: next } }),
      setTikTokOptions: (row, next) =>
        void save({ ...draftRef.current, tiktok: { ...draftRef.current.tiktok, [row.key]: next } }),
      setInstagramOptions: (row, next) =>
        void save({ ...draftRef.current, instagram: { ...draftRef.current.instagram, [row.key]: next } }),
      setYouTubeOptions: (row, next) =>
        void save({ ...draftRef.current, youtube: { ...draftRef.current.youtube, [row.key]: next } }),
      setContent: (row, next) =>
        void save({ ...draftRef.current, content: { ...draftRef.current.content, [row.key]: next } }),
      retry: async () => {
        setRetrying(true)
        try {
          // The response carries every target, so the retry is also the refresh.
          const result = await apiPost<RetryPublishResponse>(
            `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets/retry`,
            {},
            token
          )
          setSelected(result.targets)
          onChangedRef.current?.(result.targets)
        } catch (err) {
          toastError(apiErrorMessage(err, 'Could not retry the failed accounts'))
        } finally {
          setRetrying(false)
        }
      },
      completeManual: async (row, permalink, publishedAt) => {
        if (!row.target) return
        const updated = await apiPost<SelectedPublishTarget>(
          `/api/v2/projects/${projectId}/work-items/${workItemId}/publish-targets/${row.target.id}/manual-publish`,
          { permalink, publishedAt },
          token
        )
        setSelected((current) => {
          const next = current.map((t) => (t.id === updated.id ? updated : t))
          onChangedRef.current?.(next)
          return next
        })
      },
      setConsent: async (next) => {
        setConsentSaving(true)
        try {
          const state = await recordPublishConsent(projectId, workItemId, next, token)
          setConsentServer(state)
          setConsentError(null)
          onConsentChangedRef.current?.(Boolean(state.valid))
        } catch (err) {
          setConsentError(apiErrorMessage(err, 'Could not record your TikTok consent.'))
        } finally {
          setConsentSaving(false)
        }
      },
      editDestinations: () => setEditingStatus(status),
    }),
    [save, projectId, workItemId, token, status]
  )

  const rows = useMemo(
    () =>
      buildRows({
        options,
        selected,
        draft,
        preflight,
        metrics,
        consent: consentRequired ? { state: consentServer, given: consentGiven } : null,
        approvedOrLater,
        mode,
        assets,
      }),
    [options, selected, draft, preflight, metrics, consentRequired, consentServer, consentGiven, approvedOrLater, mode, assets]
  )

  const summary = useMemo(() => summarize(rows, mode), [rows, mode])
  const postLevel = useMemo(
    () => ({
      blockers: (preflight?.blockers ?? []).filter((f) => !f.targetId),
      warnings: (preflight?.warnings ?? []).filter((f) => !f.targetId),
    }),
    [preflight]
  )
  const observedAt = useMemo(() => {
    const newest = (metrics?.targets ?? [])
      .map((t) => t.latest?.observedAt)
      .filter((v): v is string => Boolean(v))
      .sort()
      .at(-1)
    return newest ?? null
  }, [metrics])
  const withNumbers = rows.filter((r) => r.metrics && !r.metrics.latest?.unavailable)

  return {
    mode,
    rows,
    summary,
    loading,
    loadError,
    saving,
    retrying,
    locked,
    revertsOnEdit: approvedOrLater,
    editing,
    postLevel,
    totals: withNumbers.length > 1 ? (metrics?.totals ?? null) : null,
    observedAt,
    unreportedNote: withNumbers.length > 0 ? unreportedNote(withNumbers.map((r) => r.option.platform)) : null,
    consentError,
    consentSaving,
    actions,
    tiktokTargets,
    tiktokBlockedReason,
    failedCount: selected.filter((t) => t.state === 'FAILED').length,
    awaitingCount: selected.filter((t) => t.state === 'AWAITING_MANUAL').length,
  }
}
