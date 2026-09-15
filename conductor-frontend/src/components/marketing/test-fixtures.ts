// Builders shared by the Post surface tests: a publishing Workflow view, destination options and
// selections in the shapes the server sends, and a minimal fetch Response stand-in.

import type { WorkflowView } from '@/types/workItem'
import type { PublishTargetOption, SelectedPublishTarget } from './destinations/types'

export const MARKETING_VIEW: WorkflowView = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'MARKETING',
  defaultView: 'list',
  version: 1,
  types: ['POST'],
  assetTypes: ['facebook_post', 'instagram_post', 'youtube_video', 'tiktok_post'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'in_progress' },
    { id: 'APPROVED', label: 'Approved', category: 'in_progress' },
  ],
  transitions: [
    { from: 'DRAFT', to: 'IN_REVIEW', label: 'Submit' },
    { from: 'IN_REVIEW', to: 'APPROVED', label: 'Approve', requiresReview: true },
  ],
}

export function option(
  overrides: Partial<PublishTargetOption> & Pick<PublishTargetOption, 'platform' | 'connectionId'>
): PublishTargetOption {
  return {
    connectorId:
      overrides.platform === 'facebook' || overrides.platform === 'instagram' ? 'meta' : overrides.platform,
    label: overrides.connectionId ?? 'Manual',
    lane: overrides.platform === 'facebook' || overrides.platform === 'youtube' ? 'NATIVE' : 'APP_MANAGED',
    ...overrides,
  }
}

export function tiktokOption(
  connectionId: string,
  overrides: Partial<PublishTargetOption> = {}
): PublishTargetOption {
  return option({
    platform: 'tiktok',
    connectionId,
    label: `@${connectionId}`,
    creatorNickname: connectionId,
    privacyLevelOptions: ['PUBLIC_TO_EVERYONE', 'MUTUAL_FOLLOW_FRIENDS', 'SELF_ONLY'],
    ...overrides,
  })
}

/** A destination a human publishes by hand: no account, no connector, always offered. */
export function manualOption(platform: PublishTargetOption['platform']): PublishTargetOption {
  const labels: Record<string, string> = {
    facebook: 'Facebook (manual)',
    instagram: 'Instagram (manual)',
    youtube: 'YouTube (manual)',
    tiktok: 'TikTok (manual)',
  }
  return {
    platform,
    connectorId: null,
    connectionId: null,
    label: labels[platform],
    lane: 'MANUAL',
  }
}

export function selection(
  o: PublishTargetOption,
  workItemId: string,
  id = `target-${o.platform}-${o.connectionId}`
): SelectedPublishTarget {
  return {
    id,
    workItemId,
    platform: o.platform,
    connectorId: o.connectorId,
    connectionId: o.connectionId,
    label: o.label,
    lane: o.lane,
    state: 'PENDING',
  }
}

export function jsonResponse(status: number, body: unknown) {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body,
  }
}
