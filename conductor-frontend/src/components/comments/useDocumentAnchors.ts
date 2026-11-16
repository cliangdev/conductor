'use client'

import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { buildQuote, findQuote, flattenText, rangeFor } from '@/lib/textQuote'
import { blocksByLine, snapToRendered } from './lineBlocks'
import type { TextQuoteAnchor } from './types'

/** The highlight names painted by `::highlight()` rules in globals.css. */
export const HIGHLIGHT_BASE = 'conductor-comment'
export const HIGHLIGHT_ACTIVE = 'conductor-comment-active'
export const HIGHLIGHT_FLASH = 'conductor-comment-flash'

const FLASH_MS = 1400

/** What the hook needs to know about a thread (a `Comment` or a review draft fits). */
export interface AnchorInput {
  id: string
  /** A quote on the page. */
  anchor?: TextQuoteAnchor | null
  /** An older comment: a source line, with no quote. */
  lineNumber?: number
}

export interface AnchorPlacement {
  /** False when the thread's words (or line) are not in the rendered document. */
  found: boolean
  /** Pixels from the top of the container to the top of the highlight. Meaningless when not found. */
  top: number
}

export interface DocumentAnchorOptions {
  /** The focused thread: its highlight is stronger. */
  activeId: string | null
  /** The markdown source. Its line count bounds legacy line comments, and a change re-finds every quote. */
  content: string
  /** A click landed on this thread's highlight. */
  onClickAnchor?: (id: string) => void
}

export interface DocumentAnchors {
  /** Where each thread that has an anchor or a line ended up. Threads with neither are absent. */
  placements: Record<string, AnchorPlacement>
  /** False in a browser without the CSS Custom Highlight API: nothing is painted, cards are still placed. */
  highlightSupported: boolean
  /** Brings the thread's words into view (if they are not comfortably on screen) and flashes them. */
  scrollToAnchor: (id: string) => void
}

type HighlightRegistry = {
  set(name: string, value: unknown): void
  delete(name: string): void
}

function highlightApi(): { registry: HighlightRegistry; make: (ranges: Range[]) => unknown } | null {
  const css = (globalThis as { CSS?: { highlights?: HighlightRegistry } }).CSS
  const Ctor = (globalThis as { Highlight?: new (...ranges: Range[]) => unknown }).Highlight
  if (!css?.highlights || typeof Ctor !== 'function') return null
  return { registry: css.highlights, make: (ranges) => new Ctor(...ranges) }
}

function pointInRange(range: Range, node: Node, offset: number): boolean {
  try {
    return range.isPointInRange(node, offset)
  } catch {
    return false
  }
}

/** The text position under a viewport point, from whichever caret API the browser has. */
function caretAt(x: number, y: number): { node: Node; offset: number } | null {
  const doc = document as Document & {
    caretPositionFromPoint?: (x: number, y: number) => { offsetNode: Node; offset: number } | null
    caretRangeFromPoint?: (x: number, y: number) => Range | null
  }
  if (typeof doc.caretPositionFromPoint === 'function') {
    const p = doc.caretPositionFromPoint(x, y)
    if (p) return { node: p.offsetNode, offset: p.offset }
  }
  if (typeof doc.caretRangeFromPoint === 'function') {
    const r = doc.caretRangeFromPoint(x, y)
    if (r) return { node: r.startContainer, offset: r.startOffset }
  }
  return null
}

function rectTop(range: Range): number | null {
  if (typeof range.getBoundingClientRect !== 'function') return null
  return range.getBoundingClientRect().top
}

/**
 * The quote for a selection inside `container`: the selected text, a little context either side, and
 * the nearest section. Null when the selection is empty or outside the container.
 */
export function anchorFromRange(container: Element, range: Range): TextQuoteAnchor | null {
  if (range.collapsed || !container.contains(range.commonAncestorContainer)) return null
  const flat = flattenText(container)
  let start = -1
  let end = -1
  for (const fn of flat.nodes) {
    if (!range.intersectsNode(fn.node)) continue
    const lo = fn.node === range.startContainer ? range.startOffset : 0
    const hi = fn.node === range.endContainer ? range.endOffset : fn.chars.length
    for (let i = lo; i < hi; i += 1) {
      const c = fn.chars[i]
      if (c === undefined || c < 0) continue
      if (start < 0 || c < start) start = c
      if (c + 1 > end) end = c + 1
    }
  }
  if (start < 0) return null
  const quote = buildQuote(flat.text, start, end)
  if (!quote.exact) return null
  const startEl = range.startContainer.nodeType === 1 ? (range.startContainer as Element) : range.startContainer.parentElement
  const section = startEl?.closest('[id]')
  const anchor: TextQuoteAnchor = { exact: quote.exact }
  if (quote.prefix) anchor.prefix = quote.prefix
  if (quote.suffix) anchor.suffix = quote.suffix
  if (section && container.contains(section) && section.id) {
    anchor.sectionId = section.id.slice(0, 255)
    if (/^H[1-6]$/.test(section.tagName)) anchor.sectionTitle = (section.textContent ?? '').trim().slice(0, 500)
  }
  return anchor
}

/**
 * Finds each thread's words in the rendered markdown and paints them with the CSS Custom Highlight API,
 * without touching the DOM (so react-markdown's output is left alone). Reports where each highlight
 * sits so the margin rail can line a card up with it, and tells the caller when one is clicked.
 *
 * - A quote comment is found with `findQuote` over the container's flattened text.
 * - An older line comment highlights the block whose `data-line-start..end` covers its line (snapping
 *   to the nearest block for a blank line). A line past the end of the document is not found.
 * - Everything is re-found when the content or the set of threads changes, and when the DOM changes
 *   underneath (a mermaid diagram arriving); only the tops are re-measured on resize and image load.
 * - Without the Highlight API nothing is painted, but placements are still reported.
 */
export function useDocumentAnchors(
  containerRef: React.RefObject<HTMLElement | null>,
  threads: AnchorInput[],
  options: DocumentAnchorOptions
): DocumentAnchors {
  const { activeId, content } = options
  const [placements, setPlacements] = useState<Record<string, AnchorPlacement>>({})
  const highlightSupported = useMemo(() => highlightApi() !== null, [])

  const threadsRef = useRef(threads)
  const activeRef = useRef(activeId)
  const contentRef = useRef(content)
  const onClickRef = useRef(options.onClickAnchor)
  const ranges = useRef(new Map<string, Range>())
  const missing = useRef(new Set<string>())
  const signature = useRef('')
  const flash = useRef<{ id: string; timer: ReturnType<typeof setTimeout> } | null>(null)
  // A layout effect, declared before the one that re-finds, so that one reads this render's values.
  useLayoutEffect(() => {
    threadsRef.current = threads
    activeRef.current = activeId
    contentRef.current = content
    onClickRef.current = options.onClickAnchor
  })

  const paint = useCallback(() => {
    const api = highlightApi()
    if (!api) return
    const base: Range[] = []
    const active: Range[] = []
    const flashed: Range[] = []
    for (const [id, range] of ranges.current) {
      if (id === activeRef.current) active.push(range)
      else base.push(range)
      if (id === flash.current?.id) flashed.push(range)
    }
    const apply = (name: string, list: Range[]) => {
      if (list.length > 0) api.registry.set(name, api.make(list))
      else api.registry.delete(name)
    }
    apply(HIGHLIGHT_BASE, base)
    apply(HIGHLIGHT_ACTIVE, active)
    apply(HIGHLIGHT_FLASH, flashed)
  }, [])

  const measure = useCallback(() => {
    const container = containerRef.current
    if (!container) return
    const origin = container.getBoundingClientRect().top
    const next: Record<string, AnchorPlacement> = {}
    for (const id of missing.current) next[id] = { found: false, top: 0 }
    for (const [id, range] of ranges.current) {
      const top = rectTop(range)
      next[id] = { found: true, top: top === null ? 0 : Math.round(top - origin) }
    }
    const sig = Object.entries(next)
      .map(([id, p]) => `${id}:${p.found ? p.top : 'x'}`)
      .join('|')
    if (sig === signature.current) return
    signature.current = sig
    setPlacements(next)
  }, [containerRef])

  const rebuild = useCallback(() => {
    const container = containerRef.current
    if (!container) return
    const flat = flattenText(container)
    const lineCount = contentRef.current.split('\n').length
    let blocks: Map<number, HTMLElement> | null = null
    const found = new Map<string, Range>()
    const lost = new Set<string>()
    for (const t of threadsRef.current) {
      let range: Range | null = null
      if (t.anchor) {
        const m = findQuote(flat.text, t.anchor)
        if (m) {
          try {
            range = rangeFor(flat, m.start, m.end)
          } catch {
            range = null
          }
        }
      } else if (t.lineNumber && t.lineNumber > 0) {
        blocks = blocks ?? blocksByLine(container)
        if (t.lineNumber <= lineCount && blocks.size > 0) {
          const block = blocks.get(snapToRendered(t.lineNumber, blocks))
          if (block) {
            range = document.createRange()
            range.selectNodeContents(block)
          }
        }
      } else {
        continue // a whole-document comment has nothing to find
      }
      if (range) found.set(t.id, range)
      else lost.add(t.id)
    }
    ranges.current = found
    missing.current = lost
    measure()
    paint()
  }, [containerRef, measure, paint])

  // Re-find when the content or the set of threads changes. A string key stands in for the array,
  // which callers rebuild on every render.
  const key = useMemo(
    () => JSON.stringify(threads.map((t) => [t.id, t.anchor?.exact, t.anchor?.prefix, t.anchor?.suffix, t.lineNumber])),
    [threads]
  )
  useLayoutEffect(() => {
    rebuild()
  }, [rebuild, key, content])

  useEffect(() => {
    paint()
  }, [paint, activeId])

  // Layout and DOM changes underneath the highlights.
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    let frame: number | null = null
    let rebuildNext = false
    const run = () => {
      frame = null
      if (rebuildNext) rebuild()
      else measure()
      rebuildNext = false
    }
    const schedule = (full: boolean) => {
      rebuildNext = rebuildNext || full
      if (frame === null) frame = requestAnimationFrame(run)
    }
    const ro = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(() => schedule(false))
    ro?.observe(container)
    const mo = typeof MutationObserver === 'undefined' ? null : new MutationObserver(() => schedule(true))
    mo?.observe(container, { childList: true, subtree: true, characterData: true })
    const onLoad = () => schedule(false)
    container.addEventListener('load', onLoad, true)
    return () => {
      if (frame !== null) cancelAnimationFrame(frame)
      ro?.disconnect()
      mo?.disconnect()
      container.removeEventListener('load', onLoad, true)
    }
  }, [containerRef, measure, rebuild])

  // Click on a highlight.
  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    function onClick(e: MouseEvent) {
      const target = e.target as HTMLElement | null
      if (target?.closest('a,button,input,[role="checkbox"]')) return
      if (window.getSelection?.()?.isCollapsed === false) return
      const caret = caretAt(e.clientX, e.clientY)
      if (!caret) return
      let hit: { id: string; size: number } | null = null
      for (const [id, range] of ranges.current) {
        if (!pointInRange(range, caret.node, caret.offset)) continue
        const size = range.toString().length
        // Overlapping highlights: the smallest one is the one most precisely under the pointer.
        if (!hit || size < hit.size) hit = { id, size }
      }
      if (hit) onClickRef.current?.(hit.id)
    }
    container.addEventListener('click', onClick)
    return () => container.removeEventListener('click', onClick)
  }, [containerRef])

  useEffect(
    () => () => {
      if (flash.current) clearTimeout(flash.current.timer)
      const api = highlightApi()
      api?.registry.delete(HIGHLIGHT_BASE)
      api?.registry.delete(HIGHLIGHT_ACTIVE)
      api?.registry.delete(HIGHLIGHT_FLASH)
    },
    []
  )

  const scrollToAnchor = useCallback(
    (id: string) => {
      const range = ranges.current.get(id)
      if (!range) return
      const rect = typeof range.getBoundingClientRect === 'function' ? range.getBoundingClientRect() : null
      const comfortable = rect && rect.top >= 96 && rect.bottom <= window.innerHeight - 96
      if (!comfortable) {
        const el =
          range.startContainer.nodeType === 1 ? (range.startContainer as Element) : range.startContainer.parentElement
        el?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
      }
      if (flash.current) clearTimeout(flash.current.timer)
      const timer = setTimeout(() => {
        flash.current = null
        paint()
      }, FLASH_MS)
      flash.current = { id, timer }
      paint()
    },
    [paint]
  )

  return { placements, highlightSupported, scrollToAnchor }
}
