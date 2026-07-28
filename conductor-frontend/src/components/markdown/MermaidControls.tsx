'use client'
import type { RefObject } from 'react'
import { ChevronUp, ChevronDown, ChevronLeft, ChevronRight, RotateCcw, ZoomIn, ZoomOut } from 'lucide-react'
import type { ReactZoomPanPinchRef } from 'react-zoom-pan-pinch'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

interface Props {
  transformRef: RefObject<ReactZoomPanPinchRef | null>
  size?: 'sm' | 'default'
  panStep?: number
  className?: string
  ariaLabel?: string
}

export function MermaidControls({
  transformRef,
  size = 'default',
  panStep = 80,
  className,
  ariaLabel = 'Diagram controls',
}: Props) {
  const compact = size === 'sm'
  const buttonClass = compact ? 'h-6 w-6' : undefined
  const iconClass = compact ? 'h-3.5 w-3.5' : 'h-4 w-4'

  const pan = (dx: number, dy: number) => {
    const ctx = transformRef.current
    if (!ctx) return
    const { positionX, positionY, scale } = ctx.state
    ctx.setTransform(positionX + dx * scale, positionY + dy * scale, scale, 200, 'easeOut')
  }

  return (
    <div
      role="toolbar"
      aria-label={ariaLabel}
      className={cn(
        'flex items-center gap-1 rounded-lg border border-border bg-popover/90 backdrop-blur px-1.5 py-1 shadow-lg',
        className
      )}
    >
      <div className="flex flex-col gap-0.5">
        <Button
          variant="ghost"
          size="icon"
          aria-label="Zoom in"
          className={buttonClass}
          onClick={() => transformRef.current?.zoomIn()}
        >
          <ZoomIn className={iconClass} />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Zoom out"
          className={buttonClass}
          onClick={() => transformRef.current?.zoomOut()}
        >
          <ZoomOut className={iconClass} />
        </Button>
      </div>
      <div className="mx-0.5 h-8 w-px bg-border" aria-hidden="true" />
      <div className="grid grid-cols-3 grid-rows-3 gap-0.5">
        <div />
        <Button
          variant="ghost"
          size="icon"
          aria-label="Pan up"
          className={buttonClass}
          onClick={() => pan(0, panStep)}
        >
          <ChevronUp className={iconClass} />
        </Button>
        <div />
        <Button
          variant="ghost"
          size="icon"
          aria-label="Pan left"
          className={buttonClass}
          onClick={() => pan(panStep, 0)}
        >
          <ChevronLeft className={iconClass} />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Reset view"
          className={buttonClass}
          onClick={() => transformRef.current?.resetTransform()}
        >
          <RotateCcw className={iconClass} />
        </Button>
        <Button
          variant="ghost"
          size="icon"
          aria-label="Pan right"
          className={buttonClass}
          onClick={() => pan(-panStep, 0)}
        >
          <ChevronRight className={iconClass} />
        </Button>
        <div />
        <Button
          variant="ghost"
          size="icon"
          aria-label="Pan down"
          className={buttonClass}
          onClick={() => pan(0, -panStep)}
        >
          <ChevronDown className={iconClass} />
        </Button>
        <div />
      </div>
    </div>
  )
}
