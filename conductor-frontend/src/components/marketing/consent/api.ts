// The TikTok consent page's wire: the one-shot page read and the atomic per-Post consent write.
// Both need a person in their own signed-in session (user JWT in the Authorization header), which
// apiGet / apiPut always send. Errors are RFC 7807 and surface as ApiError (`status`, `detail`).

import { apiGet, apiPut } from '@/lib/api'
import type { PublishConsentState, PublishConsentVerdict } from '@/components/marketing/TikTokConsentStep'

/** The declaration wording the creator agrees to; recorded server-side with every consent. */
export const TIKTOK_DECLARATION_VERSION = 'tiktok-csg-2026-10'

export type ConsentChannel = 'POST_PAGE' | 'BULK_PAGE'

/** The six choices the creator makes personally. `disable*: true` means NOT allowed. */
export interface TikTokConsentChoices {
  privacyLevel: string
  disableComment: boolean
  disableDuet: boolean
  disableStitch: boolean
  brandContentToggle: boolean
  brandOrganicToggle: boolean
}

export interface ConsentPageTarget {
  targetId: string
  connectionId: string
  format?: string | null
  effectiveAssetIds: string[]
  captionOverride?: string | null
  effectiveCaption?: string | null
  /** The stored choices; present only while the Post's verdict is VALID. */
  consented?: TikTokConsentChoices | null
}

export interface ConsentPagePost {
  workItemId: string
  displayId: string
  title: string
  caption?: string | null
  scheduledAt?: string | null
  verdict: PublishConsentVerdict
  etag: string
  consentedAt?: string | null
  consentedByUserId?: string | null
  consentedByName?: string | null
  targets: ConsentPageTarget[]
}

export interface ConsentPageCreator {
  connectionId: string
  nickname?: string | null
  username?: string | null
  avatarUrl?: string | null
  privacyLevelOptions: string[]
  commentDisabled: boolean
  duetDisabled: boolean
  stitchDisabled: boolean
  maxVideoPostDurationSec?: number | null
  canPost: boolean
  cannotPostReason?: string | null
  reconnectRequired: boolean
}

export interface TikTokConsentPage {
  requestId?: string | null
  expiresAt?: string | null
  posts: ConsentPagePost[]
  creators: ConsentPageCreator[]
}

export function consentPagePath(projectId: string, requestId?: string | null): string {
  const base = `/api/v2/projects/${projectId}/tiktok-consent`
  return requestId ? `${base}?request=${encodeURIComponent(requestId)}` : base
}

export function fetchConsentPage(
  projectId: string,
  token: string,
  requestId?: string | null
): Promise<TikTokConsentPage> {
  return apiGet<TikTokConsentPage>(consentPagePath(projectId, requestId), token)
}

export function fetchConsentSummary(projectId: string, token: string): Promise<{ pendingCount: number }> {
  return apiGet<{ pendingCount: number }>(`/api/v2/projects/${projectId}/tiktok-consent/summary`, token)
}

export interface RecordTikTokConsentBody {
  targets: Array<TikTokConsentChoices & { connectionId: string }>
  channel: ConsentChannel
  requestId?: string
}

/** The write; `etag` is sent exactly as the server returned it, quotes included. */
export function recordTikTokConsent(
  projectId: string,
  workItemId: string,
  etag: string,
  body: RecordTikTokConsentBody,
  token: string
): Promise<PublishConsentState> {
  return apiPut<PublishConsentState>(
    `/api/v2/projects/${projectId}/work-items/${workItemId}/tiktok-consent`,
    { ...body, declarationVersion: TIKTOK_DECLARATION_VERSION },
    token,
    { 'If-Match': etag }
  )
}
