'use client'
import { useEffect, useRef } from 'react'
import { Dialog } from '@base-ui/react/dialog'
import { TransformWrapper, TransformComponent, type ReactZoomPanPinchRef } from 'react-zoom-pan-pinch'
import { X } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { MermaidRenderer } from './MermaidRenderer'
import { MermaidControls } from './MermaidControls'

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

          <MermaidControls
            transformRef={transformRef}
            size="default"
            ariaLabel="Diagram controls"
            className="fixed bottom-6 right-6 z-10"
          />

          <Button
            variant="ghost"
            size="icon"
            aria-label="Close fullscreen"
            onClick={() => onOpenChange(false)}
            className="fixed top-4 right-4 z-10"
          >
            <X className="h-4 w-4" />
          </Button>
        </Dialog.Popup>
      </Dialog.Portal>
    </Dialog.Root>
  )
}
