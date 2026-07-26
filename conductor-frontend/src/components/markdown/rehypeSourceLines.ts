import { visit } from 'unist-util-visit'
import type { Element, Root } from 'hast'

/**
 * Block-level tags worth anchoring to. Deliberately the *leaf-ish* blocks — `li` rather than the `ul`
 * that wraps it, so a 40-item list yields 40 anchors instead of one. Containers (`ul`, `ol`,
 * `blockquote`, `table`) are left unstamped; their children carry the anchors.
 */
const ANCHORED_TAGS = new Set([
  'p',
  'li',
  'h1',
  'h2',
  'h3',
  'h4',
  'h5',
  'h6',
  'pre',
  'tr',
  'hr',
  'dt',
  'dd',
])

/**
 * Stamps `data-line-start` / `data-line-end` (1-based, inclusive) on rendered blocks so the comment
 * gutter can line its markers up with what's actually on screen.
 *
 * Without this the gutter can only assume every source line renders the same height, which is false
 * the moment a document contains a heading, a code block, or a line long enough to wrap — the error
 * accumulates and markers drift further from their line the further down you read.
 */
export function rehypeSourceLines() {
  return (tree: Root) => {
    visit(tree, 'element', (node: Element) => {
      if (!ANCHORED_TAGS.has(node.tagName)) return

      const start = node.position?.start?.line
      const end = node.position?.end?.line
      if (typeof start !== 'number' || typeof end !== 'number') return

      node.properties = node.properties ?? {}
      node.properties['data-line-start'] = String(start)
      node.properties['data-line-end'] = String(Math.max(start, end))
    })
  }
}
