import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

vi.mock('next-themes', () => ({
  useTheme: () => ({ resolvedTheme: 'light' }),
}))

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: vi.fn().mockResolvedValue({ svg: '<svg data-testid="mermaid-svg"></svg>' }),
  },
}))

vi.mock('react-zoom-pan-pinch', () => ({
  TransformWrapper: ({ children, ref }: { children: React.ReactNode; ref?: React.Ref<unknown> }) => {
    if (typeof ref === 'function') {
      ref({
        state: { positionX: 0, positionY: 0, scale: 1 },
        setTransform: vi.fn(),
        zoomIn: vi.fn(),
        zoomOut: vi.fn(),
        resetTransform: vi.fn(),
        centerView: vi.fn(),
      })
    } else if (ref) {
      ;(ref as React.MutableRefObject<unknown>).current = {
        state: { positionX: 0, positionY: 0, scale: 1 },
        setTransform: vi.fn(),
        zoomIn: vi.fn(),
        zoomOut: vi.fn(),
        resetTransform: vi.fn(),
        centerView: vi.fn(),
      }
    }
    return <div>{children}</div>
  },
  TransformComponent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

Object.assign(navigator, {
  clipboard: {
    writeText: vi.fn().mockResolvedValue(undefined),
  },
})

import { MermaidDiagram } from './MermaidDiagram'

describe('MermaidDiagram', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders an expand-to-fullscreen button', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    expect(screen.getByLabelText('Expand diagram to fullscreen')).toBeInTheDocument()
  })

  it('renders inline pan/zoom controls without mounting the fullscreen viewer', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    expect(screen.getByLabelText('Inline diagram controls')).toBeInTheDocument()
    expect(screen.queryByLabelText('Diagram controls')).not.toBeInTheDocument()
  })

  it('renders inline pad and zoom buttons with expected labels', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    expect(screen.getByLabelText('Pan up')).toBeInTheDocument()
    expect(screen.getByLabelText('Pan down')).toBeInTheDocument()
    expect(screen.getByLabelText('Pan left')).toBeInTheDocument()
    expect(screen.getByLabelText('Pan right')).toBeInTheDocument()
    expect(screen.getByLabelText('Reset view')).toBeInTheDocument()
    expect(screen.getByLabelText('Zoom in')).toBeInTheDocument()
    expect(screen.getByLabelText('Zoom out')).toBeInTheDocument()
  })

  it('copies the raw diagram source to the clipboard', async () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    fireEvent.click(screen.getByLabelText('Copy diagram source'))
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('graph TD; A-->B')
  })

  it('opens the fullscreen viewer with its own toolbar controls when the expand button is clicked', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    fireEvent.click(screen.getByLabelText('Expand diagram to fullscreen'))
    expect(screen.getByLabelText('Diagram controls')).toBeInTheDocument()
    expect(screen.getByLabelText('Close fullscreen')).toBeInTheDocument()
  })

  it('closes the fullscreen viewer when the close button is clicked', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    fireEvent.click(screen.getByLabelText('Expand diagram to fullscreen'))
    expect(screen.getByLabelText('Diagram controls')).toBeInTheDocument()
    fireEvent.click(screen.getByLabelText('Close fullscreen'))
    expect(screen.queryByLabelText('Diagram controls')).not.toBeInTheDocument()
  })
})
