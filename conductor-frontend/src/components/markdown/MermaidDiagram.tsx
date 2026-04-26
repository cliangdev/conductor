'use client'
import { useState } from 'react'
import { Maximize2 } from 'lucide-react'
import { MermaidRenderer } from './MermaidRenderer'
import { MermaidFullscreenViewer } from './MermaidFullscreenViewer'

interface Props {
  chart: string
}

export function MermaidDiagram({ chart }: Props) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <div className="group relative my-4">
        <MermaidRenderer chart={chart} className="overflow-x-auto" />
        <button
          type="button"
          aria-label="Expand diagram to fullscreen"
          onClick={() => setOpen(true)}
          className="absolute top-2 right-2 inline-flex items-center justify-center h-8 w-8 rounded-md border border-border bg-popover/90 backdrop-blur text-muted-foreground hover:text-foreground hover:bg-accent shadow-sm transition-opacity opacity-100 sm:opacity-0 sm:group-hover:opacity-100 focus-visible:opacity-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
        >
          <Maximize2 className="h-4 w-4" />
        </button>
      </div>
      {open && (
        <MermaidFullscreenViewer chart={chart} open={open} onOpenChange={setOpen} />
      )}
    </>
  )
}
