// Wall-clock ↔ instant conversions for "when should this go out?", and the calendar-slot rounding the
// compose flow uses. One copy: the schedule field and the compose flow used to each carry their own.

/** The viewer's own zone, the only sensible default for "when should this go out?". */
export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

/**
 * How far `timeZone` is from UTC at `ts`, in milliseconds. Read out of Intl rather than from a table:
 * it is the only source that knows this zone's rules on this date, DST included.
 */
export function offsetAt(ts: number, timeZone: string): number {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone,
      hour12: false,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    })
      .formatToParts(new Date(ts))
      .map((p) => [p.type, p.value])
  ) as Record<string, string>
  const asUtc = Date.UTC(
    Number(parts['year']),
    Number(parts['month']) - 1,
    Number(parts['day']),
    Number(parts['hour']) % 24,
    Number(parts['minute']),
    Number(parts['second'])
  )
  return asUtc - ts
}

/**
 * A wall-clock `YYYY-MM-DDTHH:mm` read in `timeZone`, as an ISO instant.
 *
 * Applied twice on purpose. The first pass has to guess an offset from a timestamp that is itself still
 * wrong, so near a DST change the guess can be an hour out; correcting once more with the offset at the
 * corrected instant settles it. Converging rather than assuming is what keeps "9am on the day the clocks
 * go forward" meaning 9am.
 */
export function wallClockToInstant(local: string, timeZone: string): string | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(local)
  if (!m) return null
  const [, y, mo, d, h, mi] = m.map(Number) as unknown as number[]
  const naive = Date.UTC(y!, mo! - 1, d!, h!, mi!)
  let ts = naive
  for (let i = 0; i < 2; i++) ts = naive - offsetAt(ts, timeZone)
  const out = new Date(ts)
  return Number.isNaN(out.getTime()) ? null : out.toISOString()
}

/** The inverse: an instant, as the wall clock a `datetime-local` input shows, read in `timeZone`. */
export function instantToWallClock(iso: string, timeZone: string): string {
  const ts = new Date(iso).getTime()
  if (Number.isNaN(ts)) return ''
  const shifted = new Date(ts + offsetAt(ts, timeZone))
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())}` +
    `T${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}`
  )
}

/** The next five-minute mark at or after `earliest` — a tidy calendar slot rather than 14:07. */
export function nextSlot(earliest: Date): Date {
  const slot = new Date(earliest.getTime())
  slot.setSeconds(0, 0)
  slot.setMinutes(Math.ceil(slot.getMinutes() / 5) * 5)
  if (slot.getTime() < earliest.getTime()) slot.setMinutes(slot.getMinutes() + 5)
  return slot
}
