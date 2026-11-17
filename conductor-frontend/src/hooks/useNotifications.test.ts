import { describe, it, expect } from 'vitest'
import {
  ALL_EVENT_TYPES,
  CHANNEL_GROUPS,
  EVENT_TYPE_DESCRIPTIONS,
  EVENT_TYPE_SUBTITLES,
} from './useNotifications'

describe('comment notification events', () => {
  const NEW_EVENTS = ['DOC_COMMENT_ADDED', 'DOC_COMMENT_REPLY', 'COMMENT_MENTIONED']

  it('are listed with plain labels and subtitles', () => {
    for (const e of NEW_EVENTS) {
      expect(ALL_EVENT_TYPES).toContain(e)
      expect(EVENT_TYPE_SUBTITLES[e]).toBeTruthy()
    }
    expect(EVENT_TYPE_DESCRIPTIONS.COMMENT_MENTIONED).toBe('Someone is mentioned in a comment')
  })

  it('sit in the Issues group, next to the other comment events', () => {
    const issues = CHANNEL_GROUPS.find((g) => g.value === 'ISSUES')!
    for (const e of ['COMMENT_ADDED', 'COMMENT_REPLY', ...NEW_EVENTS]) {
      expect(issues.eventTypes).toContain(e)
    }
  })
})
