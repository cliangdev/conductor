import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => (err as { detail?: string })?.detail ?? fallback,
}))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => true,
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: vi.fn() }),
}))
vi.mock('@/components/marketing/creatives/mediaUpload', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/marketing/creatives/mediaUpload')>()),
  uploadMediaFile: vi.fn(),
}))

import { apiGet } from '@/lib/api'
import { uploadMediaFile } from '@/components/marketing/creatives/mediaUpload'
import { MediaChooser } from './MediaChooser'
import { photo } from './testFixtures'
import type { CreativePhoto } from '@/components/marketing/creatives/types'

const rows: CreativePhoto[] = [
  photo('a', 'Sunset beach', 1080, 1350, { checked: true, createdAt: '2026-01-03T00:00:00Z' }),
  photo('b', 'City night', 1080, 1080, { checked: false, createdAt: '2026-01-02T00:00:00Z', postCount: 3 }),
  photo('c', 'Secret', 1080, 1080, { blocked: true, createdAt: '2026-01-04T00:00:00Z' }),
  photo('d', 'Beach clip', 1080, 1920, { mediaKind: 'VIDEO', checked: true, createdAt: '2026-01-01T00:00:00Z' }),
  photo('e', 'Track', 1, 1, { mediaKind: 'AUDIO', checked: true }),
]

function setup(props: Partial<React.ComponentProps<typeof MediaChooser>> = {}) {
  ;(apiGet as Mock).mockResolvedValue(rows)
  const onPicked = vi.fn()
  const onOpenChange = vi.fn()
  render(<MediaChooser open onOpenChange={onOpenChange} projectId="proj-1" token="tok" onPicked={onPicked} {...props} />)
  return { onPicked, onOpenChange }
}

describe('MediaChooser', () => {
  beforeEach(() => vi.clearAllMocks())

  it('lists library media without blocked items or audio, and has no Checked/Block/Delete controls', async () => {
    setup()
    expect(await screen.findByTestId('chooser-tile-a')).toBeInTheDocument()
    expect(screen.queryByTestId('chooser-tile-c')).not.toBeInTheDocument()
    expect(screen.queryByTestId('chooser-tile-e')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^(check|checked|block|blocked)$/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /delete/i })).not.toBeInTheDocument()
    expect(within(screen.getByTestId('chooser-tile-b')).getByText('Not checked')).toBeInTheDocument()
    expect(within(screen.getByTestId('chooser-tile-a')).queryByText('Not checked')).not.toBeInTheDocument()
    expect(within(screen.getByTestId('chooser-tile-b')).getByText(/on 3 posts/)).toBeInTheDocument()
  })

  it('shows a skeleton while loading', () => {
    ;(apiGet as Mock).mockReturnValue(new Promise(() => {}))
    render(<MediaChooser open onOpenChange={vi.fn()} projectId="proj-1" token="tok" onPicked={vi.fn()} />)
    expect(screen.getByTestId('chooser-skeleton')).toBeInTheDocument()
  })

  it('search narrows the results and an empty result says so', async () => {
    setup()
    await screen.findByTestId('chooser-tile-a')
    await userEvent.type(screen.getByLabelText('Search by label'), 'beach')
    expect(screen.getByTestId('chooser-tile-a')).toBeInTheDocument()
    expect(screen.getByTestId('chooser-tile-d')).toBeInTheDocument()
    expect(screen.queryByTestId('chooser-tile-b')).not.toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Search by label'), 'zzz')
    expect(screen.getByText('Nothing matches')).toBeInTheDocument()
  })

  it('numbers picks in pick order and returns them in that order', async () => {
    const { onPicked, onOpenChange } = setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Select City night' }))
    await userEvent.click(screen.getByRole('button', { name: 'Select Sunset beach' }))
    expect(screen.getByTestId('order-badge-b')).toHaveTextContent('1')
    expect(screen.getByTestId('order-badge-a')).toHaveTextContent('2')
    expect(screen.getByText('2 selected')).toBeInTheDocument()
    expect(screen.getByTestId('order-badge-b')).toHaveClass('bg-primary')
    expect(screen.getByTestId('chooser-tile-b')).toHaveClass('border-primary', 'ring-primary')

    // Deselecting renumbers.
    await userEvent.click(screen.getByRole('button', { name: 'Select City night' }))
    expect(screen.getByTestId('order-badge-a')).toHaveTextContent('1')
    await userEvent.click(screen.getByRole('button', { name: 'Select City night' }))
    expect(screen.getByTestId('order-badge-a')).toHaveTextContent('1')
    expect(screen.getByTestId('order-badge-b')).toHaveTextContent('2')

    await userEvent.click(screen.getByRole('button', { name: 'Add 2' }))
    expect(onPicked.mock.calls[0][0].map((i: CreativePhoto) => i.id)).toEqual(['a', 'b'])
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('shows a Film placeholder, not a broken image, for a video with no poster', async () => {
    setup()
    const tile = await screen.findByTestId('chooser-tile-d')
    expect(tile.querySelector('img')).toBeNull()
  })

  it('Clear empties the selection', async () => {
    setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Select Sunset beach' }))
    await userEvent.click(screen.getByRole('button', { name: 'Clear' }))
    expect(screen.queryByTestId('order-badge-a')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Add' })).toBeDisabled()
  })

  it('single-select replaces the pick', async () => {
    const { onPicked } = setup({ multiple: false })
    await userEvent.click(await screen.findByRole('button', { name: 'Choose Sunset beach' }))
    await userEvent.click(screen.getByRole('button', { name: 'Choose City night' }))
    await userEvent.click(screen.getByRole('button', { name: 'Add 1' }))
    expect(onPicked.mock.calls[0][0].map((i: CreativePhoto) => i.id)).toEqual(['b'])
  })

  it('restricts to one kind when asked', async () => {
    setup({ kinds: ['VIDEO'] })
    expect(await screen.findByTestId('chooser-tile-d')).toBeInTheDocument()
    expect(screen.queryByTestId('chooser-tile-a')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Images' })).not.toBeInTheDocument()
  })

  it('expand opens the full-size viewer without selecting, and steps through the filtered list', async () => {
    setup()
    await screen.findByTestId('chooser-tile-a')
    await userEvent.click(screen.getByRole('button', { name: 'Expand Sunset beach' }))
    const viewer = await screen.findByRole('dialog', { name: 'Sunset beach' })
    expect(within(viewer).getByRole('img', { name: 'Sunset beach' })).toBeInTheDocument()
    expect(screen.queryByTestId('order-badge-a')).not.toBeInTheDocument()
    await userEvent.click(within(viewer).getByRole('button', { name: 'Next' }))
    expect(await screen.findByRole('dialog', { name: 'City night' })).toBeInTheDocument()
  })

  it('sorting by name groups set members under the set name', async () => {
    ;(apiGet as Mock).mockResolvedValue([
      photo('s1', '01-import 4x5 slide 01'),
      photo('s2', '01-import 4x5 slide 02'),
      photo('x', 'Lone photo'),
    ])
    render(<MediaChooser open onOpenChange={vi.fn()} projectId="proj-1" token="tok" onPicked={vi.fn()} />)
    await screen.findByTestId('chooser-tile-s1')
    expect(screen.queryByRole('heading', { name: '01-import' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(screen.getByRole('heading', { name: '01-import' })).toBeInTheDocument()
  })

  it('upload tab saves files to the library, selects them and returns to Library', async () => {
    ;(uploadMediaFile as Mock).mockResolvedValue(photo('new', 'own', 1080, 1080, { checked: true, warnings: [] }))
    setup()
    await userEvent.click(await screen.findByRole('tab', { name: 'Upload' }))
    await userEvent.upload(
      screen.getByLabelText('Files to upload'),
      new File([new Uint8Array([1])], 'own.jpg', { type: 'image/jpeg' }),
    )
    await waitFor(() => expect(uploadMediaFile).toHaveBeenCalled())
    expect((uploadMediaFile as Mock).mock.calls[0][2]).toBe('IMAGE')
    expect(await screen.findByTestId('order-badge-new')).toHaveTextContent('1')
    expect(screen.getByRole('tab', { name: 'Library', selected: true })).toBeInTheDocument()
  })

  it('keeps Details collapsed until opened', async () => {
    setup()
    await userEvent.click(await screen.findByRole('tab', { name: 'Upload' }))
    expect(screen.queryByLabelText('Licence')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /details/i }))
    expect(screen.getByLabelText('Licence')).toBeInTheDocument()
  })

  it('hands raw files to the caller when it uploads them itself', async () => {
    const onFilesChosen = vi.fn()
    const { onOpenChange } = setup({ onFilesChosen })
    await userEvent.click(await screen.findByRole('tab', { name: 'Upload' }))
    const file = new File([new Uint8Array([1])], 'own.jpg', { type: 'image/jpeg' })
    await userEvent.upload(screen.getByLabelText('Files to upload'), file)
    expect(onFilesChosen).toHaveBeenCalledWith([file])
    expect(uploadMediaFile).not.toHaveBeenCalled()
    expect(onOpenChange).toHaveBeenCalledWith(false)
  })

  it('reports an unsupported dropped file', async () => {
    setup()
    await userEvent.click(await screen.findByRole('tab', { name: 'Upload' }))
    const bad = new File(['x'], 'notes.txt', { type: 'text/plain' })
    fireEvent.drop(screen.getByTestId('chooser-dropzone'), { dataTransfer: { files: [bad] } })
    expect(await screen.findByText(/notes\.txt: unsupported file type/)).toBeInTheDocument()
  })

  it('renders an extra tab before Library', async () => {
    setup({ extraTabs: [{ value: 'creatives', label: 'Creatives', content: <p>creative cards</p> }], defaultTab: 'creatives' })
    expect(await screen.findByText('creative cards')).toBeInTheDocument()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['Creatives', 'Library', 'Upload'])
  })
})
