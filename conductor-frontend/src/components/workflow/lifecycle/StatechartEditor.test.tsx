import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { StatechartEditor } from './StatechartEditor'
import { emptyDefinition } from '@/lib/workflowDefinition'

describe('StatechartEditor', () => {
  it('renders column headers and a labeled remove action per status row', () => {
    const onChange = vi.fn()
    render(<StatechartEditor value={emptyDefinition()} onChange={onChange} />)

    expect(screen.getByText('Status ID')).toBeInTheDocument()
    expect(screen.getByText('Category')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove status TODO' })).toBeInTheDocument()
  })

  it('adds a status via the Add status action', () => {
    const def = emptyDefinition()
    const onChange = vi.fn()
    render(<StatechartEditor value={def} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: /add status/i }))

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ statuses: [...def.statuses, { id: '', category: 'open' }] })
    )
  })

  it('removes a transition via its icon button', () => {
    const def = emptyDefinition()
    const onChange = vi.fn()
    render(<StatechartEditor value={def} onChange={onChange} />)

    fireEvent.click(screen.getByRole('button', { name: 'Remove transition 1' }))

    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ transitions: [def.transitions[1]] }))
  })

  it('reserves the Skill column for non-skill steps so step rows stay grid-aligned', () => {
    const def = emptyDefinition()
    def.transitions[0].steps = [{ kind: 'http', mode: 'BLOCKING' }]
    render(<StatechartEditor value={def} onChange={vi.fn()} />)

    expect(screen.getByText('Kind')).toBeInTheDocument()
    expect(screen.queryByLabelText('Skill')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Remove step 1' })).toBeInTheDocument()
  })

  it('shows the Skill select once a step is added and set to kind "skill"', () => {
    const def = emptyDefinition()
    def.transitions[0].steps = [{ kind: 'skill', mode: 'BLOCKING' }]
    render(<StatechartEditor value={def} onChange={vi.fn()} />)

    expect(screen.getByLabelText('Skill')).toBeInTheDocument()
  })
})
