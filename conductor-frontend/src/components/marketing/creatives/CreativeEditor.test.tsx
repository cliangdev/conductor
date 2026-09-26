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

import { apiGet, apiPatch, apiPost } from '@/lib/api'
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
    version: 3,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

interface TestPhoto {
  id: string
  label: string
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

  it('hides Save actions and disables the form for a role without creative.manage (REVIEWER)', async () => {
    mockCan.mockReturnValue(false)
    mockGetsFor(creative())

    render(<CreativeEditor projectId="proj-1" creativeId="cr-1" token="tok" />)

    const headlineInput = await screen.findByLabelText('Headline')
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Save as variant' })).not.toBeInTheDocument()
    expect(headlineInput).toBeDisabled()
  })
})
