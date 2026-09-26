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

import { apiPost } from '@/lib/api'
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
      expect.objectContaining({ contentType: 'image/jpeg', width: 1200, height: 1500 }),
      'tok',
    ))
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(confirmed))
    expect(onPhotosChanged).toHaveBeenCalled()
    expect(await screen.findByText('Long edge under 2160px')).toBeInTheDocument()

    global.Image = originalImage
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
