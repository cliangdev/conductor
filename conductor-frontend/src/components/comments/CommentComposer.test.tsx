import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'

const { apiGet, apiPost } = vi.hoisted(() => ({ apiGet: vi.fn(), apiPost: vi.fn() }))
vi.mock('@/lib/api', () => ({ apiGet, apiPost, apiErrorMessage: (_e: unknown, f: string) => f }))

import { CommentComposer } from './CommentComposer'
import { CommentsFeaturesProvider } from './CommentsFeatures'

const MENTIONABLES = [
  { kind: 'USER', id: 'u1', name: 'Ada Lovelace', avatarUrl: null, avatar: null },
  { kind: 'USER', id: 'u2', name: 'Alan Turing', avatarUrl: null, avatar: null },
  { kind: 'AGENT', id: 'a1', name: 'Scribe', avatarUrl: null, avatar: { emoji: '✍️', color: 'teal' } },
]

const target = { projectId: 'p1', workItemId: 'wi1', documentId: 'd1' }
const anchor = { exact: 'up to ten posts', prefix: 'show ', suffix: ' a week' }

function setup(props: Partial<React.ComponentProps<typeof CommentComposer>> = {}) {
  const onSubmit = vi.fn().mockResolvedValue(undefined)
  render(
    <CommentsFeaturesProvider target={target} token="tok" decideSuggestion={vi.fn()}>
      <CommentComposer onSubmit={onSubmit} {...props} />
    </CommentsFeaturesProvider>
  )
  return { onSubmit, box: screen.getByLabelText('Comment text') as HTMLTextAreaElement }
}

function type(box: HTMLTextAreaElement, value: string) {
  fireEvent.change(box, { target: { value, selectionStart: value.length, selectionEnd: value.length } })
}

describe('mention picker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    apiGet.mockImplementation(async (url: string) => {
      const q = decodeURIComponent(url.split('q=')[1] ?? '').toLowerCase()
      return MENTIONABLES.filter((m) => m.name.toLowerCase().includes(q))
    })
  })

  it('opens on @, lists people then agents, with the Agent tag', async () => {
    const { box } = setup()
    type(box, 'ping @')
    const list = await screen.findByRole('listbox', { name: 'Mention someone' })
    const options = screen.getAllByRole('option')
    expect(options).toHaveLength(3)
    expect(options[2]).toHaveTextContent('Scribe')
    expect(options[2]).toHaveTextContent('Agent')
    expect(list).toBeInTheDocument()
    expect(box).toHaveAttribute('role', 'combobox')
    expect(box.getAttribute('aria-activedescendant')).toBe(options[0].id)
    expect(apiGet).toHaveBeenCalledWith('/api/v2/projects/p1/comments/mentionables?q=', 'tok')
  })

  it('does not open on an @ inside a word (an email address)', async () => {
    const { box } = setup()
    type(box, 'mail me@')
    await new Promise((r) => setTimeout(r, 250))
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(apiGet).not.toHaveBeenCalled()
  })

  it('filters by what is typed after the @ (debounced)', async () => {
    const { box } = setup()
    type(box, '@a')
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(2))
    expect(apiGet).toHaveBeenLastCalledWith('/api/v2/projects/p1/comments/mentionables?q=a', 'tok')
    type(box, '@alan')
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(1))
    expect(screen.getByRole('option')).toHaveTextContent('Alan Turing')
  })

  it('moves with the arrow keys and inserts readable text on Enter, serializing the token on send', async () => {
    const { box, onSubmit } = setup()
    type(box, 'hey @')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'ArrowDown' })
    expect(screen.getAllByRole('option')[1]).toHaveAttribute('aria-selected', 'true')
    fireEvent.keyDown(box, { key: 'Enter' })
    expect(box.value).toBe('hey @Alan Turing ')
    expect(screen.queryByRole('listbox')).toBeNull()
    type(box, 'hey @Alan Turing please look')
    fireEvent.keyDown(box, { key: 'Enter', metaKey: true })
    await waitFor(() => expect(onSubmit).toHaveBeenCalled())
    expect(onSubmit).toHaveBeenCalledWith('hey @[Alan Turing](user:u2) please look')
  })

  it('selects an agent with Tab and by click', async () => {
    const { box, onSubmit } = setup()
    type(box, '@scr')
    await waitFor(() => expect(screen.getAllByRole('option')).toHaveLength(1))
    fireEvent.keyDown(box, { key: 'Tab' })
    expect(box.value).toBe('@Scribe ')
    // Let the caret move to after the inserted name, as it does before the next keystroke.
    await new Promise((r) => requestAnimationFrame(() => r(null)))
    type(box, '@Scribe and @')
    await screen.findByRole('listbox')
    fireEvent.click(screen.getAllByRole('option')[0])
    expect(box.value).toBe('@Scribe and @Ada Lovelace ')
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('@[Scribe](agent:a1) and @[Ada Lovelace](user:u1)'))
  })

  it('closes on Escape without cancelling the composer', async () => {
    const onCancel = vi.fn()
    const { box } = setup({ onCancel })
    type(box, '@')
    await screen.findByRole('listbox')
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(onCancel).not.toHaveBeenCalled()
    fireEvent.keyDown(box, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalled()
  })

  it('shows tokens as @Name when editing and sends them back as tokens', async () => {
    const { box, onSubmit } = setup({ initialValue: 'cc @[Ada Lovelace](user:u1) now', submitLabel: 'Save' })
    expect(box.value).toBe('cc @Ada Lovelace now')
    type(box, 'cc @Ada Lovelace now, thanks')
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('cc @[Ada Lovelace](user:u1) now, thanks'))
  })

  it('works without the provider (no picker, plain text)', async () => {
    const onSubmit = vi.fn()
    render(<CommentComposer onSubmit={onSubmit} />)
    const box = screen.getByLabelText('Comment text') as HTMLTextAreaElement
    type(box, '@al')
    await new Promise((r) => setTimeout(r, 250))
    expect(screen.queryByRole('listbox')).toBeNull()
  })
})

describe('suggest edit toggle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('is absent unless the composer is for a selection', () => {
    setup()
    expect(screen.queryByRole('button', { name: 'Suggest edit' })).toBeNull()
  })

  it('checks the selection, then swaps to replacement text prefilled with the quote plus a note', async () => {
    apiPost.mockResolvedValue({ suggestable: true, reason: null })
    const { onSubmit } = setup({ suggestFor: anchor })
    fireEvent.click(screen.getByRole('button', { name: 'Suggest edit' }))
    const replacement = (await screen.findByLabelText('Suggested text')) as HTMLTextAreaElement
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v2/projects/p1/comments/suggestion-check',
      { target: { workItemId: 'wi1', documentId: 'd1' }, anchor },
      'tok'
    )
    expect(replacement.value).toBe('up to ten posts')
    // Unchanged text cannot be sent.
    expect(screen.getByRole('button', { name: 'Comment' })).toBeDisabled()
    fireEvent.change(replacement, { target: { value: 'the top five posts' } })
    fireEvent.change(screen.getByLabelText('Why? (optional)'), { target: { value: 'Matches the spec' } })
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }))
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith('Matches the spec', { suggestion: { text: 'the top five posts' } })
    )
  })

  it('defaults the note and allows deleting the text', async () => {
    apiPost.mockResolvedValue({ suggestable: true })
    const { onSubmit } = setup({ suggestFor: anchor })
    fireEvent.click(screen.getByRole('button', { name: 'Suggest edit' }))
    const replacement = await screen.findByLabelText('Suggested text')
    fireEvent.change(replacement, { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('Suggested edit', { suggestion: { text: '' } }))
  })

  it('is disabled with the reason when the selection is not suggestable', async () => {
    apiPost.mockResolvedValue({ suggestable: false, reason: 'This text appears more than once or spans formatting — comment instead' })
    setup({ suggestFor: anchor })
    fireEvent.click(screen.getByRole('button', { name: 'Suggest edit' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Suggest edit' })).toBeDisabled())
    expect(screen.getByRole('button', { name: 'Suggest edit' })).toHaveAttribute('title', expect.stringContaining('appears more than once'))
    expect(screen.getByText(/appears more than once/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Suggested text')).toBeNull()
  })

  it('toggles back to a plain comment', async () => {
    apiPost.mockResolvedValue({ suggestable: true })
    const { onSubmit } = setup({ suggestFor: anchor })
    fireEvent.click(screen.getByRole('button', { name: 'Suggest edit' }))
    await screen.findByLabelText('Suggested text')
    fireEvent.click(screen.getByRole('button', { name: 'Suggest edit' }))
    expect(screen.queryByLabelText('Suggested text')).toBeNull()
    type(screen.getByLabelText('Comment text') as HTMLTextAreaElement, 'just a comment')
    fireEvent.click(screen.getByRole('button', { name: 'Comment' }))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('just a comment'))
  })
})
