export type KnowledgeLogAction = 'CREATE' | 'UPDATE' | 'DELETE'

/** One revision line out of the generated `log.md` (see KnowledgePageService#buildVirtualLog). */
export interface KnowledgeLogEntry {
  /** Bundle-relative path, no leading slash — matches KnowledgeIndexPage#path. */
  path: string
  action: KnowledgeLogAction
  /** ISO date (YYYY-MM-DD) from the entry's day-group heading. buildVirtualLog only groups by
   *  `LocalDate`, not a per-revision timestamp, so this is the finest granularity available. */
  day: string
  sourceRefs?: string[]
}

// "## 2026-07-19" — one heading per LocalDate, in the query's createdAt-desc order (so days already
// come back newest-first; entries within a day inherit that same order from the DB).
const DAY_HEADING_RE = /^##\s+(\d{4}-\d{2}-\d{2})\s*$/

// "* **Update**: notes/a.md ← slack://C123/p456, github://org/repo/pull/7" — mirrors
// buildVirtualLog's `"* **Update**: " + path + (refs.isEmpty() ? "" : " ← " + join(", ", refs))`.
// NOTE: as of this writing buildVirtualLog hardcodes the literal label "Update" for every revision
// regardless of its actual changeKind (CREATE/UPDATE/DELETE) — see
// KnowledgePageServiceIntegrationTest#indexAndLogVirtualPagesAreGenerated, which asserts
// `"**Update**: notes/a.md"` even for a page's first (CREATE) revision. The regex still captures
// whatever label is present so this parser keeps working unchanged if that's ever fixed to emit
// the real changeKind.
const ENTRY_RE = /^\*\s\*\*(\w+)\*\*:\s(\S+)(?:\s←\s(.+))?$/

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
    const [, label, path, refs] = entryMatch
    entries.push({
      path,
      action: toAction(label),
      day: currentDay,
      sourceRefs: refs ? refs.split(', ') : undefined,
    })
  }
  return entries
}
