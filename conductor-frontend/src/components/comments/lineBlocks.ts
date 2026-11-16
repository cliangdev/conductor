/**
 * Source-line lookups over rendered markdown. `rehypeSourceLines` stamps `data-line-start` /
 * `data-line-end` (1-based, inclusive, counted against the raw source — frontmatter included) on each
 * block, so an older comment that only knows a line number can be pointed at the block showing it.
 */

/** Which rendered block shows each source line; the narrowest block wins (a list item beats its list). */
export function blocksByLine(container: Element): Map<number, HTMLElement> {
  const spans = new Map<number, number>()
  const out = new Map<number, HTMLElement>()
  container.querySelectorAll<HTMLElement>('[data-line-start]').forEach((block) => {
    const start = Number(block.dataset.lineStart)
    const end = Number(block.dataset.lineEnd)
    if (!Number.isInteger(start) || !Number.isInteger(end) || end < start) return
    const span = end - start + 1
    for (let line = start; line <= end; line += 1) {
      const existing = spans.get(line)
      if (existing !== undefined && existing <= span) continue
      spans.set(line, span)
      out.set(line, block)
    }
  })
  return out
}

/**
 * Nearest line that actually rendered something.
 *
 * A comment can be on a line that renders nothing of its own — a blank separator, or frontmatter.
 * Snapping keeps it reachable. Ties prefer the *preceding* block, since a comment written against a
 * blank line almost always refers to what came before it.
 */
export function snapToRendered(
  lineNumber: number,
  rendered: { has(line: number): boolean; keys(): Iterable<number> }
): number {
  if (rendered.has(lineNumber)) return lineNumber
  let best = lineNumber
  let bestDistance = Number.POSITIVE_INFINITY
  for (const candidate of rendered.keys()) {
    // The +0.5 on the "after" side breaks ties toward the preceding block in one comparison.
    const distance = candidate <= lineNumber ? lineNumber - candidate : candidate - lineNumber + 0.5
    if (distance < bestDistance) {
      bestDistance = distance
      best = candidate
    }
  }
  return best
}
