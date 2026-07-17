'use client'

// The shared xyflow rendering shell for every workflow diagram (automation job graph + lifecycle
// statechart). It owns the boilerplate the two diagrams used to duplicate: the ReactFlowProvider, the
// common <ReactFlow> config, the dotted Background, the zoom controls, and a fullscreen overlay. The
// domain-specific bits — how a YAML/statechart maps to nodes/edges, the node renderers, the dagre
// layout — stay in each caller; FlowCanvas only takes the finished nodes/edges/nodeTypes.

import { useCallback, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  ReactFlow,
  ReactFlowProvider,
  Background,
  useReactFlow,
  type Node,
  type Edge,
  type NodeTypes,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { Maximize2, Minimize2, Plus, Minus } from 'lucide-react'

// ── Controls (zoom + fullscreen) ─────────────────────────────────────────────
// Rendered as an absolute sibling of <ReactFlow> (outside its overflow-hidden layer) so the buttons
// are never clipped. Must live inside <ReactFlowProvider> to use useReactFlow().
function CanvasControls({
  fullscreen,
  onToggleFullscreen,
}: {
  fullscreen: boolean
  onToggleFullscreen: () => void
}) {
  const { zoomIn, zoomOut } = useReactFlow()
  const btn =
    'flex h-9 w-9 items-center justify-center text-foreground hover:bg-surface-3 active:bg-surface-3'
  return (
    <div className="absolute top-4 right-4 z-10 flex flex-col overflow-hidden rounded-lg border border-border bg-surface shadow-md">
      <button onClick={() => zoomIn()} className={`${btn} border-b border-border`} aria-label="Zoom in">
        <Plus size={16} />
      </button>
      <button onClick={() => zoomOut()} className={`${btn} border-b border-border`} aria-label="Zoom out">
        <Minus size={16} />
      </button>
      <button
        onClick={onToggleFullscreen}
        className={btn}
        aria-label={fullscreen ? 'Exit fullscreen' : 'Fullscreen'}
        title={fullscreen ? 'Exit fullscreen (Esc)' : 'Fullscreen'}
      >
        {fullscreen ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
      </button>
    </div>
  )
}

// Re-fit the view whenever fullscreen toggles, since the canvas size changes. Lives inside the provider.
function RefitOnResize({ trigger }: { trigger: boolean }) {
  const { fitView } = useReactFlow()
  useEffect(() => {
    const id = setTimeout(() => fitView({ padding: 0.3, duration: 200 }), 80)
    return () => clearTimeout(id)
  }, [trigger, fitView])
  return null
}

export interface FlowCanvasProps {
  nodes: Node[]
  edges: Edge[]
  nodeTypes: NodeTypes
}

export function FlowCanvas({ nodes, edges, nodeTypes }: FlowCanvasProps) {
  const [fullscreen, setFullscreen] = useState(false)
  const toggleFullscreen = useCallback(() => setFullscreen((v) => !v), [])

  // Escape always exits fullscreen — a guaranteed way out beyond the toggle button.
  useEffect(() => {
    if (!fullscreen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setFullscreen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [fullscreen])

  const canvas = (
    <ReactFlowProvider>
      <div className={fullscreen ? 'fixed inset-0 z-[100] bg-background' : 'relative h-full w-full rounded-md'}>
        {/* ReactFlow gets its own overflow-hidden so it clips internally; controls sit outside it. */}
        <div className="absolute inset-0 overflow-hidden rounded-md">
          <ReactFlow
            nodes={nodes}
            edges={edges}
            nodeTypes={nodeTypes}
            fitView
            fitViewOptions={{ padding: 0.3 }}
            nodesDraggable={false}
            nodesConnectable={false}
            elementsSelectable={false}
            proOptions={{ hideAttribution: true }}
          >
            <Background color="hsl(var(--border))" gap={16} />
          </ReactFlow>
        </div>
        <CanvasControls fullscreen={fullscreen} onToggleFullscreen={toggleFullscreen} />
        <RefitOnResize trigger={fullscreen} />
      </div>
    </ReactFlowProvider>
  )

  // When fullscreen, portal to <body> so the overlay escapes any ancestor's overflow-hidden / stacking.
  return fullscreen && typeof document !== 'undefined' ? createPortal(canvas, document.body) : canvas
}

export default FlowCanvas
