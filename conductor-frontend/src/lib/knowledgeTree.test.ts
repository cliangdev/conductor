import { describe, it, expect, vi, afterEach } from 'vitest'
import { filterContentPages, groupKnowledgePages, parseKnowledgeIndexPages } from '@/lib/knowledgeTree'

const INDEX_CONTENT = `# Index

## /architecture

* [Workflow Engine](/architecture/workflow-engine.md) — Design of the workflow execution engine (type: architecture)
* [Data Model](/architecture/data-model.md) (type: architecture)

## /

* [Schema Guide](/_schema.md) (type: project)
`

describe('parseKnowledgeIndexPages', () => {
  it('extracts path, title, and type from each bullet, ignoring the "## /dir" headings', () => {
    const pages = parseKnowledgeIndexPages(INDEX_CONTENT)
    expect(pages).toEqual([
      { path: 'architecture/workflow-engine.md', title: 'Workflow Engine', type: 'architecture' },
      { path: 'architecture/data-model.md', title: 'Data Model', type: 'architecture' },
      { path: '_schema.md', title: 'Schema Guide', type: 'project' },
    ])
  })

  it('returns an empty list for an empty bundle', () => {
    expect(parseKnowledgeIndexPages('# Index\n')).toEqual([])
  })

  it('parses a title containing an unescaped "]" instead of truncating at it', () => {
    // The backend inserts titles verbatim/unescaped into index.md — a title like "Design [v2]"
    // used to break the old `[^\]]*` regex and silently drop the page from the rail.
    const content = '* [Design [v2]](/architecture/design.md) (type: architecture)\n'
    expect(parseKnowledgeIndexPages(content)).toEqual([
      { path: 'architecture/design.md', title: 'Design [v2]', type: 'architecture' },
    ])
  })

  it('tolerates a missing "(type: …)" suffix', () => {
    const content = '* [No Type Page](/misc/no-type.md)\n'
    expect(parseKnowledgeIndexPages(content)).toEqual([
      { path: 'misc/no-type.md', title: 'No Type Page', type: '' },
    ])
  })

  describe('dev-only skip warning', () => {
    afterEach(() => {
      vi.unstubAllEnvs()
      vi.restoreAllMocks()
    })

    it('warns in development when a bullet-shaped line fails to parse', () => {
      vi.stubEnv('NODE_ENV', 'development')
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      parseKnowledgeIndexPages('* [broken bullet with no closing paren(/foo.md\n')
      expect(warn).toHaveBeenCalledTimes(1)
      expect(warn.mock.calls[0][0]).toContain('skipped 1')
    })

    it('does not warn outside development (e.g. test/production)', () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      parseKnowledgeIndexPages('* [broken bullet with no closing paren(/foo.md\n')
      expect(warn).not.toHaveBeenCalled()
    })
  })
})

describe('filterContentPages', () => {
  it('drops pages whose type is schema', () => {
    const pages = [
      { path: '_schema.md', title: 'Schema Guide', type: 'schema' },
      { path: 'engineering/_schema.md', title: 'Engineering Schema', type: 'schema' },
      { path: 'architecture/design.md', title: 'Design', type: 'architecture' },
    ]
    expect(filterContentPages(pages)).toEqual([{ path: 'architecture/design.md', title: 'Design', type: 'architecture' }])
  })

  it('leaves non-schema pages untouched, including ones with no type', () => {
    const pages = [
      { path: 'readme.md', title: 'Readme', type: 'project' },
      { path: 'misc/no-type.md', title: 'No Type Page', type: '' },
    ]
    expect(filterContentPages(pages)).toEqual(pages)
  })

  it('returns an empty list unchanged', () => {
    expect(filterContentPages([])).toEqual([])
  })
})

describe('groupKnowledgePages', () => {
  it('groups nested pages by their top-level path segment', () => {
    const sections = groupKnowledgePages(parseKnowledgeIndexPages(INDEX_CONTENT))
    expect(sections).toHaveLength(2)
    expect(sections[0]).toMatchObject({ id: 'architecture', label: 'Architecture' })
    expect(sections[0].pages.map((p) => p.path)).toEqual([
      'architecture/workflow-engine.md',
      'architecture/data-model.md',
    ])
    expect(sections[1]).toMatchObject({ id: '', label: 'Pages' })
    expect(sections[1].pages.map((p) => p.path)).toEqual(['_schema.md'])
  })

  it('degrades to a single flat "Pages" section when no page has a directory', () => {
    const flat = parseKnowledgeIndexPages(`# Index\n\n## /\n\n* [Readme](/readme.md) (type: project)\n`)
    const sections = groupKnowledgePages(flat)
    expect(sections).toEqual([{ id: '', label: 'Pages', pages: flat }])
  })

  it(
    'puts named sections first (alphabetical) and the flat "Pages" bucket last, even though ' +
      'the real generator emits flat/no-directory entries before directory entries',
    () => {
      // Mirrors buildVirtualIndex's actual output order: pages are ORDER BY path, and a leading "_"
      // (ASCII 0x5F) sorts before any lowercase directory name, so flat entries land first in the
      // array. Directory names here are deliberately out of alphabetical order in the input too
      // ("Zebra" before "apple" — ASCII uppercase sorts before lowercase) to prove the function
      // re-sorts rather than merely preserving arrival order.
      const pages = [
        { path: '_schema.md', title: 'Schema Guide', type: 'project' },
        { path: 'Zebra/last.md', title: 'Zebra Last', type: 'component' },
        { path: 'apple/first.md', title: 'Apple First', type: 'component' },
      ]

      const sections = groupKnowledgePages(pages)

      expect(sections.map((s) => s.id)).toEqual(['apple', 'Zebra', ''])
      expect(sections.map((s) => s.label)).toEqual(['Apple', 'Zebra', 'Pages'])
    }
  )
})
