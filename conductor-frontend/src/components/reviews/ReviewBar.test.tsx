import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReviewBar } from './ReviewBar'

function renderBar(props: Partial<React.ComponentProps<typeof ReviewBar>> = {}) {
  const onSubmit = vi.fn()
  const onCancel = vi.fn()
  render(
    <ReviewBar
      pendingCount={0}
      reviewOutcomes={['approve', 'request_changes']}
      submitting={false}
      onSubmit={onSubmit}
      onCancel={onCancel}
      {...props}
    />
  )
  return { onSubmit, onCancel }
}

describe('ReviewBar', () => {
  it('offers exactly the outcomes the Workflow declares, and nothing without them', () => {
    renderBar()
    expect(screen.getByRole('button', { name: 'Approve' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Request changes' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Comment' })).not.toBeInTheDocument()
  })

  it('approves straight away, summary or not', async () => {
    const { onSubmit } = renderBar()
    await userEvent.click(screen.getByRole('button', { name: 'Approve' }))
    expect(onSubmit).toHaveBeenCalledWith('APPROVED', '')
  })

  it('holds a request for changes until the reviewer says what needs to change', async () => {
    const { onSubmit } = renderBar()
    await userEvent.click(screen.getByRole('button', { name: 'Request changes' }))

    expect(onSubmit).not.toHaveBeenCalled()
    const summary = screen.getByLabelText('Review summary')
    expect(summary).toHaveAttribute('placeholder', 'What needs to change?')
    expect(screen.getByRole('alert')).toHaveTextContent('Say what needs to change')

    await userEvent.type(summary, 'The caption still says Tiktok, not TikTok.')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Request changes' }))
    expect(onSubmit).toHaveBeenCalledWith('CHANGES_REQUESTED', 'The caption still says Tiktok, not TikTok.')
  })

  it('counts pending comments where there is a document to comment on, and says what a review is otherwise', () => {
    renderBar({ pendingCount: 2 })
    expect(screen.getByText('Reviewing — 2 pending comments')).toBeInTheDocument()
  })

  it('does not talk about comments on an item with nothing to comment on', () => {
    renderBar({ commentable: false })
    expect(screen.getByText(/approve, or request changes and say why/)).toBeInTheDocument()
    expect(screen.queryByText(/pending comment/)).not.toBeInTheDocument()
  })
})
