import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'user-1' }, accessToken: 'tok', loading: false }),
}))

const { pushSpy, mockCan } = vi.hoisted(() => ({ pushSpy: vi.fn(), mockCan: vi.fn((_cap?: string) => true) }))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: pushSpy }) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => mockCan(cap),
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: mockCan, refresh: vi.fn() }),
}))

import { apiGet, apiPost } from '@/lib/api'
import { CreativeLibraryGrid } from './CreativeLibraryGrid'
import type { Creative, CreativePhoto } from './types'
import type { BrandKit } from '@/components/marketing/brand/types'

const KIT: BrandKit = {
  id: 'kit-1',
  projectId: 'proj-1',
  slug: 'default',
  name: 'Default',
  isDefault: true,
  tokens: {},
  fontFamily: null,
  fontUrl: null,
  markUrl: null,
  wordmarkDarkUrl: null,
  wordmarkLightUrl: null,
  badgeUrl: null,
  ctaClaim: null,
  accentPhraseRequired: false,
  copyRules: [],
  approvedLines: [],
  enabledPlacements: ['4x5'],
  knowledgePagePath: 'marketing/brand.md',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

function creative(overrides: Partial<Creative> = {}): Creative {
  return {
    id: 'cr-1',
    projectId: 'proj-1',
    brandKitId: 'kit-1',
    number: 12,
    variantLetter: 'a',
    displayId: '12a',
    parentCreativeId: null,
    name: 'Paste a link',
    state: 'READY',
    kind: 'STILL',
    layout: 'stacked',
    theme: 'dark',
    photoId: null,
    photoUrl: null,
    focalOverride: null,
    headline: 'Every day, finally organized.',
    body: 'Body',
    caption: 'Caption',
    altText: 'Alt',
    placements: [],
    sequenceKind: null,
    sequence: [],
    carouselRatio: null,
    typeOverrides: {},
    lockup: 'plain',
    version: 1,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function clipMedia(overrides: Partial<CreativePhoto> = {}): CreativePhoto {
  return {
    id: 'media-1',
    projectId: 'proj-1',
    contentType: 'video/mp4',
    sizeBytes: 1000,
    width: 1080,
    height: 1920,
    mediaKind: 'VIDEO',
    durationSeconds: 12.5,
    posterUrl: 'https://storage.example/media-1-poster.jpg',
    aiGenerated: false,
    checked: true,
    blocked: false,
    focal: {},
    uploadStatus: 'UPLOADED',
    url: 'https://storage.example/media-1.mp4',
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

/** Wires apiGet for the routes CreativeLibraryGrid always calls (brand kits, creatives, and — for
 *  CLIP tiles — the media library), so each test only spells out what's specific to it. */
function mockGets({
  creatives = [],
  kits = [KIT],
  media = [],
}: {
  creatives?: Creative[]
  kits?: BrandKit[]
  media?: CreativePhoto[]
}) {
  ;(apiGet as Mock).mockImplementation((path: string) => {
    if (path.includes('/brand-kits')) return Promise.resolve(kits)
    if (path.includes('/marketing/photos')) return Promise.resolve(media)
    if (path.includes('/creatives')) return Promise.resolve(creatives)
    return Promise.reject(new Error(`unexpected GET ${path}`))
  })
}

describe('CreativeLibraryGrid', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
  })

  it('lists Creatives grouped by family with variant pills', async () => {
    mockGets({
      creatives: [
        creative(),
        creative({ id: 'cr-1b', variantLetter: 'b', displayId: '12b', parentCreativeId: 'cr-1' }),
      ],
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    expect(await screen.findByText('12a')).toBeInTheDocument()
    expect(screen.getByText('b')).toBeInTheDocument()
  })

  it('creates a new Creative and navigates to its editor', async () => {
    mockGets({ creatives: [] })
    ;(apiPost as Mock).mockResolvedValue(creative({ id: 'cr-new' }))

    render(<CreativeLibraryGrid projectId="proj-1" />)
    await screen.findByText('New creative')

    await userEvent.click(screen.getAllByRole('button', { name: 'New creative' })[0])

    await waitFor(() =>
      expect(pushSpy).toHaveBeenCalledWith('/app/projects/proj-1/marketing/creatives/cr-new'),
    )
  })

  it('uses latestRenderThumbnailUrl as the tile thumbnail when present, instead of the live mount', async () => {
    mockGets({
      creatives: [creative({ latestRenderId: 'render-1', latestRenderThumbnailUrl: 'https://storage.example/12a-4x5.png' })],
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    const thumb = await screen.findByTestId('creative-thumb-cr-1')
    const img = thumb.querySelector('img')
    expect(img).toHaveAttribute('src', 'https://storage.example/12a-4x5.png')
  })

  it('falls back to the live 4:5 mount when there is no render thumbnail yet', async () => {
    mockGets({ creatives: [creative()] })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    const thumb = await screen.findByTestId('creative-thumb-cr-1')
    expect(thumb.querySelector('img')).not.toBeInTheDocument()
  })

  it('hides "New creative" for a role without creative.manage (REVIEWER)', async () => {
    mockCan.mockReturnValue(false)
    mockGets({ creatives: [creative()] })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    expect(await screen.findByText('12a')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New creative' })).not.toBeInTheDocument()
  })

  it('shows a CLIP creative\'s poster with a play badge and duration, from its default clip media', async () => {
    mockGets({
      creatives: [creative({ kind: 'CLIP', layout: '', clipMedia: { default: 'media-1' } })],
      media: [clipMedia()],
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    const thumb = await screen.findByTestId('creative-thumb-cr-1')
    expect(thumb.querySelector('img')).toHaveAttribute('src', 'https://storage.example/media-1-poster.jpg')
    expect(thumb).toHaveTextContent('13s')
  })

  it('prefers the latest render\'s thumbnail over the default clip media poster for a CLIP creative', async () => {
    mockGets({
      creatives: [
        creative({
          kind: 'CLIP',
          layout: '',
          clipMedia: { default: 'media-1' },
          latestRenderId: 'render-1',
          latestRenderThumbnailUrl: 'https://storage.example/render-poster.jpg',
        }),
      ],
      media: [clipMedia()],
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    const thumb = await screen.findByTestId('creative-thumb-cr-1')
    expect(thumb.querySelector('img')).toHaveAttribute('src', 'https://storage.example/render-poster.jpg')
  })

  it('shows a MOTION creative\'s live board with a play badge and its motion.durationSec, when there is no render yet', async () => {
    mockGets({
      creatives: [
        creative({
          kind: 'MOTION',
          motion: { preset: 'fade-up', durationSec: 15, background: { source: 'photo', motion: 'zoom-in' }, endCard: true },
        }),
      ],
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    const thumb = await screen.findByTestId('creative-thumb-cr-1')
    expect(thumb).toHaveTextContent('15s')
    // No render yet — falls back to the live board (no rendered poster <img>).
    expect(thumb.querySelector('img')).not.toBeInTheDocument()
  })

  it('prefers the rendered poster over the live board for a MOTION creative once one exists', async () => {
    mockGets({
      creatives: [
        creative({
          kind: 'MOTION',
          motion: { durationSec: 9 },
          latestRenderId: 'render-1',
          latestRenderThumbnailUrl: 'https://storage.example/12a-motion-poster.jpg',
        }),
      ],
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    const thumb = await screen.findByTestId('creative-thumb-cr-1')
    expect(thumb.querySelector('img')).toHaveAttribute('src', 'https://storage.example/12a-motion-poster.jpg')
    expect(thumb).toHaveTextContent('9s')
  })
})
