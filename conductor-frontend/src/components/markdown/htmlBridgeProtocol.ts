import type { TextQuoteAnchor } from '@/components/comments/types'

/**
 * Messages the bridge script posts from the sandboxed frame to the app. The page's own scripts can
 * post to the app too, so every message is validated here and only ever drives UI state — a comment
 * body is typed in the app, never taken from the frame.
 */

export interface SelectionRect {
  top: number
  left: number
  right: number
  bottom: number
  width: number
  height: number
}

export interface AnchorResult {
  id: string
  found: boolean
  top: number
}

export type BridgeMessage =
  | { type: 'ready' }
  | { type: 'selection'; anchor: TextQuoteAnchor; rect: SelectionRect }
  | { type: 'selection-cleared' }
  | { type: 'anchored'; results: AnchorResult[] }
  | { type: 'open'; id: string }

/** An anchor sent to the frame to find and highlight. */
export interface FrameAnchor {
  id: string
  exact: string
  prefix?: string
  suffix?: string
}

const MAX_EXACT = 2000
const MAX_CONTEXT = 64
const MAX_SECTION_ID = 255
const MAX_SECTION_TITLE = 500

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null
}

function isNum(v: unknown): v is number {
  return typeof v === 'number' && Number.isFinite(v)
}

function optionalString(v: unknown, max: number): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v.slice(0, max) : undefined
}

function parseRect(v: unknown): SelectionRect | null {
  if (!isRecord(v)) return null
  const { top, left, right, bottom, width, height } = v
  if (![top, left, right, bottom, width, height].every(isNum)) return null
  return { top, left, right, bottom, width, height } as SelectionRect
}

/** Returns the message when `data` is a well-formed bridge message, otherwise null. */
export function parseBridgeMessage(data: unknown): BridgeMessage | null {
  if (!isRecord(data) || data.source !== 'conductor-bridge') return null
  switch (data.type) {
    case 'ready':
      return { type: 'ready' }
    case 'selection-cleared':
      return { type: 'selection-cleared' }
    case 'open':
      return typeof data.id === 'string' && data.id ? { type: 'open', id: data.id } : null
    case 'anchored': {
      if (!Array.isArray(data.results)) return null
      const results: AnchorResult[] = []
      for (const r of data.results) {
        if (!isRecord(r) || typeof r.id !== 'string' || typeof r.found !== 'boolean') return null
        results.push({ id: r.id, found: r.found, top: isNum(r.top) ? r.top : 0 })
      }
      return { type: 'anchored', results }
    }
    case 'selection': {
      if (typeof data.exact !== 'string') return null
      const exact = data.exact.trim()
      if (exact.length < 1 || exact.length > MAX_EXACT) return null
      const rect = parseRect(data.rect)
      if (!rect) return null
      const anchor: TextQuoteAnchor = { exact }
      const prefix = optionalString(data.prefix, MAX_CONTEXT)
      const suffix = optionalString(data.suffix, MAX_CONTEXT)
      const sectionId = optionalString(data.sectionId, MAX_SECTION_ID)
      const sectionTitle = optionalString(data.sectionTitle, MAX_SECTION_TITLE)
      if (prefix) anchor.prefix = prefix
      if (suffix) anchor.suffix = suffix
      if (sectionId) anchor.sectionId = sectionId
      if (sectionTitle) anchor.sectionTitle = sectionTitle
      return { type: 'selection', anchor, rect }
    }
    default:
      return null
  }
}
