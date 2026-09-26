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
import type { Creative } from './types'
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
    layout: 'stacked',
    theme: 'dark',
    photoId: null,
    photoUrl: null,
    focalOverride: null,
    headline: 'Every saved link, finally usable.',
    body: 'Body',
    caption: 'Caption',
    altText: 'Alt',
    placements: [],
    sequenceKind: null,
    sequence: [],
    carouselRatio: null,
    typeOverrides: {},
    version: 1,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

describe('CreativeLibraryGrid', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockCan.mockReturnValue(true)
  })

  it('lists Creatives grouped by family with variant pills', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/brand-kits')) return Promise.resolve([KIT])
      if (path.includes('/creatives')) {
        return Promise.resolve([
          creative(),
          creative({ id: 'cr-1b', variantLetter: 'b', displayId: '12b', parentCreativeId: 'cr-1' }),
        ])
      }
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    expect(await screen.findByText('12a')).toBeInTheDocument()
    expect(screen.getByText('b')).toBeInTheDocument()
  })

  it('creates a new Creative and navigates to its editor', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/brand-kits')) return Promise.resolve([KIT])
      if (path.includes('/creatives')) return Promise.resolve([])
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
    ;(apiPost as Mock).mockResolvedValue(creative({ id: 'cr-new' }))

    render(<CreativeLibraryGrid projectId="proj-1" />)
    await screen.findByText('New creative')

    await userEvent.click(screen.getAllByRole('button', { name: 'New creative' })[0])

    await waitFor(() =>
      expect(pushSpy).toHaveBeenCalledWith('/app/projects/proj-1/marketing/creatives/cr-new'),
    )
  })

  it('hides "New creative" for a role without creative.manage (REVIEWER)', async () => {
    mockCan.mockReturnValue(false)
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/brand-kits')) return Promise.resolve([KIT])
      if (path.includes('/creatives')) return Promise.resolve([creative()])
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })

    render(<CreativeLibraryGrid projectId="proj-1" />)

    expect(await screen.findByText('12a')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New creative' })).not.toBeInTheDocument()
  })
})
