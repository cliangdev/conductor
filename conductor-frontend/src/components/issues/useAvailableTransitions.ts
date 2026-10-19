'use client'

import { useEffect, useState } from 'react'
import { apiGet } from '@/lib/api'
import { statusHasReviewGate } from '@/lib/workflows'
import { useTikTokPublishGate } from '@/components/marketing/TikTokConsentStep'
import type { WorkflowView } from '@/types/workItem'

export type UserRole = 'ADMIN' | 'CREATOR' | 'REVIEWER'

export interface AvailableTransition {
  toStatus: string
  label: string
  requiresReview?: boolean
}

interface AvailableTransitionsResponse {
  workflow: string
  currentStatus: string
  noun?: string
  transitions: AvailableTransition[]
}

// The status control in the properties panel and the header's More menu both ask for the same item's
// moves in the same render. Sharing the in-flight request (keyed by item + status, dropped once it
// settles) turns the two identical GETs into one, while a later status change still re-reads.
const transitionsInFlight = new Map<string, Promise<AvailableTransitionsResponse>>()

function fetchAvailableTransitions(
  projectId: string,
  issueId: string,
  currentStatus: string,
  token: string
): Promise<AvailableTransitionsResponse> {
  const key = `${projectId}|${issueId}|${currentStatus}`
  const pending = transitionsInFlight.get(key)
  if (pending) return pending
  const promise = apiGet<AvailableTransitionsResponse>(
    `/api/v2/projects/${projectId}/work-items/${issueId}/available-transitions`,
    token
  ).finally(() => {
    if (transitionsInFlight.get(key) === promise) transitionsInFlight.delete(key)
  })
  transitionsInFlight.set(key, promise)
  return promise
}

export interface AvailableTransitionsState {
  transitions: AvailableTransition[]
  /** True while a read for the current status is in flight and no result is held yet. */
  loading: boolean
  /** True when the last read failed. */
  error: boolean
}

interface Loaded {
  status: string
  transitions: AvailableTransition[]
  error: boolean
}

/**
 * The valid next moves for a Work Item, computed server-side from the active Workflow definition
 * (GET .../available-transitions) — never a hardcoded table — so a review-gated transition stays hidden
 * until its Review is satisfied. REVIEWERs never fetch: they have no moves of their own to make.
 * Re-reads whenever the item's status changes. `enabled` (default true) defers the read: list rows and
 * board cards pass their menu's open state so a page of N items makes no requests until one is opened.
 * Each time `enabled` turns on the moves are re-read, so a gate satisfied meanwhile is picked up.
 */
export function useAvailableTransitionsState(
  projectId: string,
  issueId: string,
  currentStatus: string,
  userRole: UserRole,
  token: string,
  enabled = true
): AvailableTransitionsState {
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const active = enabled && userRole !== 'REVIEWER' && !!token

  useEffect(() => {
    if (!active) return
    let cancelled = false
    fetchAvailableTransitions(projectId, issueId, currentStatus, token)
      .then((res) => {
        if (!cancelled) setLoaded({ status: currentStatus, transitions: res.transitions ?? [], error: false })
      })
      .catch(() => {
        if (!cancelled) setLoaded({ status: currentStatus, transitions: [], error: true })
      })
    return () => {
      cancelled = true
    }
  }, [projectId, issueId, currentStatus, active, token])

  // A result for an earlier status is never shown for the current one.
  const current = loaded && loaded.status === currentStatus ? loaded : null
  return {
    transitions: current?.transitions ?? [],
    loading: active && !current,
    error: current?.error ?? false,
  }
}

export function useAvailableTransitions(
  projectId: string,
  issueId: string,
  currentStatus: string,
  userRole: UserRole,
  token: string,
  enabled = true
): AvailableTransition[] {
  return useAvailableTransitionsState(projectId, issueId, currentStatus, userRole, token, enabled).transitions
}

/**
 * Why a move to a given status would be refused right now, or null when it would not: the TikTok
 * consent gate for any move into a review-gated status (TIK-4, published by TikTokConsentStep's
 * provider), else the publish gate's own reason for that status (`blockedMoves`, from the preflight).
 * Every status control on a page reads this so they all disable the same move for the same reason.
 */
export function useBlockedReason(
  view: WorkflowView | undefined,
  blockedMoves: Record<string, string> | undefined
): (toStatus: string) => string | null {
  const tiktokBlock = useTikTokPublishGate()
  return (toStatus: string) => {
    if (tiktokBlock && statusHasReviewGate(view, toStatus)) return tiktokBlock
    return blockedMoves?.[toStatus] ?? null
  }
}
