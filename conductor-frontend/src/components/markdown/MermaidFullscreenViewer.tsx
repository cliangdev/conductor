'use client'
import { useEffect, useRef } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { TransformWrapper, TransformComponent, type ReactZoomPanPinchRef } from 'react-zoom-pan-pinch'
import { ZoomIn, ZoomOut, RotateCcw, X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { MermaidRenderer } from './MermaidRenderer'

interface Props {
  chart: string
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function MermaidFullscreenViewer({ chart, open, onOpenChange }: Props) {
  const transformRef = useRef<ReactZoomPanPinchRef | null>(null)

  useEffect(() => {
    if (!open) return
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null
      if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)) {
        return
      }
      if (e.key === '+' || e.key === '=') {
        e.preventDefault()
        transformRef.current?.zoomIn()
      } else if (e.key === '-' || e.key === '_') {
        e.preventDefault()
        transformRef.current?.zoomOut()
      } else if (e.key === '0') {
        e.preventDefault()
        transformRef.current?.resetTransform()
      }
    }
    window.addEventListener('keydown', handler)
    return () => window.removeEventListener('keydown', handler)
  }, [open])

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Backdrop className="fixed inset-0 bg-black/60 dark:bg-black/80 z-40" />
        <Dialog.Popup className="fixed inset-0 z-50 bg-background outline-none">
          <Dialog.Title className="sr-only">Mermaid diagram, fullscreen view</Dialog.Title>
          <Dialog.Description className="sr-only">
            Use mouse wheel or pinch to zoom, drag to pan. Press Escape to close, plus and minus to zoom, zero to reset.
          </Dialog.Description>

          <TransformWrapper
            ref={transformRef}
            minScale={0.2}
            maxScale={8}
            limitToBounds={false}
            wheel={{ step: 0.1 }}
            doubleClick={{ mode: 'toggle' }}
            centerOnInit
          >
            <TransformComponent
              wrapperStyle={{ width: '100vw', height: '100vh' }}
              contentStyle={{ width: '100%', height: '100%' }}
            >
              <div className="w-screen h-screen flex items-center justify-center p-8">
                <MermaidRenderer chart={chart} />
              </div>
            </TransformComponent>
          </TransformWrapper>

          <div
            className="fixed bottom-6 left-1/2 -translate-x-1/2 z-10 flex items-center gap-1 rounded-lg border border-border bg-popover/90 backdrop-blur px-2 py-1 shadow-lg"
            role="toolbar"
            aria-label="Diagram controls"
          >
            <Button
              variant="ghost"
              size="icon"
              aria-label="Zoom out"
              onClick={() => transformRef.current?.zoomOut()}
            >
              <ZoomOut className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Reset zoom"
              onClick={() => transformRef.current?.resetTransform()}
            >
              <RotateCcw className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="icon"
              aria-label="Zoom in"
              onClick={() => transformRef.current?.zoomIn()}
            >
              <ZoomIn className="h-4 w-4" />
            </Button>
            <div className="mx-1 h-5 w-px bg-border" aria-hidden="true" />
            <Button
              variant="ghost"
              size="icon"
              aria-label="Close fullscreen"
              onClick={() => onOpenChange(false)}
            >
              <X className="h-4 w-4" />
            </Button>
          </div>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
