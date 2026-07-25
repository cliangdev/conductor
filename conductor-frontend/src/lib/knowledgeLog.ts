export type KnowledgeLogAction = 'CREATE' | 'UPDATE' | 'DELETE'

/** One revision line out of the generated `log.md` (see KnowledgePageService#buildVirtualLog). */
export interface KnowledgeLogEntry {
  /** Bundle-relative path, no leading slash — matches KnowledgeIndexPage#path. */
  path: string
  action: KnowledgeLogAction
  /** ISO date (YYYY-MM-DD) from the entry's day-group heading — coarse, for grouping only. Use
   *  `timestamp` for anything time-relative ("X ago"): a bare date parses as UTC midnight, so
   *  diffing against `day` reads every same-day write as however many hours it's been since
   *  midnight UTC, not since the write actually happened. */
  day: string
  /** ISO instant (the revision's real `createdAt`) — see the note on `day` above. */
  timestamp: string
  sourceRefs?: string[]
}

// "## 2026-07-19" — one heading per LocalDate, in the query's createdAt-desc order (so days already
// come back newest-first; entries within a day inherit that same order from the DB).
const DAY_HEADING_RE = /^##\s+(\d{4}-\d{2}-\d{2})\s*$/

// "* **Create** (2026-07-19T14:23:05.123Z): notes/a.md ← slack://C123/p456, github://org/repo/pull/7"
// — mirrors buildVirtualLog's `"* **" + changeLabel(changeKind) + "** (" + createdAt.toInstant() +
// "): " + path + (refs.isEmpty() ? "" : " ← " + join(", ", refs))`. The label is the revision's real
// changeKind (Create/Update/Delete), title-cased.
const ENTRY_RE = /^\*\s\*\*(\w+)\*\*\s\(([^)]+)\):\s(\S+)(?:\s←\s(.+))?$/

function toAction(label: string): KnowledgeLogAction {
  const upper = label.toUpperCase()
  return upper === 'CREATE' || upper === 'DELETE' ? upper : 'UPDATE'
}

/** Parses the day-grouped revision list out of the generated `log.md` content, newest first
 *  (matches the generator's query order). Entries under an unrecognized/missing day heading are
 *  dropped rather than guessed at. */
export function parseKnowledgeLog(content: string): KnowledgeLogEntry[] {
  const entries: KnowledgeLogEntry[] = []
  let currentDay = ''
  for (const rawLine of content.split('\n')) {
    const line = rawLine.trim()
    const dayMatch = DAY_HEADING_RE.exec(line)
    if (dayMatch) {
      currentDay = dayMatch[1]
      continue
    }
    const entryMatch = ENTRY_RE.exec(line)
    if (!entryMatch || !currentDay) continue
    const [, label, timestamp, path, refs] = entryMatch
    entries.push({
      path,
      action: toAction(label),
      day: currentDay,
      timestamp,
      sourceRefs: refs ? refs.split(', ') : undefined,
    })
  }
  return entries
}
