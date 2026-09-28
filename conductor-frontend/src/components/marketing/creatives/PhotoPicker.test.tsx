import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// The signed-URL PUT is an XMLHttpRequest jsdom cannot complete — same pattern as
// ComposePostPage.test.tsx. This file asserts on the mint and confirm calls around it.
vi.mock('@/components/workitems/MediaUploadPanel', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/workitems/MediaUploadPanel')>()),
  putToSignedUrl: vi.fn(async () => {}),
}))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

const { mockCan } = vi.hoisted(() => ({ mockCan: vi.fn((_cap?: string) => true) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => mockCan(cap),
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: mockCan, refresh: vi.fn() }),
}))

import { apiDelete, apiPost } from '@/lib/api'
import { PhotoPicker } from './PhotoPicker'
import type { CreativePhoto } from './types'

function photo(overrides: Partial<CreativePhoto> = {}): CreativePhoto {
  return {
    id: 'photo-1',
    projectId: 'proj-1',
    label: 'saved-not-cooked.webp',
    contentType: 'image/webp',
    sizeBytes: 12345,
    width: 2400,
    height: 3000,
    source: 'Unsplash',
    licence: 'CC0',
    aiGenerated: false,
    checked: true,
    blocked: false,
    focal: {},
    uploadStatus: 'UPLOADED',
    url: 'https://storage.example/photo-1.webp',
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('PhotoPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
  })

  it('picks an existing photo from the library grid', async () => {
    const onSelect = vi.fn()
    render(
      <PhotoPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        photos={[photo()]}
        onSelect={onSelect}
        onPhotosChanged={vi.fn()}
      />,
    )

    const grid = screen.getByTestId('photo-picker-grid')
    const tile = grid.querySelector('button')!
    await userEvent.click(tile)

    expect(onSelect).toHaveBeenCalledWith(photo())
  })

  it('uploads a new photo through mint -> PUT -> confirm and selects it', async () => {
    const pending = photo({
      id: 'photo-2',
      uploadStatus: 'PENDING',
      url: null,
      checked: false,
      uploadUrl: 'https://storage.example/upload-url',
    })
    const confirmed = photo({ id: 'photo-2', warnings: ['Long edge under 2160px'] })

    ;(apiPost as Mock).mockImplementation((path: string) => {
      if (path.endsWith('/confirm')) return Promise.resolve(confirmed)
      return Promise.resolve(pending)
    })

    const onSelect = vi.fn()
    const onPhotosChanged = vi.fn()
    render(
      <PhotoPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        photos={[]}
        onSelect={onSelect}
        onPhotosChanged={onPhotosChanged}
      />,
    )

    const file = new File(['x'.repeat(10)], 'photo.jpg', { type: 'image/jpeg' })
    const input = screen.getByLabelText('Photo file') as HTMLInputElement

    // jsdom's <img> never fires onload — stub the dimension probe so upload proceeds.
    const originalImage = global.Image
    class FakeImage {
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      naturalWidth = 1200
      naturalHeight = 1500
      set src(_v: string) {
        this.onload?.()
      }
    }
    // @ts-expect-error stubbing the global Image constructor for the dimension probe
    global.Image = FakeImage

    await userEvent.upload(input, file)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith(
      expect.stringContaining('/marketing/photos'),
      expect.objectContaining({ contentType: 'image/jpeg', width: 1200, height: 1500, label: 'photo' }),
      'tok',
    ))
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(confirmed))
    expect(onPhotosChanged).toHaveBeenCalled()
    expect(await screen.findByText('Long edge under 2160px')).toBeInTheDocument()

    global.Image = originalImage
  })

  it('sends a user-typed Label instead of the file name default', async () => {
    ;(apiPost as Mock).mockImplementation((path: string) => {
      if (path.endsWith('/confirm')) return Promise.resolve(photo({ id: 'photo-3' }))
      return Promise.resolve(photo({ id: 'photo-3', uploadStatus: 'PENDING', url: null, uploadUrl: 'https://storage.example/upload-url' }))
    })

    render(
      <PhotoPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        photos={[]}
        onSelect={vi.fn()}
        onPhotosChanged={vi.fn()}
      />,
    )

    const originalImage = global.Image
    class FakeImage {
      onload: (() => void) | null = null
      onerror: (() => void) | null = null
      naturalWidth = 1200
      naturalHeight = 1500
      set src(_v: string) {
        this.onload?.()
      }
    }
    // @ts-expect-error stubbing the global Image constructor for the dimension probe
    global.Image = FakeImage

    await userEvent.type(screen.getByLabelText('Label'), 'Hero shot')
    const file = new File(['x'.repeat(10)], 'IMG_0042.jpg', { type: 'image/jpeg' })
    await userEvent.upload(screen.getByLabelText('Photo file') as HTMLInputElement, file)

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith(
      expect.stringContaining('/marketing/photos'),
      expect.objectContaining({ label: 'Hero shot' }),
      'tok',
    ))

    global.Image = originalImage
  })

  it('deletes a photo after confirmation and refreshes the library', async () => {
    ;(apiDelete as Mock).mockResolvedValue(undefined)
    const onPhotosChanged = vi.fn()
    render(
      <PhotoPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        photos={[photo()]}
        onSelect={vi.fn()}
        onPhotosChanged={onPhotosChanged}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /Delete photo/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() =>
      expect(apiDelete).toHaveBeenCalledWith(expect.stringContaining('/marketing/photos/photo-1'), 'tok'),
    )
    await waitFor(() => expect(onPhotosChanged).toHaveBeenCalled())
  })

  it('shows the 409 refusal message when a photo is still in use', async () => {
    ;(apiDelete as Mock).mockRejectedValue({ status: 409, detail: 'Creative 12a uses this photo — remove it there first' })
    render(
      <PhotoPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        photos={[photo()]}
        onSelect={vi.fn()}
        onPhotosChanged={vi.fn()}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: /Delete photo/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    expect(await screen.findByText('Could not delete this photo')).toBeInTheDocument()
  })

  it('hides upload and check/block controls for a role without creative.manage (REVIEWER)', async () => {
    mockCan.mockReturnValue(false)
    render(
      <PhotoPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        photos={[photo()]}
        onSelect={vi.fn()}
        onPhotosChanged={vi.fn()}
      />,
    )

    expect(screen.queryByRole('button', { name: /Upload a photo/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Checked' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Block' })).not.toBeInTheDocument()
    // Selecting an existing (unblocked) photo still works — reading the library stays allowed.
    expect(screen.getByTestId('photo-picker-grid').querySelector('button')).not.toBeDisabled()
  })
})
