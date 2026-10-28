import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiGetPage: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (err: unknown, fallback: string) => (err instanceof Error && err.message ? err.message : fallback),
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

const { mountBoardSpy } = vi.hoisted(() => ({
  mountBoardSpy: vi.fn(() => ({ ready: Promise.resolve(), seek: vi.fn(), destroy: vi.fn() })),
}))
vi.mock('@cliangdev/creative-render/mount', () => ({ mountBoard: mountBoardSpy }))

vi.mock('@/components/marketing/shared/usePostsHref', () => ({
  usePostsHref: () => '/app/projects/proj-1/marketing/posts',
}))

Object.assign(navigator, {
  clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
})

import { apiDelete, apiGet, apiGetPage, apiPost } from '@/lib/api'
import { fireEvent, within } from '@testing-library/react'
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
  configured: true,
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
    if (path.includes('/creatives')) return Promise.resolve(creatives)
    return Promise.reject(new Error(`unexpected GET ${path}`))
  })
  // Tiles resolve their media by id, never by listing the library.
  ;(apiGetPage as Mock).mockImplementation(async (path: string) => {
    const ids = new URL(`http://x${path}`).searchParams.get('ids')?.split(',')
    return { data: ids ? media.filter((m) => ids.includes(m.id)) : [], nextCursor: null }
  })
}

const photoRequests = () => (apiGetPage as Mock).mock.calls.map((c) => new URL(`http://x${c[0] as string}`).searchParams)

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

  it('shows the empty-state\'s three ways to make a Creative, with copyable Claude Code and Claude Desktop prompts', async () => {
    mockGets({ creatives: [] })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    await screen.findByText('No Creatives yet')
    expect(screen.getByText('In Claude Code:')).toBeInTheDocument()
    expect(
      screen.getByText(
        "/conductor:marketing create Make a 9:16 TikTok video from our newest photo with the headline 'Dinner, sorted in minutes'",
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('In Claude Desktop (or Claude Code): ask, e.g.')).toBeInTheDocument()
    expect(
      screen.getByText("Make a 9:16 TikTok video from our newest photo with the headline 'Dinner, sorted in minutes'"),
    ).toBeInTheDocument()
    expect(screen.getByText(/upload a finished video as a Clip/i)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Copy Claude Code prompt' }))
    await waitFor(() =>
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
        "/conductor:marketing create Make a 9:16 TikTok video from our newest photo with the headline 'Dinner, sorted in minutes'",
      ),
    )

    await userEvent.click(screen.getByRole('button', { name: 'Copy ask-Claude prompt' }))
    await waitFor(() =>
      expect(navigator.clipboard.writeText).toHaveBeenCalledWith(
        "Make a 9:16 TikTok video from our newest photo with the headline 'Dinner, sorted in minutes'",
      ),
    )
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
    await waitFor(() =>
      expect(thumb.querySelector('img')).toHaveAttribute('src', 'https://storage.example/media-1-poster.jpg'),
    )
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

  describe('CAROUSEL tiles', () => {
    const image = (id: string, url: string) =>
      clipMedia({ id, mediaKind: 'IMAGE', url, posterUrl: null, durationSeconds: null, width: 1080, height: 1350 })
    const slides = (n: number) => Array.from({ length: n }, (_, i) => `img-${i + 1}`)

    it('falls back to slide 1 of the 4x5 list, with an "N slides" badge, when there is no render', async () => {
      mockGets({
        creatives: [creative({ kind: 'CAROUSEL', layout: '', name: null, slides: { '9x16': ['other'], '4x5': slides(7) } })],
        media: [image('img-1', 'https://storage.example/img-1.jpg'), image('other', 'https://storage.example/other.jpg')],
      })

      render(<CreativeLibraryGrid projectId="proj-1" />)

      const thumb = await screen.findByTestId('creative-thumb-cr-1')
      await waitFor(() => expect(thumb.querySelector('img')).toHaveAttribute('src', 'https://storage.example/img-1.jpg'))
      expect(thumb).toHaveTextContent('7 slides')
      expect(screen.getByText('Carousel')).toBeInTheDocument()
    })

    it('prefers the latest render thumbnail, and says "1 slide" / "Image" for a single image', async () => {
      mockGets({
        creatives: [
          creative({
            kind: 'CAROUSEL',
            layout: '',
            name: null,
            slides: { default: ['img-1'] },
            latestRenderThumbnailUrl: 'https://storage.example/render.jpg',
          }),
        ],
      })

      render(<CreativeLibraryGrid projectId="proj-1" />)

      const thumb = await screen.findByTestId('creative-thumb-cr-1')
      expect(thumb.querySelector('img')).toHaveAttribute('src', 'https://storage.example/render.jpg')
      expect(thumb).toHaveTextContent('1 slide')
      expect(thumb).not.toHaveTextContent('1 slides')
      expect(screen.getByText('Image')).toBeInTheDocument()
    })
  })

  it('never lists the library: only the media a tile needs is requested, by id', async () => {
    mockGets({
      creatives: [
        creative({ id: 'c-clip', displayId: '1a', kind: 'CLIP', latestRenderThumbnailUrl: null, clipMedia: { default: 'clip-1' } }),
        creative({ id: 'c-car', displayId: '2a', kind: 'CAROUSEL', layout: '', slides: { '4x5': ['img-1', 'img-2'] }, latestRenderThumbnailUrl: null }),
        creative({ id: 'c-done', displayId: '3a', kind: 'CLIP', latestRenderThumbnailUrl: 'https://storage.example/r.jpg', clipMedia: { default: 'clip-rendered' } }),
      ],
      media: [clipMedia({ id: 'clip-1' })],
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)
    await screen.findByText('1a')
    await waitFor(() => expect(photoRequests()).toHaveLength(1))
    const q = photoRequests()[0]
    expect(q.get('ids')).toBe('clip-1,img-1')
    expect(q.get('mediaKind')).toBeNull()
    expect(q.get('limit')).toBeNull()
  })

  it('makes no media request when every tile already has a render thumbnail', async () => {
    mockGets({ creatives: [creative({ latestRenderThumbnailUrl: 'https://storage.example/t.jpg' })] })

    render(<CreativeLibraryGrid projectId="proj-1" />)
    await screen.findByText('12a')
    expect(apiGetPage).not.toHaveBeenCalled()
  })

  describe('browse, usage and bulk delete', () => {
    const family = () => [
      creative({ id: 'a', number: 3, displayId: '3a', name: 'Zebra', postCount: 0 }),
      creative({ id: 'b', number: 5, displayId: '5a', name: 'Apple', postCount: 4, headline: 'Crisp autumn' }),
      // The server reports the family's distinct Posts on every member.
      creative({ id: 'b2', number: 5, variantLetter: 'b', displayId: '5b', parentCreativeId: 'b', name: 'Apple b', postCount: 4 }),
    ]

    it('shows the family\'s one count (not a sum over variants) and links to the pre-filtered Posts list', async () => {
      mockGets({ creatives: family() })
      render(<CreativeLibraryGrid projectId="proj-1" />)
      const link = await screen.findByTestId('creative-used-b')
      expect(link).toHaveTextContent('Used in 4 posts')
      expect(link).toHaveAttribute('href', '/app/projects/proj-1/marketing/posts?creativeId=b&view=all')
      expect(screen.queryByTestId('creative-used-a')).toBeNull()
    })

    it('keeps the selection checkbox visible on touch and narrow screens, revealed on hover for desktop', async () => {
      mockGets({ creatives: family() })
      render(<CreativeLibraryGrid projectId="proj-1" />)
      const wrapper = (await screen.findByLabelText('Select 3a')).closest('div[class*="absolute"]') as HTMLElement
      expect(wrapper.className).toContain('group-hover:opacity-100')
      expect(wrapper.className).toContain('max-md:opacity-100')
      expect(wrapper.className).toContain('[@media(hover:none)]:opacity-100')
    })

    it('renders variant letters as real links, never a role=link span nested in the tile link', async () => {
      mockGets({ creatives: family() })
      render(<CreativeLibraryGrid projectId="proj-1" />)
      const variant = await screen.findByRole('link', { name: 'Open variant 5b' })
      expect(variant.tagName).toBe('A')
      expect(variant.closest('a:not([aria-label])')).toBeNull()
      expect(variant).toHaveAttribute('href', '/app/projects/proj-1/marketing/creatives/b2')
    })

    it('searches by name, copy and id, and sorts by name and most used', async () => {
      mockGets({ creatives: family() })
      render(<CreativeLibraryGrid projectId="proj-1" />)
      await screen.findByText('3a')

      fireEvent.change(screen.getByLabelText('Search creatives'), { target: { value: 'autumn' } })
      expect(screen.queryByText('3a')).toBeNull()
      expect(screen.getByText('5a')).toBeInTheDocument()
      fireEvent.change(screen.getByLabelText('Search creatives'), { target: { value: '' } })

      fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'name' } })
      const ids = () => screen.getAllByTestId(/^creative-tile-/).map((t) => t.getAttribute('data-testid'))
      expect(ids()).toEqual(['creative-tile-b', 'creative-tile-a'])
      fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'oldest' } })
      expect(ids()).toEqual(['creative-tile-a', 'creative-tile-b'])
      fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'used' } })
      expect(ids()).toEqual(['creative-tile-b', 'creative-tile-a'])
    })

    it('multi-selects with shift range and deletes each family atomically through the server, reporting exactly what stayed and why', async () => {
      mockGets({ creatives: family() })
      ;(apiDelete as Mock).mockImplementation((path: string) =>
        path.endsWith('/b/family')
          ? Promise.reject(new Error('Remove it from the Post first (RE-1 Pasta night)'))
          : Promise.resolve(undefined),
      )
      render(<CreativeLibraryGrid projectId="proj-1" />)
      await screen.findByText('3a')
      // newest first: 5 then 3
      fireEvent.click(screen.getByLabelText('Select 5a'))
      fireEvent.click(screen.getByLabelText('Select 3a'), { shiftKey: true })
      expect(screen.getByText('2 selected')).toBeInTheDocument()

      fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
      fireEvent.click(await screen.findByRole('button', { name: 'Delete 2' }))

      expect(await screen.findByText('1 deleted (3a), 1 kept.')).toBeInTheDocument()
      // One call per family, never per member, so a family can never be left half deleted.
      const paths = (apiDelete as Mock).mock.calls.map((c) => c[0] as string)
      expect(paths).toHaveLength(2)
      expect(paths.every((p) => p.endsWith('/family'))).toBe(true)
      // The refusal names the Post that blocked it.
      expect(screen.getByText(/5a kept: Remove it from the Post first \(RE-1 Pasta night\)/)).toBeInTheDocument()
    })

    it('asks the server for post counts, and deletes a whole family even when a filter hides its variants', async () => {
      const all = [
        creative({ id: 'r', number: 8, displayId: '8a', name: 'Root', state: 'READY' }),
        creative({ id: 'v', number: 8, variantLetter: 'b', displayId: '8b', parentCreativeId: 'r', state: 'DRAFT' }),
      ]
      ;(apiGet as Mock).mockImplementation((path: string) => {
        if (path.includes('/brand-kits')) return Promise.resolve([KIT])
        if (path.includes('state=READY')) return Promise.resolve([all[0]]) // the variant is filtered out
        if (path.includes('/creatives')) return Promise.resolve(all)
        return Promise.reject(new Error(`unexpected GET ${path}`))
      })
      ;(apiGetPage as Mock).mockResolvedValue({ data: [], nextCursor: null })
      ;(apiDelete as Mock).mockResolvedValue(undefined)
      render(<CreativeLibraryGrid projectId="proj-1" />)
      await screen.findByText('8a')
      expect((apiGet as Mock).mock.calls.some((c) => String(c[0]).includes('includePostCount=true'))).toBe(true)
      fireEvent.change(screen.getByLabelText('State'), { target: { value: 'READY' } })
      await waitFor(() => expect(screen.queryByText('8b')).toBeNull())
      fireEvent.click(screen.getByLabelText('Select 8a'))
      fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
      fireEvent.click(await screen.findByRole('button', { name: 'Delete 1' }))

      await waitFor(() => expect(apiDelete).toHaveBeenCalledTimes(1))
      expect((apiDelete as Mock).mock.calls[0]![0]).toMatch(/\/creatives\/r\/family$/)
    })

    describe('families under a state filter', () => {
      const draftRootReadyVariant = () => [
        creative({ id: 'r', number: 8, displayId: '8a', name: 'Root', state: 'DRAFT' }),
        creative({ id: 'v', number: 8, variantLetter: 'b', displayId: '8b', parentCreativeId: 'r', name: 'Variant', state: 'READY' }),
        creative({ id: 'o', number: 9, displayId: '9a', name: 'Other', state: 'DRAFT' }),
      ]

      it('never presents a variant as the root: the tile is the real root, found because a variant matches, and the filter is applied here', async () => {
        mockGets({ creatives: draftRootReadyVariant() })
        render(<CreativeLibraryGrid projectId="proj-1" />)
        await screen.findByText('8a')
        fireEvent.change(screen.getByLabelText('State'), { target: { value: 'READY' } })

        // The family stays (its variant is READY), shown by its root; the all-DRAFT family goes.
        await waitFor(() => expect(screen.queryByTestId('creative-tile-o')).toBeNull())
        expect(screen.getByTestId('creative-tile-r')).toBeInTheDocument()
        expect(screen.queryByTestId('creative-tile-v')).toBeNull()
        expect(screen.getByRole('link', { name: 'Open variant 8b' })).toHaveAttribute(
          'href',
          '/app/projects/proj-1/marketing/creatives/v',
        )
        // The list is always requested whole (no state/kit narrowing on the wire).
        expect((apiGet as Mock).mock.calls.every((c) => !String(c[0]).includes('state='))).toBe(true)
      })

      it('lists every creative that will be deleted, flags the ones the filter hides, and deletes by the root id', async () => {
        mockGets({ creatives: draftRootReadyVariant() })
        ;(apiDelete as Mock).mockResolvedValue(undefined)
        render(<CreativeLibraryGrid projectId="proj-1" />)
        await screen.findByText('8a')
        fireEvent.change(screen.getByLabelText('State'), { target: { value: 'READY' } })
        await waitFor(() => expect(screen.queryByTestId('creative-tile-o')).toBeNull())

        fireEvent.click(screen.getByLabelText('Select 8a'))
        fireEvent.click(screen.getByRole('button', { name: 'Delete' }))

        const list = within(await screen.findByTestId('delete-creatives'))
        expect(list.getByText('8a')).toBeInTheDocument()
        expect(list.getByText('8b')).toBeInTheDocument()
        // 8a is DRAFT, so the READY filter hides it, yet it is deleted with its family and is named.
        expect(within(screen.getByTestId('delete-creative-r')).getByText('hidden by the current filter')).toBeInTheDocument()
        expect(within(screen.getByTestId('delete-creative-v')).queryByText('hidden by the current filter')).toBeNull()
        expect(screen.getByText(/Delete 1 family \(2 creatives\)\?/)).toBeInTheDocument()

        fireEvent.click(screen.getByRole('button', { name: 'Delete 1' }))
        await waitFor(() => expect(apiDelete).toHaveBeenCalledTimes(1))
        expect((apiDelete as Mock).mock.calls[0]![0]).toMatch(/\/creatives\/r\/family$/)
      })
    })
  })
})
