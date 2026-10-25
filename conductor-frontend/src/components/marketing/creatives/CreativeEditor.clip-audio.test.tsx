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

const CLIP_LOUD = media({ id: 'clip-loud', mediaKind: 'VIDEO', hasAudio: true, durationSeconds: 12 })
const CLIP_SILENT = media({ id: 'clip-silent', mediaKind: 'VIDEO', hasAudio: false, durationSeconds: 12 })
const TRACK = media({ id: 'trk-1', mediaKind: 'AUDIO', durationSeconds: 95, label: 'Sunrise', licence: 'CC-BY 4.0' })

function optionFor(source: HTMLSelectElement, value: string): HTMLOptionElement {
  return Array.from(source.options).find((o) => o.value === value) as HTMLOptionElement
}

describe('CreativeEditor — sound on a CLIP', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
    for (const key of Object.keys(mockHandles)) delete mockHandles[key]
  })

  it('offers clip sound, replace, mix and mute, defaulting to the clip\'s own sound', async () => {
    mockGetsFor(creative({ kind: 'CLIP', clipMedia: { default: 'clip-loud' } }), [KIT], [CLIP_LOUD, TRACK])
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    const source = within(panel).getByLabelText('Source') as HTMLSelectElement
    expect(Array.from(source.options).map((o) => o.value)).toEqual(['clip', 'track', 'mix', 'none'])
    expect(Array.from(source.options).map((o) => o.textContent)).toEqual([
      'Clip sound',
      'Replace with a track',
      'Track under clip sound',
      'Mute',
    ])
    expect(source).toHaveValue('clip')
    expect(within(panel).queryByLabelText('Volume')).not.toBeInTheDocument()
    expect(screen.queryByTestId('slideshow-settings')).not.toBeInTheDocument()
  })

  it('disables the mix option when the clip has no audio', async () => {
    mockGetsFor(creative({ kind: 'CLIP', clipMedia: { default: 'clip-silent' } }), [KIT], [CLIP_SILENT, TRACK])
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    const source = within(panel).getByLabelText('Source') as HTMLSelectElement
    await waitFor(() => expect(optionFor(source, 'mix').disabled).toBe(true))
    expect(optionFor(source, 'track').disabled).toBe(false)
  })

  it('enables the mix option when the clip has audio', async () => {
    mockGetsFor(creative({ kind: 'CLIP', clipMedia: { default: 'clip-loud' } }), [KIT], [CLIP_LOUD, TRACK])
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    const source = within(panel).getByLabelText('Source') as HTMLSelectElement
    await waitFor(() => expect(optionFor(source, 'mix').disabled).toBe(false))
  })

  it('saves a mix with the track and the 0.3 default volume', async () => {
    mockGetsFor(
      creative({ kind: 'CLIP', clipMedia: { default: 'clip-loud' }, audio: { source: 'mix', trackId: 'trk-1', volume: 0.3, fadeOutSec: 1 } }),
      [KIT],
      [CLIP_LOUD, TRACK],
    )
    ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'CLIP', version: 4 }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    expect(await within(panel).findByText('Sunrise')).toBeInTheDocument()
    expect(within(panel).getByTestId('audio-track-audition')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    const body = (apiPatch as Mock).mock.calls[0][1]
    expect(body.audio).toEqual({ source: 'mix', trackId: 'trk-1', volume: 0.3, fadeOutSec: 1 })
    expect(body.slideshow).toBeUndefined()
  })

  it('switching to Replace uses volume 1 and sends the track; Mute sends no trackId', async () => {
    mockGetsFor(creative({ kind: 'CLIP', clipMedia: { default: 'clip-loud' } }), [KIT], [CLIP_LOUD, TRACK])
    ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'CLIP', version: 4 }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const panel = await screen.findByTestId('audio-panel')
    await userEvent.selectOptions(within(panel).getByLabelText('Source'), 'track')
    expect(within(panel).getByText('100%')).toBeInTheDocument()
    await userEvent.selectOptions(within(panel).getByLabelText('Source'), 'none')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    expect((apiPatch as Mock).mock.calls[0][1].audio).toEqual({ source: 'none' })
  })

  it('leaves a clip on its own sound as the default source', async () => {
    mockGetsFor(creative({ kind: 'CLIP', clipMedia: { default: 'clip-loud' } }), [KIT], [CLIP_LOUD])
    ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'CLIP', version: 4 }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    await screen.findByTestId('audio-panel')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    expect((apiPatch as Mock).mock.calls[0][1].audio).toEqual({ source: 'clip' })
  })

  it('keeps the clip\'s own sound when switching a no-music Still to Clip', async () => {
    mockGetsFor(creative({ kind: 'STILL' }), [KIT], [CLIP_LOUD])
    ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'CLIP', version: 4 }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    await userEvent.selectOptions(await screen.findByLabelText('Kind'), 'CLIP')
    const panel = await screen.findByTestId('audio-panel')
    expect(within(panel).getByLabelText('Source')).toHaveValue('clip')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(apiPatch).toHaveBeenCalled())
    const body = (apiPatch as Mock).mock.calls[0][1]
    expect(body.kind).toBe('CLIP')
    expect(body.audio).toEqual({ source: 'clip' })
  })
})
