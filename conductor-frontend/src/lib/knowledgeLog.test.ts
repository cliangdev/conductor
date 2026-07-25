import { describe, it, expect } from 'vitest'
import { parseKnowledgeLog } from './knowledgeLog'

// Fixture derived from KnowledgePageService#buildVirtualLog (conductor-backend/src/main/java/com/
// conductor/knowledge/page/KnowledgePageService.java): "# Log\n\n" then one "## <LocalDate>" heading
// per day (createdAt-desc order), each followed by "* **<Label>** (<instant>): <path>[ ← ref1, ref2]"
// lines, one per revision (label is the revision's real changeKind, title-cased; instant is the
// revision's real createdAt), blank line between day groups.
const FIXTURE = `# Log

## 2026-07-19

* **Update** (2026-07-19T22:10:00Z): notes/a.md ← slack://C123/p456, github://org/repo/pull/7
* **Update** (2026-07-19T09:05:00Z): engineering/architecture.md

## 2026-07-18

* **Update** (2026-07-18T23:59:00Z): notes/deleted-page.md
`

describe('parseKnowledgeLog', () => {
  it('parses entries newest-day-first, in the order they appear', () => {
    const entries = parseKnowledgeLog(FIXTURE)

    expect(entries).toEqual([
      {
        path: 'notes/a.md',
        action: 'UPDATE',
        day: '2026-07-19',
        timestamp: '2026-07-19T22:10:00Z',
        sourceRefs: ['slack://C123/p456', 'github://org/repo/pull/7'],
      },
      {
        path: 'engineering/architecture.md',
        action: 'UPDATE',
        day: '2026-07-19',
        timestamp: '2026-07-19T09:05:00Z',
        sourceRefs: undefined,
      },
      {
        path: 'notes/deleted-page.md',
        action: 'UPDATE',
        day: '2026-07-18',
        timestamp: '2026-07-18T23:59:00Z',
        sourceRefs: undefined,
      },
    ])
  })

  it('omits sourceRefs when the entry has none', () => {
    const [entry] = parseKnowledgeLog('## 2026-07-19\n\n* **Update** (2026-07-19T10:00:00Z): notes/a.md\n')
    expect(entry.sourceRefs).toBeUndefined()
  })

  it('splits multiple source refs on ", "', () => {
    const [entry] = parseKnowledgeLog(
      '## 2026-07-19\n\n* **Update** (2026-07-19T10:00:00Z): notes/a.md ← ref-one, ref-two, ref-three\n',
    )
    expect(entry.sourceRefs).toEqual(['ref-one', 'ref-two', 'ref-three'])
  })

  it('recognizes CREATE and DELETE labels if the generator ever emits them', () => {
    const entries = parseKnowledgeLog(
      '## 2026-07-19\n\n* **Create** (2026-07-19T10:00:00Z): notes/new.md\n'
        + '* **Delete** (2026-07-19T10:01:00Z): notes/gone.md\n'
        + '* **Update** (2026-07-19T10:02:00Z): notes/changed.md\n',
    )
    expect(entries.map((e) => e.action)).toEqual(['CREATE', 'DELETE', 'UPDATE'])
  })

  it('captures the parenthesized instant as timestamp', () => {
    const [entry] = parseKnowledgeLog('## 2026-07-19\n\n* **Update** (2026-07-19T10:00:00.123Z): notes/a.md\n')
    expect(entry.timestamp).toBe('2026-07-19T10:00:00.123Z')
  })

  it('returns an empty list for content with no day headings', () => {
    expect(parseKnowledgeLog('# Log\n\nNothing here yet.\n')).toEqual([])
  })

  it('ignores an entry line that appears before any day heading', () => {
    const result = parseKnowledgeLog(
      '* **Update** (2026-07-19T10:00:00Z): orphan.md\n\n## 2026-07-19\n\n* **Update** (2026-07-19T10:00:00Z): notes/a.md\n',
    )
    expect(result).toEqual([
      { path: 'notes/a.md', action: 'UPDATE', day: '2026-07-19', timestamp: '2026-07-19T10:00:00Z', sourceRefs: undefined },
    ])
  })

  it('handles empty content', () => {
    expect(parseKnowledgeLog('')).toEqual([])
  })
})
