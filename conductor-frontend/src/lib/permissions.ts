import type { MemberRole } from '@/types'

/**
 * Capability-based permissions.
 *
 * The UI gates on *capabilities*, never on role string comparisons scattered across
 * components. Roles map to capability sets here — so adding a future SaaS role (e.g. an
 * "OPERATOR" who can run workflows but not edit them) is a single edit to this file, with
 * zero changes to navigation, routes, or component gating.
 */
export type Capability =
  | 'workspace.manage' // rename/delete the workspace
  | 'members.manage' // invite/remove members, change roles
  | 'notifications.manage' // edit notification channels
  | 'workflow.manage' // create/edit/delete/enable workflows
  | 'workflow.run' // dispatch a workflow run
  | 'integration.manage' // connect/disconnect integrations
  | 'integration.appCredential.manage' // set/clear the platform OAuth app a connector runs as
  | 'agent.manage' // create/edit/delete agents, manage provider keys
  | 'doc.edit' // create/rename/delete/edit docs
  | 'issue.edit' // change issue status / edit issues
  | 'issue.assignReviewers' // assign or unassign reviewers on an issue
  | 'review.submit' // approve / request changes / comment on a Work Item as a reviewer
  | 'creative.manage' // create/edit/delete brand kits, Creative photos, and Creatives
  | 'marketing.target.manage' // set/clear a marketing overview metric target

// Capabilities held by CREATOR (the "write" role). ADMIN holds these plus the admin-only set.
const CREATOR_CAPABILITIES: Capability[] = [
  'workflow.manage',
  'workflow.run',
  'integration.manage',
  'agent.manage',
  'doc.edit',
  'issue.edit',
  'issue.assignReviewers',
  'creative.manage',
  'marketing.target.manage',
]

const ADMIN_ONLY_CAPABILITIES: Capability[] = [
  'workspace.manage',
  'members.manage',
  'notifications.manage',
  // Deciding which platform application every member's consent flow runs as is an admin call —
  // the backend endpoints gate on ADMIN too.
  'integration.appCredential.manage',
]

const ROLE_CAPABILITIES: Record<MemberRole, Capability[]> = {
  // ADMIN implies every other role, so it holds the union of all of them.
  ADMIN: [...ADMIN_ONLY_CAPABILITIES, ...CREATOR_CAPABILITIES, 'review.submit'],
  CREATOR: CREATOR_CAPABILITIES,
  REVIEWER: ['review.submit'], // can review; every resource is viewable but nothing is mutable
}

/** UI names for the stored roles. The enum values never change; CREATOR is shown as "Editor". */
export const ROLE_LABELS: Record<MemberRole, string> = {
  ADMIN: 'Admin',
  CREATOR: 'Editor',
  REVIEWER: 'Reviewer',
}

/** Display order for a set of roles: Admin first, then Editor, then Reviewer. */
export const ROLE_ORDER: MemberRole[] = ['ADMIN', 'CREATOR', 'REVIEWER']

/** UI name for a stored role string; an unknown string passes through unchanged. */
export function roleName(role: string): string {
  return (ROLE_LABELS as Record<string, string>)[role] ?? role
}

/** The roles in display order (unknown strings last). */
export function sortRoles<T extends string>(roles: readonly T[]): T[] {
  const rank = (r: string) => {
    const i = (ROLE_ORDER as string[]).indexOf(r)
    return i === -1 ? ROLE_ORDER.length : i
  }
  return [...roles].sort((a, b) => rank(a) - rank(b))
}

/** The roles a member/invite/project summary holds. Falls back to the legacy single `role` from an older server. */
export function memberRoles(m: { roles?: MemberRole[] | null; role?: MemberRole | null } | null | undefined): MemberRole[] {
  if (!m) return []
  return m.roles ?? (m.role ? [m.role] : [])
}

/** True if the held roles grant the capability (union over every role held). Empty/undefined → false. */
export function can(roles: readonly MemberRole[] | null | undefined, capability: Capability): boolean {
  if (!roles) return false
  return roles.some((r) => ROLE_CAPABILITIES[r]?.includes(capability))
}

/** All capabilities granted by a set of roles — useful for future per-section nav gating. */
export function capabilitiesFor(roles: readonly MemberRole[] | null | undefined): Capability[] {
  if (!roles) return []
  return Array.from(new Set(roles.flatMap((r) => ROLE_CAPABILITIES[r] ?? [])))
}
