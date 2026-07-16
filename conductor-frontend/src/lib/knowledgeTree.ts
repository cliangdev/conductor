/** One real page as it appears in the generated `index.md` (see KnowledgePageService#buildVirtualIndex). */
export interface KnowledgeIndexPage {
  /** Bundle-relative, no leading slash — matches the `path` used by getKnowledgePage/routing. */
  path: string
  title: string
  type: string
}

/** A rail section: either a top-level path segment ("architecture") or the flat "Pages" bucket for
 *  pages with no directory. `id` is stable for persisted collapse state; `label` is what's shown. */
export interface KnowledgeTreeSection {
  id: string
  label: string
  pages: KnowledgeIndexPage[]
}

// Title group is greedy (`.+`) rather than `[^\]]*` so that a title containing an unescaped `]`
// (the backend inserts titles verbatim, unescaped) doesn't truncate the match early — the regex
// engine backtracks to the *last* `](/` in the line, which is the real title/path delimiter since
// only one link appears per bullet. The `(type: …)` suffix is optional so a page without a type
// still parses instead of silently vanishing.
const BULLET_RE = /^\*\s\[(.+)\]\(\/([^)]*)\)(?:\s—\s.*?)?(?:\s\(type:\s*([^)]+)\))?\s*$/

/** Parses the bullet lines out of the generated `index.md` content — one entry per live page,
 *  in path order. Ignores the "## /dir" section headings (redundant with each page's own `path`). */
export function parseKnowledgeIndexPages(content: string): KnowledgeIndexPage[] {
  const pages: KnowledgeIndexPage[] = []
  let skipped = 0
  for (const rawLine of content.split('\n')) {
    const line = rawLine.trim()
    const match = BULLET_RE.exec(line)
    if (!match) {
      // Only count lines that look like a bullet attempt — headings/blank lines are expected misses.
      if (line.startsWith('* [')) skipped++
      continue
    }
    const [, title, path, type] = match
    pages.push({ path, title: title || path, type: (type ?? '').trim() })
  }
  if (skipped > 0 && process.env.NODE_ENV === 'development') {
    // Dev-only visibility for otherwise-silent parse drops (e.g. a malformed bullet line).
    console.warn(`[knowledgeTree] skipped ${skipped} unparsable index.md bullet line(s)`)
  }
  return pages
}

/** Groups pages by their top-level path segment ("architecture/foo.md" → section "architecture").
 *  Pages with no "/" (flat, e.g. "_schema.md") land in one "Pages" section — the tree degrades to a
 *  flat list when every page is flat. Named/directory sections sort alphabetically by label first;
 *  the flat "Pages" bucket always sorts last, regardless of where it first appears in `pages`
 *  (the generator emits flat entries before directory entries, so without this the catch-all would
 *  otherwise land first). */
export function groupKnowledgePages(pages: KnowledgeIndexPage[]): KnowledgeTreeSection[] {
  const sections: KnowledgeTreeSection[] = []
  const byId = new Map<string, KnowledgeTreeSection>()

  for (const page of pages) {
    const slash = page.path.indexOf('/')
    const id = slash < 0 ? '' : page.path.slice(0, slash)
    let section = byId.get(id)
    if (!section) {
      section = { id, label: id === '' ? 'Pages' : humanizeSegment(id), pages: [] }
      byId.set(id, section)
      sections.push(section)
    }
    section.pages.push(page)
  }

  const named = sections.filter((s) => s.id !== '').sort((a, b) => a.label.localeCompare(b.label))
  const flat = sections.find((s) => s.id === '')
  return flat ? [...named, flat] : named
}

function humanizeSegment(segment: string): string {
  return segment
    .replace(/[-_]/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase())
}
