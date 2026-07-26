import { visit } from 'unist-util-visit'
import type { Element, Root } from 'hast'

/**
 * Stamps `data-line` (the 1-based source line of the task list item) onto every GFM checkbox so the
 * renderer can map a click back to the markdown line it came from.
 *
 * This is needed because `mdast-util-to-hast` *synthesizes* the checkbox `<input>` — it builds a bare
 * element literal with no `position`, so the input alone can't be traced to source. Its parent `<li>`
 * does carry `position`, so we copy the line down. Doing it here rather than in an `li` component
 * override keeps the renderer to a single `input` override; an `li` override would have to re-forward
 * `className` by hand and risk breaking list styling on every surface that renders markdown.
 *
 * `rehype-highlight` and `rehype-slug` don't touch `position`, so plugin order relative to them
 * doesn't matter.
 */
export function rehypeTaskListLines() {
  return (tree: Root) => {
    visit(tree, 'element', (node: Element) => {
      if (node.tagName !== 'li') return

      const className = node.properties?.className
      if (!Array.isArray(className) || !className.includes('task-list-item')) return

      const line = node.position?.start?.line
      if (typeof line !== 'number') return

      node.properties['data-line'] = String(line)

      const checkbox = findOwnCheckbox(node)
      if (checkbox) checkbox.properties['data-line'] = String(line)
    })
  }
}

/**
 * The item's own checkbox: a direct child in a tight list, or nested inside the first `<p>` in a loose
 * one. Nested `ul`/`ol` are skipped so a parent item can't claim a child item's checkbox — without
 * that, an unchecked parent containing a checked child would stamp the wrong line on the child's box.
 */
function findOwnCheckbox(node: Element): Element | null {
  for (const child of node.children) {
    if (child.type !== 'element') continue
    if (child.tagName === 'ul' || child.tagName === 'ol') continue

    if (child.tagName === 'input' && child.properties?.type === 'checkbox') return child

    const nested = findOwnCheckbox(child)
    if (nested) return nested
  }
  return null
}
