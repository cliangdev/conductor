import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiGetPage: vi.fn(),
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

import { apiGetPage } from '@/lib/api'
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

/** A fake server: applies includeBlocked / mediaKind / q / limit / cursor like the real list endpoint. */
function serve(all: CreativePhoto[]) {
  ;(apiGetPage as Mock).mockImplementation(async (path: string) => {
    const q = new URL(`http://x${path}`).searchParams
    const kinds = q.get('mediaKind')?.split(',')
    const needle = q.get('q')?.toLowerCase()
    let list = all
      .filter((r) => q.get('includeBlocked') === 'true' || !r.blocked)
      .filter((r) => !kinds || kinds.includes(r.mediaKind ?? 'IMAGE'))
      .filter((r) => !needle || (r.label ?? '').toLowerCase().includes(needle))
      .filter((r) => q.get('aiGenerated') !== 'true' || r.aiGenerated)
      .sort((a, b) => b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id))
    const cursor = q.get('cursor')
    if (cursor) list = list.slice(list.findIndex((r) => r.id === cursor.replace('after:', '')) + 1)
    const limit = q.get('limit') ? Number(q.get('limit')) : null
    const page = limit == null ? list : list.slice(0, limit)
    const next = limit != null && list.length > limit ? `after:${page[page.length - 1].id}` : null
    return { data: page, nextCursor: next }
  })
}

const paths = () => (apiGetPage as Mock).mock.calls.map((c) => new URL(`http://x${c[0] as string}`).searchParams)

function manyImages(n: number): CreativePhoto[] {
  return Array.from({ length: n }, (_, i) =>
    photo(`m${String(i).padStart(3, '0')}`, `Shot ${i}`, 10, 10, { checked: true, createdAt: `2026-02-01T00:${String(59 - Math.floor(i / 60)).padStart(2, '0')}:${String(59 - (i % 60)).padStart(2, '0')}Z` }),
  )
}

function setup(props: Partial<React.ComponentProps<typeof MediaChooser>> = {}) {
  serve(rows)
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
    ;(apiGetPage as Mock).mockReturnValue(new Promise(() => {}))
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
    serve([photo('s1', '01-import 4x5 slide 01'), photo('s2', '01-import 4x5 slide 02'), photo('x', 'Lone photo')])
    render(<MediaChooser open onOpenChange={vi.fn()} projectId="proj-1" token="tok" onPicked={vi.fn()} />)
    await screen.findByTestId('chooser-tile-s1')
    expect(screen.queryByRole('heading', { name: '01-import' })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Name' }))
    expect(screen.getByRole('heading', { name: '01-import' })).toBeInTheDocument()
  })

  it('asks for the first page only, without blocked items, scoped to the chooser kinds', async () => {
    setup()
    await screen.findByTestId('chooser-tile-a')
    expect(apiGetPage).toHaveBeenCalledTimes(1)
    const q = paths()[0]
    expect(q.get('limit')).toBe('60')
    expect(q.get('includeBlocked')).toBe('false')
    expect(q.get('mediaKind')).toBe('IMAGE,VIDEO')
    expect(q.get('cursor')).toBeNull()
  })

  it('a single-kind chooser sends just that kind', async () => {
    setup({ kinds: ['VIDEO'] })
    await screen.findByTestId('chooser-tile-d')
    expect(paths()[0].get('mediaKind')).toBe('VIDEO')
  })

  it('loads the next page on "Load more", appends it, and drops the button on the last page', async () => {
    serve(manyImages(130))
    render(<MediaChooser open onOpenChange={vi.fn()} projectId="proj-1" token="tok" onPicked={vi.fn()} />)
    await screen.findByTestId('chooser-tile-m000')
    expect(screen.getAllByTestId(/^chooser-tile-/)).toHaveLength(60)
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getAllByTestId(/^chooser-tile-/)).toHaveLength(120))
    expect(paths()[1].get('cursor')).toBe('after:m059')
    expect(paths()[1].get('limit')).toBe('60')
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getAllByTestId(/^chooser-tile-/)).toHaveLength(130))
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('search is sent to the server and restarts from the first page', async () => {
    serve(manyImages(130))
    render(<MediaChooser open onOpenChange={vi.fn()} projectId="proj-1" token="tok" onPicked={vi.fn()} />)
    await screen.findByTestId('chooser-tile-m000')
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))
    await waitFor(() => expect(screen.getAllByTestId(/^chooser-tile-/)).toHaveLength(120))
    await userEvent.type(screen.getByLabelText('Search by label'), 'Shot 12')
    await waitFor(() => expect(paths().some((q) => q.get('q') === 'Shot 12')).toBe(true))
    const search = paths().filter((q) => q.get('q') === 'Shot 12')
    expect(search.every((q) => q.get('cursor') == null)).toBe(true)
    // "Shot 12", "Shot 120".."Shot 129" = 11 matches, all on one page.
    await waitFor(() => expect(screen.getAllByTestId(/^chooser-tile-/)).toHaveLength(11))
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('switching the kind tab re-queries the first page for that kind', async () => {
    setup()
    await screen.findByTestId('chooser-tile-a')
    await userEvent.click(screen.getByRole('tab', { name: 'Video' }))
    await waitFor(() => expect(paths().at(-1)!.get('mediaKind')).toBe('VIDEO'))
    expect(paths().at(-1)!.get('cursor')).toBeNull()
  })

  it('discards a slow response for an older query', async () => {
    serve(rows)
    const impl = (apiGetPage as Mock).getMockImplementation()!
    let releaseFirst!: () => void
    const gate = new Promise<void>((r) => (releaseFirst = r))
    ;(apiGetPage as Mock).mockImplementation(async (path: string, ...rest: unknown[]) => {
      if (!new URL(`http://x${path}`).searchParams.get('q')) {
        await gate // the unfiltered request is slow
      }
      return impl(path, ...rest)
    })
    render(<MediaChooser open onOpenChange={vi.fn()} projectId="proj-1" token="tok" onPicked={vi.fn()} />)
    await userEvent.type(screen.getByLabelText('Search by label'), 'City')
    await screen.findByTestId('chooser-tile-b')
    expect(screen.queryByTestId('chooser-tile-a')).not.toBeInTheDocument()
    releaseFirst()
    await new Promise((r) => setTimeout(r, 20))
    // The older, unfiltered page must not overwrite the search results.
    expect(screen.queryByTestId('chooser-tile-a')).not.toBeInTheDocument()
    expect(screen.getByTestId('chooser-tile-b')).toBeInTheDocument()
  })

  it('shows a load error without losing what is on screen', async () => {
    serve(manyImages(130))
    render(<MediaChooser open onOpenChange={vi.fn()} projectId="proj-1" token="tok" onPicked={vi.fn()} />)
    await screen.findByTestId('chooser-tile-m000')
    ;(apiGetPage as Mock).mockRejectedValueOnce({})
    await userEvent.click(screen.getByRole('button', { name: 'Load more' }))
    expect(await screen.findByText('Could not load more media — please try again.')).toBeInTheDocument()
    expect(screen.getAllByTestId(/^chooser-tile-/)).toHaveLength(60)
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
