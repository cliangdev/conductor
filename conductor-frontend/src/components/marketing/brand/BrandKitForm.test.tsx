import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

const { toastSpy } = vi.hoisted(() => ({ toastSpy: vi.fn() }))
vi.mock('@/components/ui/toast', () => ({ useToast: () => ({ showToast: toastSpy }) }))

import { apiGet } from '@/lib/api'
import { BrandKitForm } from './BrandKitForm'
import type { BrandKit } from './types'

function kit(overrides: Partial<BrandKit> = {}): BrandKit {
  return {
    id: 'kit-1',
    projectId: 'proj-1',
    slug: 'default',
    name: 'Default',
    isDefault: true,
    tokens: { accent: '#3366FF' },
    fontFamily: 'DM Sans',
    fontUrl: null,
    markUrl: null,
    wordmarkDarkUrl: null,
    wordmarkLightUrl: null,
    badgeUrl: null,
    ctaClaim: '7 days free.',
    accentPhraseRequired: true,
    copyRules: [
      { id: 'no-exclaim', pattern: '!', message: 'No exclamation marks.', fields: ['headline', 'body'] },
    ],
    approvedLines: ['Dinner, decided.'],
    enabledPlacements: ['9x16', '4x5', '1x1'],
    knowledgePagePath: 'marketing/brand.md',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

const REGISTRY = {
  layouts: { stacked: { themes: ['dark', 'light'] }, bleed: { themes: ['dark'] } },
  placements: [
    { key: '9x16', label: '9:16 TikTok and Reels', platform: 'tiktok', width: 1080, height: 1920, default: true },
    { key: '4x5', label: '4:5 Instagram feed', platform: 'instagram', width: 1080, height: 1350, default: true },
  ],
}

describe('BrandKitForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/creative-registry')) return Promise.resolve(REGISTRY)
      if (path.includes('/brand-kits')) return Promise.resolve([kit()])
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
  })

  it('runs the kit copy rules live against a test line', async () => {
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    const testLineInput = await screen.findByLabelText('Test a line')
    await userEvent.type(testLineInput, 'Every saved link, finally usable!')

    expect(await screen.findByText(/No exclamation marks\./)).toBeInTheDocument()
  })

  it('shows no issues for a line that clears every rule and the accent-phrase requirement', async () => {
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    const testLineInput = await screen.findByLabelText('Test a line')
    await userEvent.type(testLineInput, 'Every saved link, *finally usable*.')

    expect(await screen.findByText('No issues.')).toBeInTheDocument()
  })

  it('flags a missing accent phrase when the kit requires one', async () => {
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    const testLineInput = await screen.findByLabelText('Test a line')
    await userEvent.type(testLineInput, 'Every saved link, finally usable.')

    expect(await screen.findByText(/found 0/)).toBeInTheDocument()
  })
})
