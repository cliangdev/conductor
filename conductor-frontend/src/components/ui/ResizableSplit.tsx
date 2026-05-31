'use client'

import { useEffect, useRef, useState, ReactNode } from 'react'

interface ResizableSplitProps {
  storageKey: string
  defaultFraction?: number
  min?: number
  left: ReactNode
  right: ReactNode
  rightCollapsed?: boolean
}

export function ResizableSplit({
  storageKey,
  defaultFraction = 0.5,
  min = 240,
  left,
  right,
  rightCollapsed = false,
}: ResizableSplitProps) {
  const containerRef = useRef<HTMLDivElement>(null)

  const [leftPx, setLeftPx] = useState<number | null>(null)

  // Resolve initial width from localStorage fraction or default
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    const stored = localStorage.getItem(storageKey)
    const fraction = stored ? parseFloat(stored) : defaultFraction
    const width = Math.max(min, Math.min(container.clientWidth - min, container.clientWidth * fraction))
    setLeftPx(width)

    // Keep ratio on container resize
    const observer = new ResizeObserver((entries) => {
      const w = entries[0].contentRect.width
      setLeftPx((prev) => {
        if (prev === null) return Math.max(min, Math.min(w - min, w * fraction))
        const f = parseFloat(localStorage.getItem(storageKey) ?? String(defaultFraction))
        return Math.max(min, Math.min(w - min, w * f))
      })
    })
    observer.observe(container)
    return () => observer.disconnect()
  }, [storageKey, defaultFraction, min])

  function startResize(e: React.MouseEvent) {
    e.preventDefault()
    const container = containerRef.current
    if (!container) return

    const startX = e.clientX
    const startWidth = leftPx ?? container.clientWidth * defaultFraction

    document.body.classList.add('select-none')

    function onMouseMove(ev: MouseEvent) {
      const containerWidth = container!.clientWidth
      const next = Math.max(min, Math.min(containerWidth - min, startWidth + ev.clientX - startX))
      setLeftPx(next)
    }

    function onMouseUp(ev: MouseEvent) {
      window.removeEventListener('mousemove', onMouseMove)
      window.removeEventListener('mouseup', onMouseUp)
      document.body.classList.remove('select-none')
      const containerWidth = container!.clientWidth
      const next = Math.max(min, Math.min(containerWidth - min, startWidth + ev.clientX - startX))
      const fraction = next / containerWidth
      localStorage.setItem(storageKey, String(fraction))
    }

    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', onMouseUp)
  }

  if (rightCollapsed) {
    return (
      <div ref={containerRef} className="flex flex-1 overflow-hidden">
        <div className="flex flex-col flex-1 overflow-hidden">{left}</div>
      </div>
    )
  }

  return (
    <div ref={containerRef} className="flex flex-1 overflow-hidden">
      {/* Left pane */}
      <div
        className="flex flex-col overflow-hidden shrink-0"
        style={{ width: leftPx ?? '50%' }}
      >
        {left}
      </div>

      {/* Drag handle */}
      <div
        className="w-1 shrink-0 cursor-col-resize bg-border hover:bg-primary/40 transition-colors"
        onMouseDown={startResize}
      />

      {/* Right pane */}
      <div className="flex flex-col flex-1 overflow-hidden">{right}</div>
    </div>
  )
}
