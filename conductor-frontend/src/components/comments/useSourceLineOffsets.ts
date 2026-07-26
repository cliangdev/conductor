'use client'

import { useCallback, useEffect, useRef, useState } from 'react'

export interface LineBox {
  /** Pixels from the top of the measured container. */
  top: number
  height: number
}

/** What a source line falls back to when nothing has been measured yet. */
export const FALLBACK_LINE_HEIGHT_PX = 1.625 * 16 // 26px

export type LineOffsets = Map<number, LineBox>

export function fallbackBox(lineNumber: number): LineBox {
  return { top: (lineNumber - 1) * FALLBACK_LINE_HEIGHT_PX, height: FALLBACK_LINE_HEIGHT_PX }
}

/**
 * Nearest line that actually rendered something.
 *
 * A comment can be anchored to a line that renders nothing of its own — a blank separator, or a line
 * swallowed by a block whose narrowest anchor starts earlier. Snapping keeps it reachable rather than
 * stranding its marker at a position no content occupies. Ties prefer the *preceding* block, since a
 * comment written against a blank line almost always refers to what came before it.
 */
export function snapToRendered(lineNumber: number, measured: LineOffsets): number {
  if (measured.has(lineNumber)) return lineNumber

  let best = lineNumber
  let bestDistance = Number.POSITIVE_INFINITY
  for (const candidate of measured.keys()) {
    // The +0.5 on the "after" side breaks ties toward the preceding block in one comparison.
    const distance =
      candidate <= lineNumber ? lineNumber - candidate : candidate - lineNumber + 0.5
    if (distance < bestDistance) {
      bestDistance = distance
      best = candidate
    }
  }
  return best
}

/**
 * Where each source line actually sits in the rendered markdown.
 *
 * The gutter used to assume every source line was exactly 26px tall, which holds only for a document
 * of uniform single-line paragraphs. A heading, a code block, an image, or any line long enough to
 * wrap breaks the assumption, and because the error accumulates, markers drift further from their
 * line the further down the document you read. This measures the real thing instead, from the
 * `data-line-start` / `data-line-end` attributes that `rehypeSourceLines` stamps on each block.
 *
 * Returns null until a measurement succeeds — including in jsdom, where every element reports zero
 * height — so callers can fall back to the old arithmetic rather than stacking everything at y=0.
 */
export function useSourceLineOffsets(
  containerRef: React.RefObject<HTMLElement | null>,
  /** Re-measure when the rendered content changes, not just when the box resizes. */
  content: string
): LineOffsets | null {
  const [offsets, setOffsets] = useState<LineOffsets | null>(null)
  // Compared before setState so a ResizeObserver firing on an unchanged layout doesn't re-render.
  const signatureRef = useRef<string>('')

  const measure = useCallback(() => {
    const container = containerRef.current
    if (!container) return

    const blocks = container.querySelectorAll<HTMLElement>('[data-line-start]')
    if (blocks.length === 0) {
      signatureRef.current = ''
      setOffsets(null)
      return
    }

    const containerTop = container.getBoundingClientRect().top
    const next: LineOffsets = new Map()
    // Narrowest range wins, so a nested list item beats the item that contains it and a paragraph
    // inside a loose list item beats the item itself.
    const spanForLine = new Map<number, number>()
    let sawRealHeight = false

    blocks.forEach((block) => {
      const start = Number(block.dataset.lineStart)
      const end = Number(block.dataset.lineEnd)
      if (!Number.isInteger(start) || !Number.isInteger(end) || end < start) return

      const rect = block.getBoundingClientRect()
      if (rect.height > 0) sawRealHeight = true

      const lineCount = end - start + 1
      const span = lineCount
      const sliceHeight = rect.height / lineCount
      const blockTop = rect.top - containerTop

      for (let line = start; line <= end; line += 1) {
        const existing = spanForLine.get(line)
        if (existing !== undefined && existing <= span) continue
        spanForLine.set(line, span)
        next.set(line, {
          top: blockTop + (line - start) * sliceHeight,
          height: sliceHeight,
        })
      }
    })

    // jsdom (and a container that hasn't been laid out yet) reports every rect as zero — treat that
    // as "not measurable" so the caller keeps its arithmetic fallback.
    if (!sawRealHeight) {
      signatureRef.current = ''
      setOffsets(null)
      return
    }

    const signature = `${next.size}:${[...next.entries()]
      .map(([line, box]) => `${line},${Math.round(box.top)},${Math.round(box.height)}`)
      .join('|')}`
    if (signature === signatureRef.current) return
    signatureRef.current = signature
    setOffsets(next)
  }, [containerRef])

  useEffect(() => {
    measure()

    const container = containerRef.current
    if (!container || typeof ResizeObserver === 'undefined') return

    // Catches the reflows a one-shot measurement would miss: window resize, a font swap, an image or
    // mermaid diagram finishing its load, the properties panel opening.
    const observer = new ResizeObserver(() => measure())
    observer.observe(container)
    container.querySelectorAll('img').forEach((img) => observer.observe(img))

    return () => observer.disconnect()
  }, [measure, containerRef, content])

  return offsets
}
