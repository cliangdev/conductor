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

/**
 * The valid next moves for a Work Item, computed server-side from the active Workflow definition
 * (GET .../available-transitions) — never a hardcoded table — so a review-gated transition stays hidden
 * until its Review is satisfied. REVIEWERs never fetch: they have no moves of their own to make.
 * Re-reads whenever the item's status changes.
 */
export function useAvailableTransitions(
  projectId: string,
  issueId: string,
  currentStatus: string,
  userRole: UserRole,
  token: string
): AvailableTransition[] {
  const [transitions, setTransitions] = useState<AvailableTransition[]>([])

  useEffect(() => {
    if (userRole === 'REVIEWER' || !token) return
    let cancelled = false
    apiGet<AvailableTransitionsResponse>(
      `/api/v2/projects/${projectId}/work-items/${issueId}/available-transitions`,
      token
    )
      .then((res) => {
        if (!cancelled) setTransitions(res.transitions ?? [])
      })
      .catch(() => {
        if (!cancelled) setTransitions([])
      })
    return () => {
      cancelled = true
    }
  }, [projectId, issueId, currentStatus, userRole, token])

  return transitions
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
