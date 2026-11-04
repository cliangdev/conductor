'use client'

// The live preview of one slide: the composer's own page in an iframe, scaled down from its real
// 1080-px width, with the optional safe-zone overlay. When the page's measuring script finishes
// (`window.__READY`) its findings (`window.__CHECKS`) are handed to `onChecks`.
//
// The frame is `allow-same-origin` because the bundled fonts load from this origin and a font request
// from an opaque origin would need CORS headers on /slideshow-fonts. The page is built from escaped
// spec text by the composer's builder, never from raw HTML.

import { useEffect, useRef } from 'react'
import { SAFE } from '@cliangdev/creative-render/slideshow/html.js'
import { asChecks, type SlideshowCheck } from './slideText'

/** Frame size in px (real slide pixels) per ratio. */
const FRAME: Record<string, { w: number; h: number }> = { '9x16': { w: 1080, h: 1920 }, '4x5': { w: 1080, h: 1350 } }

interface PreviewWindow {
  __READY?: boolean
  __CHECKS?: unknown
}

const POLL_MS = 100
const GIVE_UP_MS = 20_000

export interface SlidePreviewProps {
  html: string
  ratio: '9x16' | '4x5'
  /** Display width in CSS px; the slide is scaled to it. */
  width?: number
  showSafeZones?: boolean
  /** Called with null while the page loads, then with its checks once it has measured itself. */
  onChecks: (checks: SlideshowCheck[] | null) => void
  title: string
}

export function SlidePreview({ html, ratio, width = 300, showSafeZones, onChecks, title }: SlidePreviewProps) {
  const frame = FRAME[ratio] ?? FRAME['9x16']!
  const scale = width / frame.w
  const timer = useRef<ReturnType<typeof setInterval> | null>(null)
  const onChecksRef = useRef(onChecks)
  useEffect(() => {
    onChecksRef.current = onChecks
  })

  const stop = () => {
    if (timer.current) clearInterval(timer.current)
    timer.current = null
  }
  // A new page: forget the old findings until the new one has measured itself.
  useEffect(() => {
    stop()
    onChecksRef.current(null)
    return stop
  }, [html])

  function handleLoad(e: React.SyntheticEvent<HTMLIFrameElement>) {
    stop()
    const started = Date.now()
    const win = e.currentTarget.contentWindow as (Window & PreviewWindow) | null
    const poll = () => {
      if (win?.__READY) {
        stop()
        onChecksRef.current(asChecks(win.__CHECKS))
      } else if (Date.now() - started > GIVE_UP_MS) {
        stop()
        onChecksRef.current([{ slide: 0, ratio, rule: 'preview', severity: 'warning', message: 'The preview did not finish measuring the slide.' }])
      }
    }
    poll()
    if (timer.current === null && !win?.__READY) timer.current = setInterval(poll, POLL_MS)
  }

  return (
    <div
      className="relative shrink-0 overflow-hidden rounded-md border border-border bg-surface-3"
      style={{ width, height: frame.h * scale }}
      data-testid="slide-preview"
    >
      <iframe
        title={title}
        srcDoc={html}
        sandbox="allow-scripts allow-same-origin"
        onLoad={handleLoad}
        className="absolute left-0 top-0 border-0"
        style={{ width: frame.w, height: frame.h, transform: `scale(${scale})`, transformOrigin: 'top left' }}
      />
      {showSafeZones && ratio === '9x16' && (
        <div className="pointer-events-none absolute inset-0" data-testid="safe-zones" aria-hidden>
          <div className="absolute inset-x-0 top-0 bg-status-failed/15" style={{ height: SAFE.top * scale }} />
          <div className="absolute inset-x-0 bottom-0 bg-status-failed/15" style={{ height: SAFE.bottom * scale }} />
          <div
            className="absolute bg-status-failed/15"
            style={{ right: 0, width: SAFE.right * scale, top: SAFE.top * scale, bottom: SAFE.bottom * scale }}
          />
        </div>
      )}
    </div>
  )
}
