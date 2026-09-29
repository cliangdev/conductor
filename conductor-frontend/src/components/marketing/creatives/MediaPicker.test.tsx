import { describe, it, expect, vi, beforeEach, afterEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// The signed-URL PUT is an XMLHttpRequest jsdom cannot complete — same pattern as PhotoPicker.test.tsx.
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
import { MediaPicker } from './MediaPicker'
import type { CreativePhoto } from './types'

function media(overrides: Partial<CreativePhoto> = {}): CreativePhoto {
  return {
    id: 'media-1',
    projectId: 'proj-1',
    label: 'clip.mp4',
    contentType: 'video/mp4',
    sizeBytes: 12345,
    width: 1080,
    height: 1920,
    mediaKind: 'VIDEO',
    durationSeconds: 12.5,
    hasAudio: true,
    posterUrl: 'https://storage.example/poster.jpg',
    aiGenerated: false,
    checked: true,
    blocked: false,
    focal: {},
    uploadStatus: 'UPLOADED',
    url: 'https://storage.example/clip.mp4',
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

/** A controllable stand-in for a real <video>/<audio> element: jsdom cannot load or seek real media,
 *  so setting `.src` synchronously fires `onloadedmetadata`, and setting `.currentTime` synchronously
 *  fires `onseeked` — mirroring the browser events measureVideoFile/captureVideoPoster wait for. */
function makeFakeMediaElement(props: Record<string, unknown>) {
  const el: Record<string, unknown> = {
    onloadedmetadata: null,
    onerror: null,
    onseeked: null,
    preload: '',
    muted: false,
    playsInline: false,
    _currentTime: 0,
    ...props,
  }
  Object.defineProperty(el, 'currentTime', {
    get() {
      return el._currentTime
    },
    set(v: number) {
      el._currentTime = v
      ;(el.onseeked as (() => void) | null)?.()
    },
  })
  Object.defineProperty(el, 'src', {
    set() {
      ;(el.onloadedmetadata as (() => void) | null)?.()
    },
  })
  return el
}

describe('MediaPicker', () => {
  let originalCreateElement: typeof document.createElement

  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
    originalCreateElement = document.createElement.bind(document)
    // jsdom has no real canvas 2D backend — stub just enough of the API for captureVideoPoster to
    // produce a Blob, so the poster mint -> PUT -> confirm chain can be asserted end to end.
    HTMLCanvasElement.prototype.getContext = vi.fn(() => ({ drawImage: vi.fn() })) as unknown as typeof HTMLCanvasElement.prototype.getContext
    HTMLCanvasElement.prototype.toBlob = function (callback: BlobCallback) {
      callback(new Blob(['poster'], { type: 'image/jpeg' }))
    }
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.createElement = originalCreateElement
  })

  it('shows only the active tab\'s media, and switches on click', async () => {
    const items = [
      media({ id: 'v-1', mediaKind: 'VIDEO', label: 'a clip' }),
      media({ id: 'p-1', mediaKind: 'IMAGE', label: 'a photo', url: 'https://storage.example/photo.jpg' }),
      media({ id: 'a-1', mediaKind: 'AUDIO', label: 'a track', durationSeconds: 30 }),
    ]
    render(
      <MediaPicker projectId="proj-1" token="tok" open onOpenChange={() => {}} media={items} onSelect={vi.fn()} onMediaChanged={vi.fn()} />,
    )

    // Photos is the default tab — one tile in the grid, the rest filtered out.
    expect(screen.getByTestId('photo-picker-grid').children).toHaveLength(1)

    await userEvent.click(screen.getByRole('tab', { name: 'Videos' }))
    expect(screen.getByTestId('video-picker-grid')).toBeInTheDocument()
    expect(screen.getByTestId('video-picker-grid').children).toHaveLength(1)

    await userEvent.click(screen.getByRole('tab', { name: 'Audio' }))
    expect(screen.getByTestId('audio-picker-grid')).toBeInTheDocument()
    expect(screen.getByTestId('audio-picker-grid').children).toHaveLength(1)
  })

  it('locks to one kind and hides the tab bar when `kind` is given', async () => {
    render(
      <MediaPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        media={[media({ mediaKind: 'VIDEO' })]}
        onSelect={vi.fn()}
        onMediaChanged={vi.fn()}
        kind="VIDEO"
      />,
    )
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.getByTestId('video-picker-grid')).toBeInTheDocument()
  })

  it('uploads a video through mint -> PUT -> confirm -> poster mint -> PUT -> confirm, reading duration/dimensions/hasAudio client-side', async () => {
    const pending = media({ id: 'v-2', uploadStatus: 'PENDING', url: null, posterUrl: null, uploadUrl: 'https://storage.example/upload-video' })
    const confirmedNoPoster = media({ id: 'v-2', posterUrl: null })
    const confirmedWithPoster = media({ id: 'v-2', posterUrl: 'https://storage.example/v-2-poster.jpg' })
    const posterUpload = { uploadUrl: 'https://storage.example/upload-poster', gcsPath: 'gcs://poster/v-2.jpg' }

    ;(apiPost as Mock).mockImplementation((path: string, body?: unknown) => {
      if (path.endsWith('/poster/confirm')) return Promise.resolve(confirmedWithPoster)
      if (path.endsWith('/poster')) return Promise.resolve(posterUpload)
      if (path.endsWith('/confirm')) return Promise.resolve(confirmedNoPoster)
      expect(body).toMatchObject({ contentType: 'video/mp4', mediaKind: 'VIDEO', width: 1080, height: 1920, durationSeconds: 12.5, hasAudio: true })
      return Promise.resolve(pending)
    })

    document.createElement = ((tag: string) => {
      if (tag === 'video') {
        return makeFakeMediaElement({ videoWidth: 1080, videoHeight: 1920, duration: 12.5, audioTracks: { length: 1 } }) as unknown as HTMLVideoElement
      }
      return originalCreateElement(tag)
    }) as typeof document.createElement

    const onSelect = vi.fn()
    const onMediaChanged = vi.fn()
    render(
      <MediaPicker
        projectId="proj-1"
        token="tok"
        open
        onOpenChange={() => {}}
        media={[]}
        onSelect={onSelect}
        onMediaChanged={onMediaChanged}
        kind="VIDEO"
      />,
    )

    const file = new File(['x'.repeat(10)], 'clip.mp4', { type: 'video/mp4' })
    const input = screen.getByLabelText('Video file') as HTMLInputElement
    await userEvent.upload(input, file)

    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(confirmedWithPoster))
    expect(apiPost).toHaveBeenCalledWith(expect.stringContaining('/marketing/photos/v-2/poster'), {}, 'tok')
    expect(apiPost).toHaveBeenCalledWith(
      expect.stringContaining('/marketing/photos/v-2/poster/confirm'),
      { gcsPath: 'gcs://poster/v-2.jpg' },
      'tok',
    )
    expect(onMediaChanged).toHaveBeenCalled()
  })

  it('uploads audio through mint -> PUT -> confirm, reading duration client-side', async () => {
    const pending = media({
      id: 'a-2',
      mediaKind: 'AUDIO',
      uploadStatus: 'PENDING',
      url: null,
      posterUrl: null,
      uploadUrl: 'https://storage.example/upload-audio',
      width: null,
      height: null,
    })
    const confirmed = media({ id: 'a-2', mediaKind: 'AUDIO', durationSeconds: 45, width: null, height: null, posterUrl: null })

    ;(apiPost as Mock).mockImplementation((path: string) => {
      if (path.endsWith('/confirm')) return Promise.resolve(confirmed)
      return Promise.resolve(pending)
    })

    document.createElement = ((tag: string) => {
      if (tag === 'audio') return makeFakeMediaElement({ duration: 45 }) as unknown as HTMLAudioElement
      return originalCreateElement(tag)
    }) as typeof document.createElement

    const onSelect = vi.fn()
    render(
      <MediaPicker projectId="proj-1" token="tok" open onOpenChange={() => {}} media={[]} onSelect={onSelect} onMediaChanged={vi.fn()} kind="AUDIO" />,
    )

    const file = new File(['x'.repeat(10)], 'track.mp3', { type: 'audio/mpeg' })
    await userEvent.upload(screen.getByLabelText('Audio file') as HTMLInputElement, file)

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        expect.stringContaining('/marketing/photos'),
        expect.objectContaining({ contentType: 'audio/mpeg', mediaKind: 'AUDIO', durationSeconds: 45 }),
        'tok',
      ),
    )
    await waitFor(() => expect(onSelect).toHaveBeenCalledWith(confirmed))
    // No poster calls for audio.
    expect(apiPost).not.toHaveBeenCalledWith(expect.stringContaining('/poster'), expect.anything(), expect.anything())
  })

  it('rejects an oversized video client-side with a clear message, before any API call', async () => {
    render(
      <MediaPicker projectId="proj-1" token="tok" open onOpenChange={() => {}} media={[]} onSelect={vi.fn()} onMediaChanged={vi.fn()} kind="VIDEO" />,
    )
    const big = new File(['x'], 'huge.mp4', { type: 'video/mp4' })
    Object.defineProperty(big, 'size', { value: 1024 * 1024 * 1024 + 1 })
    await userEvent.upload(screen.getByLabelText('Video file') as HTMLInputElement, big)

    expect(await screen.findByText(/Videos must be under 1 GB/)).toBeInTheDocument()
    expect(apiPost).not.toHaveBeenCalled()
  })
})
