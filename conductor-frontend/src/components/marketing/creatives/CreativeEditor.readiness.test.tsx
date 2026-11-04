import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// The Readiness card's wiring on the Creative page: Mark ready and the in-place fixes.

vi.mock('@cliangdev/creative-render/mount', () => ({
  mountBoard: vi.fn(() => ({ ready: Promise.resolve(), board: null, shell: document.createElement('div'), update: vi.fn(), destroy: vi.fn() })),
  attachFocalDrag: vi.fn(() => ({ detach: vi.fn() })),
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

const { mockCan } = vi.hoisted(() => ({ mockCan: vi.fn((_cap?: string) => true) }))
vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/components/ui/toast', () => ({ useToast: () => ({ showToast: vi.fn() }) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => mockCan(cap),
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: mockCan, refresh: vi.fn() }),
}))

import { apiGet, apiPatch } from '@/lib/api'
import { CreativeEditor } from './CreativeEditor'
import { creative, media } from './slideTextFixtures'
import type { Creative } from './types'

const KIT = {
  id: 'kit-1', projectId: 'p', slug: 'default', name: 'Default', isDefault: true, tokens: { accent: '#3366FF' },
  fontFamily: null, fontUrl: null, markUrl: null, wordmarkDarkUrl: null, wordmarkLightUrl: null, badgeUrl: null, ctaClaim: null,
  accentPhraseRequired: false, copyRules: [], approvedLines: [], enabledPlacements: ['4x5', '9x16'], knowledgePagePath: 'marketing/brand.md',
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
}
const REGISTRY = {
  layouts: { stacked: { themes: ['dark', 'light'] } },
  placements: [
    { key: '4x5', label: '4:5', platform: 'instagram', width: 1080, height: 1350, default: true },
    { key: '9x16', label: '9:16', platform: 'tiktok', width: 1080, height: 1920, default: true },
  ],
}
const item = (key: string, ok: boolean, blocking: boolean, message: string) => ({ key, ok, blocking, message })
const readinessCalls = () => (apiGet as Mock).mock.calls.filter(([p]) => String(p).includes('/readiness')).length

function mockGets(active: Creative, readiness: unknown, images = ['s1', 's2', 's3', 't1', 't2', 't3'].map(media)) {
  ;(apiGet as Mock).mockImplementation((path: string) => {
    if (path.includes('/creative-registry')) return Promise.resolve(REGISTRY)
    if (path.includes('/brand-kits')) return Promise.resolve([KIT])
    if (path.includes('/readiness')) return Promise.resolve(readiness)
    if (path.match(/\/marketing\/photos(\?|$)/)) return Promise.resolve(images)
    if (path.endsWith(`/creatives/${active.id}`)) return Promise.resolve(active)
    return Promise.resolve([])
  })
}

const open = () => render(<CreativeEditor projectId="p" creativeId="cr-1" token="t" />)

beforeEach(() => {
  vi.clearAllMocks()
  mockCan.mockReturnValue(true)
  Element.prototype.scrollIntoView = vi.fn()
})

describe('CreativeEditor — Readiness card', () => {
  it('Mark ready PATCHes only the state with the current version, then refreshes readiness', async () => {
    const draft = creative({ state: 'DRAFT', slideshowSpec: null })
    mockGets(draft, { ready: false, items: [item('caption', true, true, 'caption is set'), item('state', false, true, 'Creative is still a draft')] })
    ;(apiPatch as Mock).mockResolvedValue({ ...draft, state: 'READY', version: 5 })
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Mark ready' }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect((apiPatch as Mock).mock.calls[0][0]).toContain('/creatives/cr-1')
    expect((apiPatch as Mock).mock.calls[0][1]).toEqual({ version: 4, state: 'READY' })
    await waitFor(() => expect(screen.getByTestId('readiness-title')).toHaveTextContent(/^Ready$/))
    expect(readinessCalls()).toBeGreaterThanOrEqual(2)
  })

  it('keeps Mark ready disabled while something blocks', async () => {
    mockGets(creative({ state: 'DRAFT', slideshowSpec: null }), { ready: false, items: [item('caption', false, true, 'caption is missing')] })
    open()
    expect(await screen.findByRole('button', { name: 'Mark ready' })).toBeDisabled()
  })

  it('Add alt text focuses the first slide with no alt text', async () => {
    mockGets(creative({ slideshowSpec: null, slideAltText: ['First', '', ''] }), {
      ready: false,
      items: [item('altText', false, true, 'alt text is missing')],
    })
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Add alt text' }))
    expect(screen.getByLabelText('Alt text for slide 2')).toHaveFocus()
  })

  it('Add caption focuses the caption field', async () => {
    mockGets(creative({ slideshowSpec: null, caption: '' }), { ready: false, items: [item('caption', false, true, 'caption is missing')] })
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Add caption' }))
    expect(document.getElementById('creative-caption')).toHaveFocus()
  })

  it("I've checked them marks each unchecked image checked, then refreshes readiness", async () => {
    const images = ['s1', 's2', 's3', 't1', 't2', 't3'].map((id) => ({ ...media(id), checked: id !== 't3' }))
    mockGets(
      creative({ slideshowSpec: null }),
      { ready: false, items: [item('photoChecked', false, true, '1 of 6 slide images have not been checked for burned-in text')] },
      images,
    )
    ;(apiPatch as Mock).mockResolvedValue({})
    open()
    await screen.findByText('Look over 1 photo for text in the image')
    const before = readinessCalls()
    await userEvent.click(screen.getByRole('button', { name: "I've checked them" }))
    await waitFor(() => expect(apiPatch).toHaveBeenCalledTimes(1))
    expect((apiPatch as Mock).mock.calls[0][0]).toContain('/photos/t3')
    expect((apiPatch as Mock).mock.calls[0][1]).toEqual({ checked: true })
    await waitFor(() => expect(readinessCalls()).toBeGreaterThan(before))
  })

  it('shows no Mark ready or fix buttons to someone who cannot manage Creatives', async () => {
    mockCan.mockReturnValue(false)
    mockGets(creative({ state: 'DRAFT', slideshowSpec: null }), { ready: false, items: [item('caption', false, true, 'caption is missing')] })
    open()
    await screen.findByText('Add a caption')
    expect(screen.queryByRole('button', { name: 'Mark ready' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add caption' })).not.toBeInTheDocument()
  })
})
