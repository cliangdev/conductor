import { describe, it, expect } from 'vitest'
import { SETTINGS_NAV, visibleNavEntries } from './navigation'
import { can } from './permissions'

describe('SETTINGS_NAV gating', () => {
  const keysFor = (role: 'ADMIN' | 'CREATOR' | 'REVIEWER') =>
    visibleNavEntries(SETTINGS_NAV, (cap) => can([role], cap)).map((e) => e.key)

  it('gates Milestones on the capability the backend enforces', () => {
    expect(SETTINGS_NAV.find((e) => e.key === 'settings-milestones')?.permission).toBe('milestone.manage')
  })

  it('shows Milestones to ADMIN and CREATOR but not REVIEWER', () => {
    expect(keysFor('ADMIN')).toContain('settings-milestones')
    expect(keysFor('CREATOR')).toContain('settings-milestones')
    expect(keysFor('REVIEWER')).not.toContain('settings-milestones')
  })
})
