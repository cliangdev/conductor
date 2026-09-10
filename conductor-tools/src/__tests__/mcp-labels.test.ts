import { describe, it, expect } from 'vitest'
import { titleCase, stateLabel, statusLabel, STATE_LABELS } from '../mcp/tools/labels.js'

describe('titleCase', () => {
  it('turns a SCREAMING_SNAKE code into words', () => {
    expect(titleCase('IN_REVIEW')).toBe('In Review')
    expect(titleCase('PUBLISHED')).toBe('Published')
    expect(titleCase('AWAITING_MANUAL')).toBe('Awaiting Manual')
  })
})

describe('stateLabel', () => {
  it('uses the known publish-target state labels verbatim', () => {
    expect(stateLabel('HANDED_OFF')).toBe(STATE_LABELS.HANDED_OFF)
    expect(stateLabel('AWAITING_MANUAL')).toBe('Post it now')
    expect(stateLabel('PENDING')).toBe('Waiting')
  })

  it('falls back to Title Case for an unrecognized state', () => {
    expect(stateLabel('SOME_NEW_STATE')).toBe('Some New State')
  })

  it('returns undefined for no state', () => {
    expect(stateLabel(undefined)).toBeUndefined()
    expect(stateLabel(null)).toBeUndefined()
  })
})

describe('statusLabel', () => {
  it('prefers the Workflow statuses label when the id matches', () => {
    const statuses = [{ id: 'IN_REVIEW', label: 'Awaiting Approval' }]
    expect(statusLabel('IN_REVIEW', statuses)).toBe('Awaiting Approval')
  })

  it('falls back to Title Case when no Workflow statuses are given, or none match', () => {
    expect(statusLabel('IN_REVIEW')).toBe('In Review')
    expect(statusLabel('IN_REVIEW', [{ id: 'DRAFT', label: 'Draft' }])).toBe('In Review')
  })

  it('returns undefined for no status', () => {
    expect(statusLabel(undefined)).toBeUndefined()
  })
})
