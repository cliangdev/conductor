'use client'

import { useEffect, useState } from 'react'

/** Below this width a docked rail would squeeze the text (the page viewers measure their own width). */
export const DOCK_MIN_WIDTH = 1024
export const PHONE_QUERY = '(max-width: 767px)'
export const TABLET_QUERY = '(min-width: 768px) and (max-width: 1023px)'
export const COARSE_QUERY = '(pointer: coarse)'

/**
 * wide: the aligned rail is docked beside the document (>= 1024px).
 * tablet: only count bubbles in a narrow margin; a tap opens a side sheet (768-1023px).
 * phone: highlights only; a tap opens a bottom sheet (< 768px).
 */
export type CommentLayoutMode = 'wide' | 'tablet' | 'phone'

function matches(query: string): boolean {
  return typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(query).matches
}

/** Whether the primary pointer is a finger: the native selection menu then sits above the selection. */
export function isCoarsePointer(): boolean {
  return matches(COARSE_QUERY)
}

/**
 * The layout the comments use. The viewport decides phone and tablet; a wrapper narrower than a docked
 * rail allows (a sidebar eating the width of a laptop window) counts as tablet. `wrapperWidth` is 0
 * until measured, and then treated as wide enough.
 */
export function useCommentLayoutMode(wrapperWidth: number): CommentLayoutMode {
  const [phone, setPhone] = useState(false)
  const [tablet, setTablet] = useState(false)

  useEffect(() => {
    if (typeof window.matchMedia !== 'function') return
    const p = window.matchMedia(PHONE_QUERY)
    const t = window.matchMedia(TABLET_QUERY)
    const sync = () => {
      setPhone(p.matches)
      setTablet(t.matches)
    }
    sync()
    p.addEventListener?.('change', sync)
    t.addEventListener?.('change', sync)
    return () => {
      p.removeEventListener?.('change', sync)
      t.removeEventListener?.('change', sync)
    }
  }, [])

  if (phone) return 'phone'
  if (tablet || (wrapperWidth > 0 && wrapperWidth < DOCK_MIN_WIDTH)) return 'tablet'
  return 'wide'
}
