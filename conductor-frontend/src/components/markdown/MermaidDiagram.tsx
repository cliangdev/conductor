'use client'
import { useCallback, useRef, useState } from 'react'
import { Check, Copy, Maximize2 } from 'lucide-react'
import { TransformWrapper, TransformComponent, type ReactZoomPanPinchRef } from 'react-zoom-pan-pinch'
import { MermaidRenderer } from './MermaidRenderer'
import { MermaidFullscreenViewer } from './MermaidFullscreenViewer'
import { MermaidControls } from './MermaidControls'
import { computeFitScale } from './mermaidFit'
import { toastError, toastSuccess } from '@/components/ui/toast'

interface Props {
  chart: string
}

const MIN_SCALE = 0.2
const MAX_SCALE = 8

export function MermaidDiagram({ chart }: Props) {
  const [open, setOpen] = useState(false)
  const [copied, setCopied] = useState(false)
  const containerRef = useRef<HTMLDivElement>(null)
  const transformRef = useRef<ReactZoomPanPinchRef | null>(null)

  const handleRendered = useCallback((svg: SVGSVGElement) => {
    const container = containerRef.current
    if (!container) return
    const fitScale = computeFitScale(container, svg, { minScale: MIN_SCALE, maxScale: MAX_SCALE, padding: 16 })
    transformRef.current?.centerView(fitScale, 0)
  }, [])

  const handleCopy = () => {
    navigator.clipboard
      .writeText(chart)
      .then(() => {
        setCopied(true)
        toastSuccess('Diagram source copied')
        setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => {
        toastError('Could not copy diagram source')
      })
  }

  return (
    <>
      <div
        ref={containerRef}
        className="group relative my-4 h-[min(60vh,480px)] min-h-[200px] w-full overflow-hidden rounded-md border border-border"
      >
        <TransformWrapper
          ref={transformRef}
          minScale={MIN_SCALE}
          maxScale={MAX_SCALE}
          limitToBounds={false}
          wheel={{ wheelDisabled: true }}
          doubleClick={{ mode: 'toggle' }}
          initialScale={1}
        >
          <TransformComponent
            wrapperStyle={{ width: '100%', height: '100%' }}
            contentStyle={{ width: '100%', height: '100%' }}
          >
            <div className="flex h-full w-full items-center justify-center p-4">
              <MermaidRenderer chart={chart} onRendered={handleRendered} />
            </div>
          </TransformComponent>
        </TransformWrapper>

        <div className="absolute top-2 right-2 flex gap-1 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
          <button
            type="button"
            aria-label="Copy diagram source"
            onClick={handleCopy}
            className="inline-flex items-center justify-center h-8 w-8 rounded-md border border-border bg-popover/90 backdrop-blur text-muted-foreground hover:text-foreground hover:bg-accent shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            {copied ? <Check className="h-4 w-4 text-status-done" /> : <Copy className="h-4 w-4" />}
          </button>
          <button
            type="button"
            aria-label="Expand diagram to fullscreen"
            onClick={() => setOpen(true)}
            className="inline-flex items-center justify-center h-8 w-8 rounded-md border border-border bg-popover/90 backdrop-blur text-muted-foreground hover:text-foreground hover:bg-accent shadow-sm transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <Maximize2 className="h-4 w-4" />
          </button>
        </div>

        <MermaidControls
          transformRef={transformRef}
          size="sm"
          ariaLabel="Inline diagram controls"
          className="absolute bottom-2 right-2 opacity-100 sm:opacity-0 sm:group-hover:opacity-100 focus-within:opacity-100 transition-opacity"
        />
      </div>
      {open && (
        <MermaidFullscreenViewer chart={chart} open={open} onOpenChange={setOpen} />
      )}
    </>
  )
}
