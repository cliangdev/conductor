import { describe, it, expect } from 'vitest'
import { can, capabilitiesFor, memberRoles, type Capability } from './permissions'

const EDIT_CAPABILITIES: Capability[] = [
  'workflow.manage',
  'workflow.run',
  'integration.manage',
  'agent.manage',
  'doc.edit',
  'issue.edit',
  'issue.assignReviewers',
  'creative.manage',
  'marketing.target.manage',
  'milestone.manage',
]

const ADMIN_ONLY: Capability[] = [
  'workspace.manage',
  'members.manage',
  'notifications.manage',
  'integration.appCredential.manage',
]

const ALL_CAPABILITIES: Capability[] = [...EDIT_CAPABILITIES, ...ADMIN_ONLY, 'review.submit']

describe('can', () => {
  it('grants ADMIN every capability, including review', () => {
    for (const cap of ALL_CAPABILITIES) {
      expect(can(['ADMIN'], cap)).toBe(true)
    }
  })

  it('grants CREATOR write capabilities but not admin-only ones or review', () => {
    for (const cap of EDIT_CAPABILITIES) expect(can(['CREATOR'], cap)).toBe(true)
    for (const cap of ADMIN_ONLY) expect(can(['CREATOR'], cap)).toBe(false)
    expect(can(['CREATOR'], 'review.submit')).toBe(false)
  })

  it('grants REVIEWER only review.submit', () => {
    for (const cap of ALL_CAPABILITIES) {
      expect(can(['REVIEWER'], cap)).toBe(cap === 'review.submit')
    }
  })

  it('unions the capabilities of every role held', () => {
    const both = ['CREATOR', 'REVIEWER'] as const
    for (const cap of EDIT_CAPABILITIES) expect(can(both, cap)).toBe(true)
    expect(can(both, 'review.submit')).toBe(true)
    expect(can(both, 'members.manage')).toBe(false)
  })

  it('grants nothing to an empty set (Viewer) or an undefined/null set', () => {
    for (const cap of ALL_CAPABILITIES) expect(can([], cap)).toBe(false)
    expect(can(undefined, 'workflow.manage')).toBe(false)
    expect(can(null, 'workflow.run')).toBe(false)
  })
})

describe('capabilitiesFor', () => {
  it('returns the full set for ADMIN, review only for REVIEWER, and empty for none', () => {
    expect(capabilitiesFor(['ADMIN'])).toHaveLength(ALL_CAPABILITIES.length)
    expect(capabilitiesFor(['REVIEWER'])).toEqual(['review.submit'])
    expect(capabilitiesFor([])).toHaveLength(0)
    expect(capabilitiesFor(undefined)).toHaveLength(0)
  })

  it('does not repeat a capability held through several roles', () => {
    const caps = capabilitiesFor(['ADMIN', 'CREATOR', 'REVIEWER'])
    expect(new Set(caps).size).toBe(caps.length)
  })
})

describe('memberRoles', () => {
  it('prefers roles, falls back to the legacy role, and returns [] for neither', () => {
    expect(memberRoles({ roles: ['CREATOR', 'REVIEWER'], role: 'ADMIN' })).toEqual(['CREATOR', 'REVIEWER'])
    expect(memberRoles({ roles: [] })).toEqual([])
    expect(memberRoles({ role: 'CREATOR' })).toEqual(['CREATOR'])
    expect(memberRoles({})).toEqual([])
    expect(memberRoles(undefined)).toEqual([])
  })
})
