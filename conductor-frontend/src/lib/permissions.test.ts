import { describe, it, expect } from 'vitest'
import { can, capabilitiesFor, type Capability } from './permissions'

const ALL_CAPABILITIES: Capability[] = [
  'workspace.manage',
  'members.manage',
  'notifications.manage',
  'workflow.manage',
  'workflow.run',
  'integration.manage',
  'agent.manage',
  'doc.edit',
  'issue.edit',
  'issue.assignReviewers',
]

describe('can', () => {
  it('grants ADMIN every capability', () => {
    for (const cap of ALL_CAPABILITIES) {
      expect(can('ADMIN', cap)).toBe(true)
    }
  })

  it('grants CREATOR write capabilities but not admin-only ones', () => {
    expect(can('CREATOR', 'workflow.manage')).toBe(true)
    expect(can('CREATOR', 'workflow.run')).toBe(true)
    expect(can('CREATOR', 'integration.manage')).toBe(true)
    expect(can('CREATOR', 'agent.manage')).toBe(true)
    expect(can('CREATOR', 'doc.edit')).toBe(true)
    expect(can('CREATOR', 'issue.edit')).toBe(true)
    expect(can('CREATOR', 'issue.assignReviewers')).toBe(true)

    expect(can('CREATOR', 'workspace.manage')).toBe(false)
    expect(can('CREATOR', 'members.manage')).toBe(false)
    expect(can('CREATOR', 'notifications.manage')).toBe(false)
  })

  it('grants REVIEWER no mutating capability', () => {
    for (const cap of ALL_CAPABILITIES) {
      expect(can('REVIEWER', cap)).toBe(false)
    }
  })

  it('returns false for an undefined/null role', () => {
    expect(can(undefined, 'workflow.manage')).toBe(false)
    expect(can(null, 'workflow.run')).toBe(false)
  })
})

describe('capabilitiesFor', () => {
  it('returns the full set for ADMIN and empty for REVIEWER/none', () => {
    expect(capabilitiesFor('ADMIN')).toHaveLength(ALL_CAPABILITIES.length)
    expect(capabilitiesFor('REVIEWER')).toHaveLength(0)
    expect(capabilitiesFor(undefined)).toHaveLength(0)
  })
})
