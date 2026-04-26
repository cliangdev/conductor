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
  TransformWrapper: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
  TransformComponent: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}))

import { MermaidDiagram } from './MermaidDiagram'

describe('MermaidDiagram', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders an expand-to-fullscreen button', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    expect(screen.getByLabelText('Expand diagram to fullscreen')).toBeInTheDocument()
  })

  it('does not mount the fullscreen viewer until the button is clicked', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    expect(screen.queryByLabelText('Diagram controls')).not.toBeInTheDocument()
  })

  it('opens the fullscreen viewer with toolbar controls when the expand button is clicked', () => {
    render(<MermaidDiagram chart="graph TD; A-->B" />)
    fireEvent.click(screen.getByLabelText('Expand diagram to fullscreen'))
    expect(screen.getByLabelText('Diagram controls')).toBeInTheDocument()
    expect(screen.getByLabelText('Zoom in')).toBeInTheDocument()
    expect(screen.getByLabelText('Zoom out')).toBeInTheDocument()
    expect(screen.getByLabelText('Reset zoom')).toBeInTheDocument()
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
