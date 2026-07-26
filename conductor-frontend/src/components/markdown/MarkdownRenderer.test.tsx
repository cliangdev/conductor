import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'

// MermaidDiagram transitively pulls in mermaid + react-zoom-pan-pinch, neither of which survives jsdom.
vi.mock('./MermaidDiagram', () => ({
  MermaidDiagram: ({ chart }: { chart: string }) => <div data-testid="mermaid">{chart}</div>,
}))
vi.mock('./SignedImage', () => ({
  SignedImage: ({ alt }: { alt?: string }) => <img alt={alt} />,
}))

import { MarkdownRenderer } from './MarkdownRenderer'

describe('MarkdownRenderer task checkboxes', () => {
  it('renders disabled native checkboxes when onToggleTask is not given', () => {
    const { container } = render(<MarkdownRenderer content={'- [ ] alpha\n- [x] beta'} />)

    const inputs = container.querySelectorAll('input[type="checkbox"]')
    expect(inputs).toHaveLength(2)
    inputs.forEach((input) => expect(input).toBeDisabled())
    // Native inputs also carry role="checkbox", so the tag name is what distinguishes the two modes.
    screen.getAllByRole('checkbox').forEach((box) => expect(box.tagName).toBe('INPUT'))
  })

  it('renders interactive checkboxes when onToggleTask is given', () => {
    const { container } = render(
      <MarkdownRenderer content={'- [ ] alpha\n- [x] beta'} onToggleTask={vi.fn()} />
    )

    expect(container.querySelectorAll('input[type="checkbox"]')).toHaveLength(0)

    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(2)
    boxes.forEach((box) => expect(box.tagName).toBe('BUTTON'))
    expect(boxes[0]).toHaveAttribute('aria-checked', 'false')
    expect(boxes[1]).toHaveAttribute('aria-checked', 'true')
  })

  it('reports the source line and the requested new state on click', () => {
    const onToggleTask = vi.fn()
    render(
      <MarkdownRenderer content={'# Heading\n\n- [ ] alpha\n- [x] beta'} onToggleTask={onToggleTask} />
    )

    const boxes = screen.getAllByRole('checkbox')
    fireEvent.click(boxes[0])
    expect(onToggleTask).toHaveBeenCalledWith(3, true)

    fireEvent.click(boxes[1])
    expect(onToggleTask).toHaveBeenCalledWith(4, false)
  })

  it('reports lines against the raw content, frontmatter included', () => {
    const onToggleTask = vi.fn()
    const content = ['---', 'title: Notes', 'author: someone', '---', '', '- [ ] alpha'].join('\n')

    render(<MarkdownRenderer content={content} onToggleTask={onToggleTask} />)

    fireEvent.click(screen.getAllByRole('checkbox')[0])
    // The item is on line 6 of the raw string; without the frontmatter padding this reported line 1.
    expect(onToggleTask).toHaveBeenCalledWith(6, true)
  })

  it('does not render the frontmatter block itself', () => {
    const content = ['---', 'title: Notes', '---', '', 'Body text'].join('\n')
    render(<MarkdownRenderer content={content} />)

    expect(screen.getByText('Body text')).toBeInTheDocument()
    expect(screen.queryByText(/title: Notes/)).not.toBeInTheDocument()
  })

  it('maps nested task items to their own lines', () => {
    const onToggleTask = vi.fn()
    render(
      <MarkdownRenderer content={'- [ ] parent\n  - [ ] child'} onToggleTask={onToggleTask} />
    )

    const boxes = screen.getAllByRole('checkbox')
    expect(boxes).toHaveLength(2)

    fireEvent.click(boxes[1])
    expect(onToggleTask).toHaveBeenCalledWith(2, true)
  })

  it('maps blockquoted task items to their source line', () => {
    const onToggleTask = vi.fn()
    render(<MarkdownRenderer content={'text\n\n> - [ ] quoted'} onToggleTask={onToggleTask} />)

    fireEvent.click(screen.getAllByRole('checkbox')[0])
    expect(onToggleTask).toHaveBeenCalledWith(3, true)
  })

  it('ignores task syntax inside a fenced code block', () => {
    render(
      <MarkdownRenderer content={'```\n- [ ] not a task\n```'} onToggleTask={vi.fn()} />
    )

    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
  })

  it('does not stamp source lines unless asked', () => {
    const { container } = render(<MarkdownRenderer content={'alpha\n\nbeta'} />)
    expect(container.querySelectorAll('[data-line-start]')).toHaveLength(0)
  })

  it('stamps source line ranges on rendered blocks when annotateSourceLines is set', () => {
    const content = ['# Title', '', 'A paragraph', '', '- one', '- two'].join('\n')
    const { container } = render(<MarkdownRenderer content={content} annotateSourceLines />)

    const stamped = [...container.querySelectorAll<HTMLElement>('[data-line-start]')].map((el) => [
      el.tagName.toLowerCase(),
      el.dataset.lineStart,
      el.dataset.lineEnd,
    ])

    expect(stamped).toEqual([
      ['h1', '1', '1'],
      ['p', '3', '3'],
      // Anchors land on the list items, not the ul that wraps them — one marker per item.
      ['li', '5', '5'],
      ['li', '6', '6'],
    ])
  })

  it('stamps ranges against the raw content, frontmatter included', () => {
    const content = ['---', 'title: Notes', '---', '', 'Body'].join('\n')
    const { container } = render(<MarkdownRenderer content={content} annotateSourceLines />)

    expect(container.querySelector<HTMLElement>('[data-line-start]')?.dataset.lineStart).toBe('5')
  })

  it('gives a multi-line block a range spanning every line it came from', () => {
    const content = 'first line\nsecond line\nthird line'
    const { container } = render(<MarkdownRenderer content={content} annotateSourceLines />)

    const p = container.querySelector<HTMLElement>('p[data-line-start]')
    expect(p?.dataset.lineStart).toBe('1')
    expect(p?.dataset.lineEnd).toBe('3')
  })

  it('renders checkboxes non-interactive when tasksReadOnly is set', () => {
    const onToggleTask = vi.fn()
    render(
      <MarkdownRenderer content={'- [ ] alpha'} onToggleTask={onToggleTask} tasksReadOnly />
    )

    const box = screen.getByRole('checkbox')
    expect(box).toBeDisabled()

    fireEvent.click(box)
    expect(onToggleTask).not.toHaveBeenCalled()
  })
})
