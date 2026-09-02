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

// Capabilities held by CREATOR (the "write" role). ADMIN holds these plus the admin-only set.
const CREATOR_CAPABILITIES: Capability[] = [
  'workflow.manage',
  'workflow.run',
  'integration.manage',
  'agent.manage',
  'doc.edit',
  'issue.edit',
  'issue.assignReviewers',
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
  ADMIN: [...ADMIN_ONLY_CAPABILITIES, ...CREATOR_CAPABILITIES],
  CREATOR: CREATOR_CAPABILITIES,
  REVIEWER: [], // read-only: every resource is viewable; REVIEWER holds no mutating capability
}

/** True if the given role holds the capability. Unknown/undefined role → false. */
export function can(role: MemberRole | null | undefined, capability: Capability): boolean {
  if (!role) return false
  return ROLE_CAPABILITIES[role].includes(capability)
}

/** All capabilities held by a role — useful for future per-section nav gating. */
export function capabilitiesFor(role: MemberRole | null | undefined): Capability[] {
  if (!role) return []
  return ROLE_CAPABILITIES[role]
}
