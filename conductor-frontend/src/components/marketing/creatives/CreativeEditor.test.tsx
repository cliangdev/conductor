import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

/** Dispatches a native pointer event on `el` — the frame's board is mounted by
 * @cliangdev/creative-render's mountBoard, which wires its focal-drag/click detection with plain
 * addEventListener, not React's synthetic events, so a real DOM event is required (RTL's fireEvent
 * helpers cover click but not pointerdown/move/up with custom clientX/clientY). */
function dispatchPointer(el: Element, type: string, props: { clientX: number; clientY: number }) {
  const event = new Event(type) as Event & { pointerId?: number; clientX?: number; clientY?: number }
  Object.assign(event, { pointerId: 1, ...props })
  fireEvent(el, event)
}

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

import { apiDelete, apiGet, apiPatch, apiPost } from '@/lib/api'
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
  copyRules: [
    { id: 'no-exclaim', pattern: '!', message: 'No exclamation marks.', fields: ['headline', 'body'] },
  ],
  approvedLines: [],
  enabledPlacements: ['4x5'],
  knowledgePagePath: 'marketing/brand.md',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const SECOND_KIT: BrandKit = {
  ...KIT,
  id: 'kit-2',
  name: 'Second brand',
  isDefault: false,
  tokens: { accent: '#2F6FD0' },
}

const REGISTRY = {
  layouts: { stacked: { themes: ['dark', 'light'] } },
  placements: [
    { key: '4x5', label: '4:5 Instagram feed', platform: 'instagram', width: 1080, height: 1350, default: true },
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
    name: 'Paste a link',
    state: 'DRAFT',
    kind: 'STILL',
    layout: 'stacked',
    theme: 'dark',
    photoId: null,
    photoUrl: null,
    focalOverride: null,
    headline: 'Original headline',
    body: 'Original body',
    caption: 'Caption',
    altText: 'Alt text',
    placements: [],
    sequenceKind: null,
    sequence: [],
    carouselRatio: null,
    typeOverrides: {},
    lockup: 'plain',
    layoutOverrides: null,
    version: 3,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

interface TestPhoto {
  id: string
  label: string | null
  url: string
}

function photo(overrides: Partial<TestPhoto> & { id: string }): TestPhoto & Record<string, unknown> {
  return {
    label: overrides.id,
    url: `https://storage.example/${overrides.id}.jpg`,
    projectId: 'proj-1',
    contentType: 'image/jpeg',
    sizeBytes: 1,
    width: 10,
    height: 10,
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

function mockGetsFor(activeCreative: Creative, kits: BrandKit[] = [KIT], photos: ReturnType<typeof photo>[] = []) {
  ;(apiGet as Mock).mockImplementation((path: string) => {
    if (path.includes('/creative-registry')) return Promise.resolve(REGISTRY)
    if (path.includes('/brand-kits')) return Promise.resolve(kits)
    if (path.includes('/readiness')) return Promise.resolve({ ready: false, items: [] })
    const singlePhoto = path.match(/\/marketing\/photos\/([^/?]+)$/)
    if (singlePhoto) {
      const found = photos.find((p) => p.id === singlePhoto[1])
      return found ? Promise.resolve(found) : Promise.reject(new Error(`no such photo ${singlePhoto[1]}`))
    }
    if (path.match(/\/marketing\/photos(\?|$)/)) return Promise.resolve(photos)
    if (path.endsWith(`/creatives/${activeCreative.id}`)) return Promise.resolve(activeCreative)
    return Promise.reject(new Error(`unexpected GET ${path}`))
  })
}

describe('CreativeEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
  })

  it('re-renders the live preview on every keystroke with no server call (AC-P0-2.2)', async () => {
    mockGetsFor(creative())
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const headlineInput = await screen.findByLabelText('Headline')
    const board = await screen.findByTestId('placement-board-4x5')
    await waitFor(() => expect(board.textContent).toContain('Original headline'))

    await userEvent.clear(headlineInput)
    await userEvent.type(headlineInput, 'Brand new headline copy')

    await waitFor(() => expect(board.textContent).toContain('Brand new headline copy'))
    expect(apiPost).not.toHaveBeenCalled()
    expect(apiPatch).not.toHaveBeenCalled()
  })

  it('shows a 422 violation next to the headline field (AC-P0-2.1)', async () => {
    mockGetsFor(creative())
    ;(apiPatch as Mock).mockRejectedValue({
      status: 422,
      detail: 'The Creative fails a structural rule',
      violations: [{ field: 'headline', ruleId: 'no-exclaim', message: 'No exclamation marks.' }],
    })

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByLabelText('Headline')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText('No exclamation marks.')).toBeInTheDocument()
  })

  it('shows a headline copy-rule message once, not twice, when the live check and the 422 violation agree', async () => {
    mockGetsFor(creative({ headline: 'Dinner is ready!' }))
    ;(apiPatch as Mock).mockRejectedValue({
      status: 422,
      detail: 'The Creative fails a structural rule',
      violations: [{ field: 'headline', ruleId: 'no-exclaim', message: 'No exclamation marks.' }],
    })

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByLabelText('Headline')

    // The live copy-rule check already flags this headline before any save is attempted.
    expect(await screen.findAllByText('No exclamation marks.')).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    // The server's 422 carries the identical message — it must not render a second time.
    expect(await screen.findAllByText('No exclamation marks.')).toHaveLength(1)
  })

  it('shows a clear reload message on a 409 version conflict', async () => {
    mockGetsFor(creative())
    ;(apiPatch as Mock).mockRejectedValue({ status: 409, detail: 'stale version' })

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByLabelText('Headline')

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))

    expect(await screen.findByText(/changed elsewhere/i)).toBeInTheDocument()
  })

  it('navigates to the new variant after "Save as variant" (AC-P0-2.3)', async () => {
    mockGetsFor(creative())
    ;(apiPost as Mock).mockResolvedValue(creative({ id: 'cr-2', variantLetter: 'b', displayId: '12b' }))

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByLabelText('Headline')

    await userEvent.click(screen.getByRole('button', { name: 'Save as variant' }))
    await userEvent.click(screen.getByRole('button', { name: 'Create variant' }))

    await waitFor(() =>
      expect(pushSpy).toHaveBeenCalledWith('/app/projects/proj-1/marketing/creatives/cr-2'),
    )
    expect(apiPost).toHaveBeenCalledWith(
      expect.stringContaining('/creatives/cr-1/variants'),
      expect.any(Object),
      'tok',
    )
  })

  it('uses the newly-selected kit\'s tokens in the preview when the kit changes (AC-P0-2.4)', async () => {
    mockGetsFor(creative(), [KIT, SECOND_KIT])
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const board = await screen.findByTestId('placement-board-4x5')
    await waitFor(() => {
      const inner = board.querySelector('.cc-board') as HTMLElement | null
      expect(inner?.style.getPropertyValue('--cc-accent')).toBe('#3366FF')
    })

    await userEvent.selectOptions(screen.getByLabelText('Brand kit'), 'kit-2')

    await waitFor(() => {
      const inner = board.querySelector('.cc-board') as HTMLElement | null
      expect(inner?.style.getPropertyValue('--cc-accent')).toBe('#2F6FD0')
    })
  })

  it("gives a sequence beat its own photo, falling back to the Creative's main photo when a beat is left unset", async () => {
    const mainPhoto = photo({ id: 'photo-main' })
    const beatPhoto = photo({ id: 'photo-beat' })
    mockGetsFor(
      creative({ photoId: 'photo-main', sequenceKind: 'story', sequence: [{}, {}] }),
      [KIT],
      [mainPhoto, beatPhoto],
    )

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const board = await screen.findByTestId('placement-board-4x5')
    const boardPhotoSrc = () => (board.querySelector('.cc-board__band img') as HTMLImageElement | null)?.src ?? ''

    // Beat 1 (the shown frame at sequenceIndex 0) starts unset, so it falls back to the main photo.
    await waitFor(() => expect(boardPhotoSrc()).toContain('photo-main.jpg'))

    await userEvent.click(screen.getByLabelText('Beat 1 photo'))
    await userEvent.click(await screen.findByRole('button', { name: 'photo-beat' }))

    await waitFor(() => expect(boardPhotoSrc()).toContain('photo-beat.jpg'))

    // Beat 2 was never given its own photo — it keeps falling back to the main photo.
    await userEvent.click(screen.getByRole('button', { name: 'Next beat' }))
    await waitFor(() => expect(boardPhotoSrc()).toContain('photo-main.jpg'))
  })

  it('shows a content-type fallback instead of the raw photo id when a photo has no label', async () => {
    const unlabelled = photo({ id: 'a1b2c3d4-e5f6-7890-uuid', label: null })
    mockGetsFor(creative({ photoId: 'a1b2c3d4-e5f6-7890-uuid' }), [KIT], [unlabelled])

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    expect(await screen.findByText('Untitled jpeg photo')).toBeInTheDocument()
    expect(screen.queryByText('a1b2c3d4-e5f6-7890-uuid')).not.toBeInTheDocument()
  })

  it('toggles the lockup chip class in the live preview and saves it as part of the patch', async () => {
    const kitWithLogo: BrandKit = { ...KIT, markUrl: 'https://cdn.example/mark.png' }
    mockGetsFor(creative(), [kitWithLogo])
    ;(apiPatch as Mock).mockResolvedValue(creative({ lockup: 'chip' }))

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    const board = await screen.findByTestId('placement-board-4x5')
    await waitFor(() => expect(board.querySelector('.cc-lockup')).toBeTruthy())
    expect(board.querySelector('.cc-lockup--chip')).toBeNull()

    await userEvent.click(screen.getByRole('button', { name: 'Advanced' }))
    await userEvent.selectOptions(screen.getByLabelText('Lockup'), 'chip')

    await waitFor(() => expect(board.querySelector('.cc-lockup--chip')).toBeTruthy())

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        expect.stringContaining('/creatives/cr-1'),
        expect.objectContaining({ lockup: 'chip' }),
        'tok',
      ),
    )
  })

  it('a per-placement band override sets --cc-band-h on the live board and is included in the patch', async () => {
    mockGetsFor(creative({ layout: 'stacked' }))
    ;(apiPatch as Mock).mockResolvedValue(creative())

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    const board = await screen.findByTestId('placement-board-4x5')
    await screen.findByLabelText('Headline')

    await userEvent.click(screen.getByRole('button', { name: 'Advanced' }))
    await userEvent.type(screen.getByLabelText('4x5 band height override'), '900')

    await waitFor(() => {
      const inner = board.querySelector('.cc-board') as HTMLElement | null
      expect(inner?.style.getPropertyValue('--cc-band-h')).toBe('900px')
    })

    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await waitFor(() =>
      expect(apiPatch).toHaveBeenCalledWith(
        expect.stringContaining('/creatives/cr-1'),
        expect.objectContaining({ layoutOverrides: { band: { '4x5': 900 }, padBottom: undefined } }),
        'tok',
      ),
    )
  })

  it('deletes the Creative after confirmation and routes back to the library', async () => {
    mockGetsFor(creative())
    ;(apiDelete as Mock).mockResolvedValue(undefined)

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByLabelText('Headline')

    await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete this Creative' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() =>
      expect(apiDelete).toHaveBeenCalledWith(expect.stringContaining('/creatives/cr-1'), 'tok'),
    )
    await waitFor(() => expect(pushSpy).toHaveBeenCalledWith('/app/projects/proj-1/marketing/creatives'))
  })

  it('shows the 409 refusal message and stays on the page when delete is blocked', async () => {
    mockGetsFor(creative())
    ;(apiDelete as Mock).mockRejectedValue({ status: 409, detail: 'Remove it from the Post first (AM-1)' })

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
    await screen.findByLabelText('Headline')

    await userEvent.click(screen.getByRole('button', { name: 'More actions' }))
    await userEvent.click(await screen.findByRole('menuitem', { name: 'Delete this Creative' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(toastSpy).toHaveBeenCalled())
    expect(pushSpy).not.toHaveBeenCalledWith('/app/projects/proj-1/marketing/creatives')
  })

  it('hides Save actions and disables the form for a role without creative.manage (REVIEWER)', async () => {
    mockCan.mockReturnValue(false)
    mockGetsFor(creative())

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const headlineInput = await screen.findByLabelText('Headline')
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save as variant' })).not.toBeInTheDocument()
    expect(headlineInput).toBeDisabled()
  })

  it('opens the full-size viewer on a plain click, without moving the focal point', async () => {
    mockGetsFor(creative())
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const board = await screen.findByTestId('placement-board-4x5')
    await waitFor(() => expect(board.firstElementChild).toBeTruthy())
    const shell = board.firstElementChild as HTMLElement
    const focalBefore = (board.querySelector('.cc-board') as HTMLElement | null)?.style.getPropertyValue('--cc-focal')

    dispatchPointer(shell, 'pointerdown', { clientX: 40, clientY: 40 })
    dispatchPointer(shell, 'pointerup', { clientX: 40, clientY: 40 })

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('4:5 Instagram feed')).toBeInTheDocument()
    expect(within(dialog).getByText('1080×1350px')).toBeInTheDocument()
    expect((board.querySelector('.cc-board') as HTMLElement | null)?.style.getPropertyValue('--cc-focal')).toBe(focalBefore)
  })

  it('still sets the focal point on an actual drag (movement past the threshold) and does not open the viewer', async () => {
    mockGetsFor(creative())
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const board = await screen.findByTestId('placement-board-4x5')
    const shell = board.firstElementChild as HTMLElement
    shell.getBoundingClientRect = () =>
      ({ left: 0, top: 0, width: 200, height: 250, right: 200, bottom: 250, x: 0, y: 0, toJSON() {} }) as DOMRect

    dispatchPointer(shell, 'pointerdown', { clientX: 40, clientY: 40 })
    dispatchPointer(shell, 'pointermove', { clientX: 140, clientY: 140 })
    dispatchPointer(shell, 'pointerup', { clientX: 140, clientY: 140 })

    await waitFor(() =>
      expect((board.querySelector('.cc-board') as HTMLElement | null)?.style.getPropertyValue('--cc-focal')).not.toBe(''),
    )
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it("steps through the enabled placements with the viewer's Next/Previous buttons", async () => {
    mockGetsFor(creative(), [{ ...KIT, enabledPlacements: ['4x5', '1x1'] }])
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const board = await screen.findByTestId('placement-board-4x5')
    const shell = board.firstElementChild as HTMLElement
    dispatchPointer(shell, 'pointerdown', { clientX: 10, clientY: 10 })
    dispatchPointer(shell, 'pointerup', { clientX: 10, clientY: 10 })

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('4:5 Instagram feed')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Previous' })).not.toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: 'Next' }))
    expect(within(dialog).getByText('1:1 Facebook feed')).toBeInTheDocument()
    expect(within(dialog).queryByRole('button', { name: 'Next' })).not.toBeInTheDocument()

    await userEvent.click(within(dialog).getByRole('button', { name: 'Previous' }))
    expect(within(dialog).getByText('4:5 Instagram feed')).toBeInTheDocument()
  })

  it('lets a reader (no creative.manage) open the full-size viewer with a plain click', async () => {
    mockCan.mockReturnValue(false)
    mockGetsFor(creative())
    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const board = await screen.findByTestId('placement-board-4x5')
    const shell = board.firstElementChild as HTMLElement
    fireEvent.click(shell)

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('4:5 Instagram feed')).toBeInTheDocument()
  })

  describe('Kind and Clip mode (COND-24 T6)', () => {
    it('shows a Kind control defaulting to Still, with Still, Motion and Clip all selectable', async () => {
      mockGetsFor(creative())
      render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
      await screen.findByLabelText('Headline')

      const kindSelect = screen.getByLabelText('Kind') as HTMLSelectElement
      expect(kindSelect.value).toBe('STILL')
      const motionOption = screen.getByRole('option', { name: 'Motion' }) as HTMLOptionElement
      expect(motionOption.disabled).toBe(false)
    })

    it('switching to Clip hides Still-only fields and shows the Clip media section, keeping name/caption/alt text/state', async () => {
      mockGetsFor(creative({ name: 'My ad', caption: 'Caption text', altText: 'Alt text' }))
      render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
      await screen.findByLabelText('Headline')

      await userEvent.selectOptions(screen.getByLabelText('Kind'), 'CLIP')

      expect(screen.queryByLabelText('Headline')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Layout')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Theme')).not.toBeInTheDocument()
      expect(screen.queryByTestId('placement-board-4x5')).not.toBeInTheDocument()
      expect(screen.getByLabelText('Caption')).toHaveValue('Caption text')
      expect(screen.getByLabelText('Alt text')).toHaveValue('Alt text')
      expect(screen.getByLabelText('Name')).toHaveValue('My ad')
      expect(screen.getByText('Clip media')).toBeInTheDocument()
    })

    it('picks a default clip video and shows it playable with its poster and duration', async () => {
      const video = {
        id: 'vid-1',
        label: 'clip.mp4',
        url: 'https://storage.example/clip.mp4',
        contentType: 'video/mp4',
        sizeBytes: 1000,
        width: 1080,
        height: 1920,
        mediaKind: 'VIDEO',
        durationSeconds: 20,
        hasAudio: true,
        posterUrl: 'https://storage.example/clip-poster.jpg',
        aiGenerated: false,
        checked: true,
        blocked: false,
        focal: {},
        uploadStatus: 'UPLOADED',
        warnings: [],
        createdAt: '2026-01-01T00:00:00Z',
      }
      mockGetsFor(creative({ kind: 'CLIP' }), [KIT], [video])
      render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
      await screen.findByText('Clip media')

      await userEvent.click(screen.getAllByRole('button', { name: 'Choose a video…' })[0])
      const grid = await screen.findByTestId('video-picker-grid')
      await userEvent.click(grid.querySelector('button')!)

      const videoEl = await screen.findByTestId('clip-media-video-default') as HTMLVideoElement
      expect(videoEl.poster).toBe('https://storage.example/clip-poster.jpg')
      expect(videoEl.src).toBe('https://storage.example/clip.mp4')
      expect(screen.getByText(/20s/)).toBeInTheDocument()
    })

    it('saves kind and clipMedia for a Clip creative', async () => {
      mockGetsFor(creative({ kind: 'CLIP', clipMedia: { default: 'vid-1' } }))
      ;(apiPatch as Mock).mockResolvedValue(creative({ kind: 'CLIP', clipMedia: { default: 'vid-1' } }))
      render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)
      await screen.findByText('Clip media')

      await userEvent.click(screen.getByRole('button', { name: 'Save' }))

      await waitFor(() =>
        expect(apiPatch).toHaveBeenCalledWith(
          expect.stringContaining('/creatives/cr-1'),
          expect.objectContaining({ kind: 'CLIP', clipMedia: { default: 'vid-1' } }),
          'tok',
        ),
      )
    })
  })
})
