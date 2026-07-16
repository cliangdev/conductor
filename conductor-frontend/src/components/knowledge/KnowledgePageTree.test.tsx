import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { vi } from 'vitest'
import { KnowledgePageTree } from './KnowledgePageTree'
import type { KnowledgeTreeSection } from '@/lib/knowledgeTree'

const SECTIONS: KnowledgeTreeSection[] = [
  {
    id: 'architecture',
    label: 'Architecture',
    pages: [
      { path: 'architecture/workflow-engine.md', title: 'Workflow Engine', type: 'architecture' },
      { path: 'architecture/data-model.md', title: 'Data Model', type: 'architecture' },
    ],
  },
  {
    id: '',
    label: 'Pages',
    pages: [{ path: '_schema.md', title: 'Schema Guide', type: 'project' }],
  },
]

describe('KnowledgePageTree', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders every section expanded by default, with the active page highlighted', () => {
    render(
      <KnowledgePageTree
        projectId="proj-1"
        sections={SECTIONS}
        activePath="architecture/data-model.md"
        onNavigate={vi.fn()}
      />
    )

    expect(screen.getByText('Architecture')).toBeInTheDocument()
    expect(screen.getByText('Pages')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Data Model' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('button', { name: 'Workflow Engine' })).not.toHaveAttribute('aria-current')
  })

  it('exposes proper nav/list semantics and disclosure state', () => {
    render(
      <KnowledgePageTree
        projectId="proj-1"
        sections={SECTIONS}
        activePath="architecture/data-model.md"
        onNavigate={vi.fn()}
      />
    )

    const nav = screen.getByRole('navigation', { name: 'Knowledge pages' })
    expect(nav).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Architecture' })).toHaveAttribute('aria-expanded', 'true')
    // Sections render as list items within lists, not bare divs.
    expect(screen.getAllByRole('list').length).toBeGreaterThan(0)
  })

  it('navigates to a page on click', () => {
    const onNavigate = vi.fn()
    render(
      <KnowledgePageTree projectId="proj-1" sections={SECTIONS} activePath="" onNavigate={onNavigate} />
    )

    fireEvent.click(screen.getByRole('button', { name: 'Schema Guide' }))
    expect(onNavigate).toHaveBeenCalledWith('_schema.md')
  })

  it('collapses a section and persists the choice across remounts', () => {
    const { unmount } = render(
      <KnowledgePageTree projectId="proj-1" sections={SECTIONS} activePath="" onNavigate={vi.fn()} />
    )

    fireEvent.click(screen.getByRole('button', { name: 'Architecture' }))
    expect(screen.queryByText('Workflow Engine')).not.toBeInTheDocument()
    unmount()

    render(<KnowledgePageTree projectId="proj-1" sections={SECTIONS} activePath="" onNavigate={vi.fn()} />)
    expect(screen.queryByText('Workflow Engine')).not.toBeInTheDocument()
  })
})
