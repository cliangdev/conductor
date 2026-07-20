import { describe, it, expect } from 'vitest'
import { parseKnowledgeLog } from './knowledgeLog'

// Fixture derived from KnowledgePageService#buildVirtualLog (conductor-backend/src/main/java/com/
// conductor/knowledge/page/KnowledgePageService.java): "# Log\n\n" then one "## <LocalDate>" heading
// per day (createdAt-desc order), each followed by "* **Update**: <path>[ ← ref1, ref2]" lines, one
// per revision, blank line between day groups. The generator currently hardcodes the literal label
// "Update" for every revision regardless of its real changeKind — see
// KnowledgePageServiceIntegrationTest#indexAndLogVirtualPagesAreGenerated.
const FIXTURE = `# Log

## 2026-07-19

* **Update**: notes/a.md ← slack://C123/p456, github://org/repo/pull/7
* **Update**: engineering/architecture.md

## 2026-07-18

* **Update**: notes/deleted-page.md
`

describe('parseKnowledgeLog', () => {
  it('parses entries newest-day-first, in the order they appear', () => {
    const entries = parseKnowledgeLog(FIXTURE)

    expect(entries).toEqual([
      { path: 'notes/a.md', action: 'UPDATE', day: '2026-07-19', sourceRefs: ['slack://C123/p456', 'github://org/repo/pull/7'] },
      { path: 'engineering/architecture.md', action: 'UPDATE', day: '2026-07-19', sourceRefs: undefined },
      { path: 'notes/deleted-page.md', action: 'UPDATE', day: '2026-07-18', sourceRefs: undefined },
    ])
  })

  it('omits sourceRefs when the entry has none', () => {
    const [entry] = parseKnowledgeLog('## 2026-07-19\n\n* **Update**: notes/a.md\n')
    expect(entry.sourceRefs).toBeUndefined()
  })

  it('splits multiple source refs on ", "', () => {
    const [entry] = parseKnowledgeLog('## 2026-07-19\n\n* **Update**: notes/a.md ← ref-one, ref-two, ref-three\n')
    expect(entry.sourceRefs).toEqual(['ref-one', 'ref-two', 'ref-three'])
  })

  it('recognizes CREATE and DELETE labels if the generator ever emits them', () => {
    const entries = parseKnowledgeLog(
      '## 2026-07-19\n\n* **Create**: notes/new.md\n* **Delete**: notes/gone.md\n* **Update**: notes/changed.md\n',
    )
    expect(entries.map((e) => e.action)).toEqual(['CREATE', 'DELETE', 'UPDATE'])
  })

  it('returns an empty list for content with no day headings', () => {
    expect(parseKnowledgeLog('# Log\n\nNothing here yet.\n')).toEqual([])
  })

  it('ignores an entry line that appears before any day heading', () => {
    expect(parseKnowledgeLog('* **Update**: orphan.md\n\n## 2026-07-19\n\n* **Update**: notes/a.md\n')).toEqual([
      { path: 'notes/a.md', action: 'UPDATE', day: '2026-07-19', sourceRefs: undefined },
    ])
  })

  it('handles empty content', () => {
    expect(parseKnowledgeLog('')).toEqual([])
  })
})
