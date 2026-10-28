// Pure helpers behind the Posts bulk actions: what counts as live, what can be retried, and the
// schedule / tag arithmetic. Kept apart from the dialogs so the rules are unit-testable.

import { instantToWallClock, wallClockToInstant } from '@/lib/schedule'
import type { Issue } from '@/components/workitems/listTypes'

type PostLike = Pick<Issue, 'status' | 'publishTargets' | 'externalLinks' | 'enrichmentFailed'>

/**
 * Whether we cannot tell if a Post is live: the list did not enrich it (no `publishTargets` at all, as for a
 * response that predates the field) or said enrichment failed for it. Such a Post must be treated as
 * possibly live — never as one that is not — by anything destructive.
 */
export function isLiveUnknown(post: Pick<Issue, 'publishTargets' | 'enrichmentFailed'>): boolean {
  return post.enrichmentFailed === true || post.publishTargets === undefined || post.publishTargets === null
}

/**
 * The calendar date (`YYYY-MM-DD`) it is at `nowMs` in `timeZone` — the earliest day a schedule picker should
 * offer, which is not the UTC date: at 20:00 in Los Angeles it is already tomorrow in UTC, and at 08:00 in
 * Auckland it is still yesterday there.
 */
export function todayInZone(nowMs: number, timeZone: string): string {
  try {
    return new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(nowMs))
  } catch {
    return new Date(nowMs).toISOString().slice(0, 10)
  }
}

/**
 * A Post that is out in the world: any destination PUBLISHED, a recorded live link, or a status the
 * Workflow names Published/Live. Bulk delete leaves these out (standing rule: never delete live Posts).
 */
export function isLivePost(post: PostLike): boolean {
  if ((post.publishTargets ?? []).some((t) => t.state === 'PUBLISHED')) return true
  if ((post.externalLinks ?? []).length > 0) return true
  const status = post.status.toUpperCase()
  return status === 'PUBLISHED' || status === 'LIVE'
}

/**
 * A Post whose schedule must not be changed in bulk: the same rule the single-Post schedule field and the
 * backend apply. A schedule PATCH is allowed in every status short of published (a Scheduled Post re-times
 * in place; an Approved or In Review one just takes the new time), and refused once it is live.
 */
export function isRescheduleLocked(post: PostLike): boolean {
  return isLivePost(post)
}

/**
 * A Post whose destinations must not be replaced in bulk: it is live, or already approved or
 * handed to a platform. Unlike a schedule change, PUT .../publish-targets revokes the hand-off and reverts the
 * Post to review first, a side effect nobody should get from a bulk action.
 */
export function isEditLocked(post: PostLike): boolean {
  if (isLivePost(post) || isLiveUnknown(post)) return true
  const status = post.status.toUpperCase()
  if (status === 'APPROVED' || status === 'SCHEDULED' || status === 'PUBLISHING') return true
  return (post.publishTargets ?? []).some((t) =>
    ['HANDED_OFF', 'PUBLISHING', 'AWAITING_MANUAL', 'PUBLISHED'].includes(t.state),
  )
}

/** True when some destination of this Post failed to publish. */
export function hasFailedTarget(post: Pick<Issue, 'publishTargets'>): boolean {
  return (post.publishTargets ?? []).some((t) => t.state === 'FAILED')
}

/**
 * `iso` moved by `days` calendar days, keeping the wall-clock time in the zone it was authored in (so
 * "9am" stays 9am across a DST change). Null when there is nothing to shift or the zone/date is unusable.
 */
export function shiftSchedule(iso: string | null | undefined, timeZone: string | null | undefined, days: number): string | null {
  if (!iso) return null
  const zone = timeZone || 'UTC'
  let wall: string
  try {
    wall = instantToWallClock(iso, zone)
  } catch {
    return null
  }
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(wall)
  if (!m) return null
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + days))
  const pad = (n: number) => String(n).padStart(2, '0')
  const shifted = `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())}T${m[4]}:${m[5]}`
  return wallClockToInstant(shifted, zone)
}

/** Free text ("Autumn, launch") as the stored form: trimmed, lower-cased, de-duplicated, no blanks. */
export function parseTags(input: string): string[] {
  return [...new Set(input.split(/[,\n]/).map((t) => t.trim().toLowerCase()).filter(Boolean))]
}

/** True when applying the tag change would actually alter the Post's tags (content, not identity). */
export function tagsDiffer(current: string[] | undefined, change: string[], mode: 'add' | 'remove'): boolean {
  const base = current ?? []
  const next = applyTagChange(base, change, mode)
  return next.length !== base.length || next.some((t, i) => t !== base[i])
}

/** `current` with `change` added or removed; the same array reference when nothing changes. */
export function applyTagChange(current: string[] | undefined, change: string[], mode: 'add' | 'remove'): string[] {
  const base = current ?? []
  const next = mode === 'add' ? [...new Set([...base, ...change])] : base.filter((t) => !change.includes(t))
  return next.length === base.length && next.every((t, i) => t === base[i]) ? base : next
}
