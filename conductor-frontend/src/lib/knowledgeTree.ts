/** One real page as it appears in the generated `index.md` (see KnowledgePageService#buildVirtualIndex). */
export interface KnowledgeIndexPage {
  /** Bundle-relative, no leading slash — matches the `path` used by getKnowledgePage/routing. */
  path: string
  title: string
  type: string
}

/** A rail section: a directory at some depth ("architecture", then "architecture/frontend", ...)
 *  or the flat "Pages" bucket (id "") for root pages with no directory. `id` is the full directory
 *  path and is stable for persisted collapse state; `label` is what's shown. `pages` are the pages
 *  filed directly in this directory; `children` are its subdirectories. */
export interface KnowledgeTreeSection {
  id: string
  label: string
  pages: KnowledgeIndexPage[]
  children: KnowledgeTreeSection[]
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

/** Drops schema pages (`type: schema`) — plumbing pages that back a domain's filing rules, not
 *  reading content. Filtering here (before grouping) means a section that's schema-only just
 *  disappears rather than showing up with an empty page list. */
export function filterContentPages(pages: KnowledgeIndexPage[]): KnowledgeIndexPage[] {
  return pages.filter((page) => page.type !== 'schema')
}

/** Internal trie node used to build the nested tree before it's flattened into `KnowledgeTreeSection`s. */
interface DirNode {
  id: string
  label: string
  pages: KnowledgeIndexPage[]
  children: Map<string, DirNode>
}

function dirOf(path: string): string {
  const slash = path.lastIndexOf('/')
  return slash < 0 ? '' : path.slice(0, slash)
}

/** Walks/creates every directory node along `dirPath` (e.g. "engineering/architecture" creates/reuses
 *  "engineering" then "engineering/architecture"), returning the deepest node. */
function ensureNode(root: DirNode, dirPath: string): DirNode {
  if (dirPath === '') return root
  let node = root
  let acc = ''
  for (const segment of dirPath.split('/')) {
    acc = acc ? `${acc}/${segment}` : segment
    let child = node.children.get(segment)
    if (!child) {
      child = { id: acc, label: humanizeSegment(segment), pages: [], children: new Map() }
      node.children.set(segment, child)
    }
    node = child
  }
  return node
}

/** Converts a node's children into sorted `KnowledgeTreeSection`s (alphabetical by label). Does not
 *  include the node's own `pages`/flat bucket — callers attach those separately. */
function finalizeChildren(node: DirNode): KnowledgeTreeSection[] {
  return [...node.children.values()]
    .map((child) => ({
      id: child.id,
      label: child.label,
      pages: child.pages,
      children: finalizeChildren(child),
    }))
    .sort((a, b) => a.label.localeCompare(b.label))
}

/** Groups pages into a full nested tree by directory path ("engineering/architecture/foo.md" →
 *  section "engineering" containing child section "architecture"), not just the first segment.
 *  Pages with no "/" (flat, e.g. "_schema.md") land in one root "Pages" section — the tree degrades
 *  to a flat list when every page is flat. Named/directory sections sort alphabetically by label
 *  first (at every depth); the flat "Pages" bucket always sorts last at the root, regardless of
 *  where it first appears in `pages` (the generator emits flat entries before directory entries, so
 *  without this the catch-all would otherwise land first). */
export function groupKnowledgePages(pages: KnowledgeIndexPage[]): KnowledgeTreeSection[] {
  const root: DirNode = { id: '', label: '', pages: [], children: new Map() }
  for (const page of pages) {
    ensureNode(root, dirOf(page.path)).pages.push(page)
  }

  const named = finalizeChildren(root)
  if (root.pages.length === 0) return named
  return [...named, { id: '', label: 'Pages', pages: root.pages, children: [] }]
}

function humanizeSegment(segment: string): string {
  return segment
    .replace(/[-_]/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase())
}
