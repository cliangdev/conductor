import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
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

describe('CreativeEditor — Motion (COND-24 PR2)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
    for (const key of Object.keys(mockHandles)) delete mockHandles[key]
  })

  it('switching Kind to Motion shows the Motion and Audio panels with contract defaults', async () => {
    mockGetsFor(creative({ kind: 'STILL' }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    await screen.findByLabelText('Headline')
    expect(screen.queryByTestId('motion-panel')).not.toBeInTheDocument()

    await userEvent.selectOptions(screen.getByLabelText('Kind'), 'MOTION')

    const motionPanel = await screen.findByTestId('motion-panel')
    const audioPanel = screen.getByTestId('audio-panel')

    expect(within(motionPanel).getByLabelText('Preset')).toHaveValue('fade-up')
    expect(within(motionPanel).getByLabelText('Duration')).toHaveValue('8')
    expect(within(motionPanel).getByLabelText('Background')).toHaveValue('photo')
    expect(within(motionPanel).getByLabelText('Background motion')).toHaveValue('zoom-in')
    expect(within(motionPanel).getByLabelText(/End card/)).toBeChecked()
    expect(within(audioPanel).getByLabelText('Source')).toHaveValue('none')
  })

  it('play/pause and scrub call every mounted board\'s handle', async () => {
    mockGetsFor(
      creative({
        kind: 'MOTION',
        motion: { preset: 'fade-up', durationSec: 10, background: { source: 'photo', motion: 'zoom-in' }, endCard: true },
        audio: { source: 'none', volume: 0.8, fadeOutSec: 1 },
      }),
    )
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    await screen.findByTestId('motion-transport')
    await waitFor(() => {
      expect(mockHandles['4x5']).toBeTruthy()
      expect(mockHandles['9x16']).toBeTruthy()
    })

    // Autoplay by default (no reduced-motion stub in this test) — both boards already got play().
    await waitFor(() => expect(mockHandles['4x5'].play).toHaveBeenCalled())
    expect(mockHandles['9x16'].play).toHaveBeenCalled()

    const pauseButton = screen.getByRole('button', { name: 'Pause' })
    await userEvent.click(pauseButton)
    await waitFor(() => expect(mockHandles['4x5'].pause).toHaveBeenCalled())
    expect(mockHandles['9x16'].pause).toHaveBeenCalled()

    const scrub = screen.getByLabelText('Scrub')
    fireEvent.change(scrub, { target: { value: '4' } })

    await waitFor(() => expect(mockHandles['4x5'].seek).toHaveBeenCalledWith(4))
    expect(mockHandles['9x16'].seek).toHaveBeenCalledWith(4)
  })

  it('locks the background clip picker to VIDEO and the audio track picker to AUDIO', async () => {
    mockGetsFor(creative({ kind: 'MOTION' }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const motionPanel = await screen.findByTestId('motion-panel')
    await userEvent.selectOptions(within(motionPanel).getByLabelText('Background'), 'clip')
    await userEvent.click(screen.getByRole('button', { name: 'Choose a clip…' }))

    expect(await screen.findByText('Choose a clip')).toBeInTheDocument()
    // A locked picker (kind="VIDEO") hides the type tab bar — only the media-type modal title shows.
    expect(screen.queryByRole('tab', { name: 'Photos' })).not.toBeInTheDocument()
    await userEvent.keyboard('{Escape}')

    const audioPanel = screen.getByTestId('audio-panel')
    await userEvent.selectOptions(within(audioPanel).getByLabelText('Source'), 'track')
    await userEvent.click(screen.getByRole('button', { name: 'Choose a track…' }))

    expect(await screen.findByText('Choose audio')).toBeInTheDocument()
  })

  it('disables Clip sound when the chosen background clip has no audio track', async () => {
    const silentClip = media({ id: 'clip-silent', mediaKind: 'VIDEO', hasAudio: false, durationSeconds: 12 })
    mockGetsFor(
      creative({ kind: 'MOTION', motion: { background: { source: 'clip', clipMediaId: 'clip-silent', clipStartSec: 0 } } }),
      [KIT],
      [silentClip],
    )
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const audioPanel = await screen.findByTestId('audio-panel')
    const source = within(audioPanel).getByLabelText('Source') as HTMLSelectElement
    const clipOption = within(source).getByText(/Clip sound/) as HTMLOptionElement
    await waitFor(() => expect(clipOption.disabled).toBe(true))
  })

  it('enables Clip sound once the chosen background clip has an audio track', async () => {
    const loudClip = media({ id: 'clip-loud', mediaKind: 'VIDEO', hasAudio: true, durationSeconds: 12 })
    mockGetsFor(
      creative({ kind: 'MOTION', motion: { background: { source: 'clip', clipMediaId: 'clip-loud', clipStartSec: 0 } } }),
      [KIT],
      [loudClip],
    )
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const audioPanel = await screen.findByTestId('audio-panel')
    const source = within(audioPanel).getByLabelText('Source') as HTMLSelectElement
    const clipOption = within(source).getByText(/Clip sound/) as HTMLOptionElement
    await waitFor(() => expect(clipOption.disabled).toBe(false))
  })

  it('places 422 motion.*/audio.* violations next to the right controls', async () => {
    const track = media({ id: 'trk-1', mediaKind: 'AUDIO', durationSeconds: 20 })
    mockGetsFor(
      creative({ kind: 'MOTION', audio: { source: 'track', trackId: 'trk-1', volume: 0.8, fadeOutSec: 1 } }),
      [KIT],
      [track],
    )
    ;(apiPatch as Mock).mockRejectedValue({
      status: 422,
      detail: 'The Creative fails a structural rule',
      violations: [
        { field: 'motion.durationSec', ruleId: 'range', message: 'Duration must be between 3 and 60 seconds.' },
        { field: 'audio.volume', ruleId: 'range', message: 'Volume must be between 0 and 1.' },
      ],
    })

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByTestId('motion-panel')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('Duration must be between 3 and 60 seconds.')).toBeInTheDocument()
    expect(screen.getByText('Volume must be between 0 and 1.')).toBeInTheDocument()
  })

  it('starts the Motion preview paused when prefers-reduced-motion is set', async () => {
    const matchMediaMock = vi.fn().mockImplementation((query: string) => ({
      matches: true,
      media: query,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    Object.defineProperty(window, 'matchMedia', { writable: true, configurable: true, value: matchMediaMock })

    mockGetsFor(creative({ kind: 'MOTION' }))
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    expect(await screen.findByRole('button', { name: 'Play' })).toBeInTheDocument()
    await waitFor(() => expect(mockHandles['4x5']).toBeTruthy())
    expect(mockHandles['4x5'].play).not.toHaveBeenCalled()

    Reflect.deleteProperty(window, 'matchMedia')
  })

  it('sends motion and audio on save', async () => {
    mockGetsFor(
      creative({
        kind: 'MOTION',
        motion: { preset: 'word-by-word', durationSec: 12, background: { source: 'photo', motion: 'pan-left' }, endCard: false },
        audio: { source: 'none', volume: 0.5, fadeOutSec: 2 },
      }),
    )
    ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'MOTION' }))

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByTestId('motion-panel')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        expect.stringContaining('/creatives/cr-1'),
        expect.objectContaining({
          motion: expect.objectContaining({
            preset: 'word-by-word',
            durationSec: 12,
            background: expect.objectContaining({ source: 'photo', motion: 'pan-left' }),
            endCard: false,
          }),
          audio: expect.objectContaining({ source: 'none', volume: 0.5, fadeOutSec: 2 }),
        }),
        'tok',
      ),
    )
  })
})
