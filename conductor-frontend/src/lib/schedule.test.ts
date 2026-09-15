import { describe, expect, it } from 'vitest'
import { instantToWallClock, nextSlot, wallClockToInstant } from './schedule'

describe('schedule helpers', () => {
  it('nextSlot rounds up to the next five-minute mark at or after the instant', () => {
    expect(nextSlot(new Date('2026-09-04T12:01:01Z')).toISOString()).toBe('2026-09-04T12:05:00.000Z')
    expect(nextSlot(new Date('2026-09-04T12:15:00Z')).toISOString()).toBe('2026-09-04T12:15:00.000Z')
  })

  it('wallClockToInstant reads a wall-clock time in its zone, DST included', () => {
    expect(wallClockToInstant('2026-07-01T09:00', 'Europe/Berlin')).toBe('2026-07-01T07:00:00.000Z')
    expect(wallClockToInstant('2026-01-15T09:00', 'Europe/Berlin')).toBe('2026-01-15T08:00:00.000Z')
    expect(wallClockToInstant('', 'UTC')).toBeNull()
  })

  it('round-trips an instant back to the wall clock it was authored as', () => {
    const iso = wallClockToInstant('2026-11-02T17:30', 'Europe/Berlin')!
    expect(instantToWallClock(iso, 'Europe/Berlin')).toBe('2026-11-02T17:30')
  })
})
