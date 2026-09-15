'use client'

// The compose page's destinations: the same rows and the same actions as a Post's, held locally until
// the Post exists. Only the project's options are fetched; everything else is a draft the page turns
// into the set-replace payload once the Post is created. No consent, no metrics, no outcomes — a Post
// that does not exist yet has none.

import { useEffect, useMemo, useState } from 'react'
import { apiErrorMessage, apiGet } from '@/lib/api'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'
import { buildRows, summarize, type DestinationActions, type DestinationsState } from './model'
import { EMPTY_DRAFT, type DestinationDraft } from './selectionState'
import type { PublishTargetOption } from './types'

export interface DraftDestinationsState extends DestinationsState {
  options: PublishTargetOption[]
  draft: DestinationDraft
}

export function useDraftDestinations({
  projectId,
  token,
  assets = [],
}: {
  projectId: string
  token: string
  /** Local files have no asset ids yet, so the per-destination media picker stays empty here. */
  assets?: MediaAsset[]
}): DraftDestinationsState {
  const [options, setOptions] = useState<PublishTargetOption[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [draft, setDraft] = useState<DestinationDraft>(EMPTY_DRAFT)

  useEffect(() => {
    let cancelled = false
    apiGet<PublishTargetOption[]>(`/api/v2/projects/${projectId}/publish-targets`, token)
      .then((list) => {
        if (cancelled) return
        setOptions(list)
        setLoadError(null)
      })
      .catch((err) => {
        if (!cancelled) setLoadError(apiErrorMessage(err, 'Could not load the accounts to publish to'))
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [projectId, token])

  const rows = useMemo(
    () =>
      buildRows({
        options,
        selected: [],
        draft,
        preflight: null,
        metrics: null,
        consent: null,
        approvedOrLater: false,
        mode: 'pick',
        assets,
      }),
    [options, draft, assets]
  )

  const actions = useMemo<DestinationActions>(
    () => ({
      toggle: (row) =>
        setDraft((d) => {
          const keys = new Set(d.keys)
          if (keys.has(row.key)) keys.delete(row.key)
          else keys.add(row.key)
          return { ...d, keys }
        }),
      setFormat: (row, next) => setDraft((d) => ({ ...d, formats: { ...d.formats, [row.key]: next } })),
      setTikTokOptions: (row, next) => setDraft((d) => ({ ...d, tiktok: { ...d.tiktok, [row.key]: next } })),
      setInstagramOptions: (row, next) => setDraft((d) => ({ ...d, instagram: { ...d.instagram, [row.key]: next } })),
      setYouTubeOptions: (row, next) => setDraft((d) => ({ ...d, youtube: { ...d.youtube, [row.key]: next } })),
      setContent: (row, next) => setDraft((d) => ({ ...d, content: { ...d.content, [row.key]: next } })),
      retry: async () => {},
      completeManual: async () => {},
      setConsent: async () => {},
      editDestinations: () => {},
    }),
    []
  )

  return {
    mode: 'pick',
    rows,
    summary: summarize(rows, 'pick'),
    loading,
    loadError,
    saving: false,
    retrying: false,
    locked: false,
    revertsOnEdit: false,
    editing: false,
    postLevel: { blockers: [], warnings: [] },
    totals: null,
    observedAt: null,
    unreportedNote: null,
    consentError: null,
    consentSaving: false,
    actions,
    options,
    draft,
  }
}
