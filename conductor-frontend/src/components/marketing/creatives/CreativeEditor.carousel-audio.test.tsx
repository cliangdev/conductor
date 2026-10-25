import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// COND-24 PR2 (MOTION creatives, web): unlike CreativeEditor.test.tsx (which mounts the real
// @cliangdev/creative-render engine against jsdom for STILL creatives), these tests mock the render
// package's `mountBoard` handle outright — see docs/mcp-tool-guidelines.md-adjacent reasoning in the
// motion contract: play()/pause()/seek() drive a real requestAnimationFrame loop and real <video>
// seeking, neither of which jsdom implements meaningfully, so asserting "the shared transport calls
// the right handle methods" is both simpler and more robust as a mock-based test than trying to
// observe real motion pixels the way the STILL tests observe real DOM/CSS output.

const { mockMountBoard, mockHandles, mockAttachFocalDrag } = vi.hoisted(() => {
  const mockHandles: Record<
    string,
    {
      ready: Promise<void>
      board: null
      shell: HTMLElement
      update: Mock
      destroy: Mock
      play: Mock
      pause: Mock
      seek: Mock
      onTime: Mock
    }
  > = {}
  const mockAttachFocalDrag = vi.fn(() => ({ detach: vi.fn() }))
  const mockMountBoard = vi.fn((_container: HTMLElement, options: { placementKey: string }) => {
    const handle = {
      ready: Promise.resolve(),
      board: null,
      shell: document.createElement('div'),
      update: vi.fn(() => Promise.resolve()),
      destroy: vi.fn(),
      play: vi.fn(),
      pause: vi.fn(),
      seek: vi.fn(() => Promise.resolve()),
      onTime: vi.fn(() => () => {}),
    }
    mockHandles[options.placementKey] = handle
    return handle
  })
  return { mockMountBoard, mockHandles, mockAttachFocalDrag }
})

vi.mock('@cliangdev/creative-render/mount', () => ({
  mountBoard: mockMountBoard,
  attachFocalDrag: mockAttachFocalDrag,
  enabledPlacements: () => ['4x5', '9x16'],
}))
vi.mock('@cliangdev/creative-render/copy-rules', () => ({ checkCreativeCopy: () => [] }))

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

const { pushSpy, toastSpy, mockCan } = vi.hoisted(() => ({
  pushSpy: vi.fn(),
  toastSpy: vi.fn(),
  mockCan: vi.fn((_cap?: string) => true),
}))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: pushSpy }) }))
vi.mock('@/components/ui/toast', () => ({ useToast: () => ({ showToast: toastSpy }) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => mockCan(cap),
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: mockCan, refresh: vi.fn() }),
}))

import { apiGet, apiPatch } from '@/lib/api'
import { CreativeEditor } from './CreativeEditor'
import type { Creative } from './types'
import type { BrandKit } from '@/components/marketing/brand/types'

const KIT: BrandKit = {
  id: 'kit-1',
  projectId: 'proj-1',
  slug: 'default',
  name: 'Default',
  isDefault: true,
  tokens: { accent: '#3366FF' },
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
  enabledPlacements: ['4x5', '9x16'],
  knowledgePagePath: 'marketing/brand.md',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const REGISTRY = {
  layouts: { stacked: { themes: ['dark', 'light'] } },
  placements: [
    { key: '4x5', label: '4:5 Instagram feed', platform: 'instagram', width: 1080, height: 1350, default: true },
    { key: '9x16', label: '9:16 TikTok and Reels', platform: 'tiktok', width: 1080, height: 1920, default: true },
  ],
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
    name: 'Motion ad',
    state: 'DRAFT',
    kind: 'STILL',
    layout: 'stacked',
    theme: 'dark',
    photoId: null,
    photoUrl: null,
    focalOverride: null,
    headline: 'Headline',
    body: 'Body',
    caption: 'Caption',
    altText: 'Alt text',
    placements: [],
    sequenceKind: null,
    sequence: [],
    carouselRatio: null,
    typeOverrides: {},
    lockup: 'plain',
    layoutOverrides: null,
    motion: null,
    audio: null,
    version: 3,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

interface TestMedia {
  id: string
  label: string | null
  url: string
  mediaKind: 'IMAGE' | 'VIDEO' | 'AUDIO'
  hasAudio?: boolean | null
  durationSeconds?: number | null
  licence?: string | null
}

function media(overrides: Partial<TestMedia> & { id: string; mediaKind: TestMedia['mediaKind'] }): TestMedia & Record<string, unknown> {
  return {
    label: overrides.id,
    url: `https://storage.example/${overrides.id}`,
    projectId: 'proj-1',
    contentType: overrides.mediaKind === 'VIDEO' ? 'video/mp4' : overrides.mediaKind === 'AUDIO' ? 'audio/mpeg' : 'image/jpeg',
    sizeBytes: 1,
    width: overrides.mediaKind === 'IMAGE' || overrides.mediaKind === 'VIDEO' ? 100 : undefined,
    height: overrides.mediaKind === 'IMAGE' || overrides.mediaKind === 'VIDEO' ? 100 : undefined,
    aiGenerated: false,
    checked: true,
    blocked: false,
    focal: {},
    uploadStatus: 'UPLOADED',
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

function mockGetsFor(activeCreative: Creative, kits: BrandKit[] = [KIT], mediaRows: ReturnType<typeof media>[] = []) {
  ;(apiGet as Mock).mockImplementation((path: string) => {
    if (path.includes('/creative-registry')) return Promise.resolve(REGISTRY)
    if (path.includes('/brand-kits')) return Promise.resolve(kits)
    if (path.includes('/readiness')) return Promise.resolve({ ready: false, items: [] })
    const singleMedia = path.match(/\/marketing\/photos\/([^/?]+)$/)
    if (singleMedia) {
      const found = mediaRows.find((m) => m.id === singleMedia[1])
      return found ? Promise.resolve(found) : Promise.reject(new Error(`no such media ${singleMedia[1]}`))
    }
    if (path.match(/\/marketing\/photos(\?|$)/)) return Promise.resolve(mediaRows)
    if (path.endsWith(`/creatives/${activeCreative.id}`)) return Promise.resolve(activeCreative)
    return Promise.reject(new Error(`unexpected GET ${path}`))
  })
}

const SLIDES = { '9x16': ['img-1', 'img-2', 'img-3'], default: ['img-1', 'img-2', 'img-3'] }
const TRACK = media({ id: 'trk-1', mediaKind: 'AUDIO', durationSeconds: 95, label: 'Sunrise', licence: 'CC-BY 4.0' })
const IMAGES = ['img-1', 'img-2', 'img-3'].map((id) => media({ id, mediaKind: 'IMAGE' }))

describe('CreativeEditor — music on a CAROUSEL', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
    for (const key of Object.keys(mockHandles)) delete mockHandles[key]
  })

  it('shows the audio panel with only "No music" and "Music track"', async () => {
    mockGetsFor(creative({ kind: 'CAROUSEL', slides: SLIDES }), [KIT], [...IMAGES, TRACK])
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    const source = within(panel).getByLabelText('Source') as HTMLSelectElement
    expect(Array.from(source.options).map((o) => o.value)).toEqual(['none', 'track'])
    expect(source).toHaveValue('none')
    expect(screen.queryByTestId('slideshow-settings')).not.toBeInTheDocument()
  })

  it('shows the track row (label, duration, licence, audition) and slideshow settings once a track is chosen', async () => {
    mockGetsFor(
      creative({ kind: 'CAROUSEL', slides: SLIDES, audio: { source: 'track', trackId: 'trk-1', volume: 1, fadeOutSec: 1 } }),
      [KIT],
      [...IMAGES, TRACK],
    )
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    expect(await within(panel).findByText('Sunrise')).toBeInTheDocument()
    expect(within(panel).getByTestId('audio-track-meta')).toHaveTextContent('Licence: CC-BY 4.0')
    expect(within(panel).getByTestId('audio-track-meta')).toHaveTextContent('1m 35s')
    expect(within(panel).getByTestId('audio-track-audition')).toBeInTheDocument()

    const settings = within(panel).getByTestId('slideshow-settings')
    expect(within(settings).getByRole('switch')).toBeChecked()
    expect(within(settings).getByLabelText('Slide 1 seconds')).toHaveAttribute('placeholder', '3')
    expect(within(settings).getByLabelText('Slide 2 seconds')).toHaveAttribute('placeholder', '2.5')
    expect(within(settings).getAllByRole('spinbutton')).toHaveLength(3)
  })

  it('saves audio and slideshow for a track, with custom durations', async () => {
    mockGetsFor(
      creative({ kind: 'CAROUSEL', slides: SLIDES, audio: { source: 'track', trackId: 'trk-1', volume: 1, fadeOutSec: 1 } }),
      [KIT],
      [...IMAGES, TRACK],
    )
    ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'CAROUSEL', slides: SLIDES, version: 4 }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    await userEvent.click(within(panel).getByRole('switch'))
    await userEvent.type(within(panel).getByLabelText('Slide 2 seconds'), '4')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    const body = (apiPatch as Mock).mock.calls[0][1]
    expect(body.audio).toEqual({ source: 'track', trackId: 'trk-1', volume: 1, fadeOutSec: 1 })
    expect(body.slideshow).toEqual({ zoom: false, slideDurations: [3, 4, 2.5] })
  })

  it('saves "No music" without a trackId and without per-slide durations', async () => {
    mockGetsFor(creative({ kind: 'CAROUSEL', slides: SLIDES }), [KIT], IMAGES)
    ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'CAROUSEL', slides: SLIDES, version: 4 }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    await screen.findByTestId('audio-panel')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    const body = (apiPatch as Mock).mock.calls[0][1]
    expect(body.audio).toEqual({ source: 'none' })
    expect(body.slideshow).toEqual({ zoom: true })
  })
})
