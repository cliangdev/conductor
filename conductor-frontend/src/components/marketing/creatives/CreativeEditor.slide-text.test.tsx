import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

// The slide-text editor's entry points on the Creative page: the "Edit text" action, the history panel,
// the pending / failed notes and the alt-text flags. The editor's own behaviour is in SlideTextEditor.test.tsx.

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

import { apiGet } from '@/lib/api'
import { CreativeEditor } from './CreativeEditor'
import { creative, media, BG_ID } from './slideTextFixtures'
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
const IMAGES = ['s1', 's2', 's3', 't1', 't2', 't3', BG_ID].map(media)

function mockGets(active: Creative, extra: { readiness?: unknown; revisions?: unknown[]; detail?: unknown } = {}) {
  ;(apiGet as Mock).mockImplementation((path: string) => {
    if (path.includes('/creative-registry')) return Promise.resolve(REGISTRY)
    if (path.includes('/brand-kits')) return Promise.resolve([KIT])
    if (path.includes('/readiness')) return Promise.resolve(extra.readiness ?? { ready: true, items: [] })
    if (path.endsWith('/revisions')) return Promise.resolve({ revisions: extra.revisions ?? [] })
    if (/\/revisions\/[^/]+$/.test(path)) return Promise.resolve(extra.detail ?? {})
    if (path.match(/\/marketing\/photos(\?|$)/)) return Promise.resolve(IMAGES)
    if (path.endsWith(`/creatives/${active.id}`)) return Promise.resolve(active)
    // the panels below the editor ask for these; none of them matters here
    return Promise.resolve([])
  })
}

const open = () => render(<CreativeEditor projectId="p" creativeId="cr-1" token="t" />)

beforeEach(() => {
  vi.clearAllMocks()
  mockCan.mockReturnValue(true)
})

describe('CreativeEditor — editing slide text', () => {
  it('offers Edit text on the current slide when the Creative has a stored spec', async () => {
    mockGets(creative())
    open()
    await userEvent.click(await screen.findByRole('button', { name: 'Next slide' }))
    await userEvent.click(screen.getByTestId('edit-slide-text'))
    expect(await screen.findByTestId('slide-text-editor')).toBeInTheDocument()
    expect(screen.getByTestId('slide-position')).toHaveTextContent('Slide 2 of 3')
    expect(screen.getByLabelText('Title')).toHaveValue('Plan')
  })

  it('has no Edit text, and no history, without a spec', async () => {
    mockGets(creative({ slideshowSpec: null }))
    open()
    await screen.findByTestId('carousel-slides-section')
    expect(screen.queryByTestId('edit-slide-text')).not.toBeInTheDocument()
    expect(screen.queryByTestId('creative-revisions')).not.toBeInTheDocument()
  })

  it('explains why a composed slideshow saved without its spec has no Edit text', async () => {
    mockGets(creative({ slideshowSpec: null, slideStyle: { textSize: 60 } }))
    open()
    expect(await screen.findByTestId('edit-slide-text-unavailable')).toHaveTextContent('saved without its spec')
    expect(screen.queryByTestId('edit-slide-text')).not.toBeInTheDocument()
  })

  it('says nothing about a spec for a carousel of plain images (no slide style)', async () => {
    mockGets(creative({ slideshowSpec: null, slideStyle: null }))
    open()
    await screen.findByTestId('carousel-slides-section')
    expect(screen.queryByTestId('edit-slide-text-unavailable')).not.toBeInTheDocument()
  })

  it('offers no Edit text for a Creative with 1x1 slides, and says why', async () => {
    mockGets(creative({ slides: { '9x16': ['s1', 's2', 's3'], '4x5': ['t1', 't2', 't3'], '1x1': ['q1', 'q2', 'q3'] } }))
    open()
    expect(await screen.findByTestId('edit-slide-text-unavailable')).toHaveTextContent(
      "Text editing isn't available for Creatives with 1x1 slides yet.",
    )
    expect(screen.queryByTestId('edit-slide-text')).not.toBeInTheDocument()
  })

  it('still offers Edit text when the 1x1 list is empty', async () => {
    mockGets(creative({ slides: { '9x16': ['s1', 's2', 's3'], '4x5': ['t1', 't2', 't3'], '1x1': [] } }))
    open()
    expect(await screen.findByTestId('edit-slide-text')).toBeInTheDocument()
    expect(screen.queryByTestId('edit-slide-text-unavailable')).not.toBeInTheDocument()
  })

  it('has no Edit text for someone who cannot manage Creatives', async () => {
    mockCan.mockReturnValue(false)
    mockGets(creative())
    open()
    await screen.findByTestId('carousel-slides-section')
    expect(screen.queryByTestId('edit-slide-text')).not.toBeInTheDocument()
  })

  it('disables it while an edit is rendering, and says so', async () => {
    mockGets(creative({ pendingRevision: { id: 'rev-1', status: 'PENDING', createdAt: '2026-11-02T10:00:00Z' } }))
    open()
    expect(await screen.findByTestId('edit-slide-text')).toBeDisabled()
    expect(screen.getByTestId('edit-slide-text-reason')).toHaveTextContent('A text edit is rendering.')
    expect(screen.getByTestId('revision-pending')).toBeInTheDocument()
  })

  it('asks for unsaved changes to be saved first', async () => {
    mockGets(creative())
    open()
    await userEvent.type(await screen.findByLabelText('Alt text for slide 1'), 'x')
    expect(screen.getByTestId('edit-slide-text')).toBeDisabled()
    expect(screen.getByTestId('edit-slide-text-reason')).toHaveTextContent('Save your changes first.')
  })

  it('shows the text history panel', async () => {
    mockGets(creative(), {
      revisions: [{ id: 'r1', revisionNo: 1, creativeVersion: 4, status: 'APPLIED', source: 'EDITOR', authorName: 'Ada Admin', createdAt: new Date().toISOString(), textDiff: [{ slide: 1, path: 'texts[0].t', old: 'a', new: 'b' }] }],
    })
    open()
    const panel = await screen.findByTestId('creative-revisions')
    expect(await within(panel).findByTestId('revision-1')).toHaveTextContent('Web editor')
  })

  it('shows why the last edit failed', async () => {
    mockGets(
      creative({
        latestRevision: {
          id: 'r2', revisionNo: 2, status: 'FAILED', source: 'EDITOR', error: 'Slide checks failed', createdAt: '2026-11-02T10:00:00Z',
          checks: [{ slide: 2, ratio: '9x16', rule: 'spill', severity: 'error', message: 'Plan extends past the frame' }],
        },
      }),
    )
    open()
    const note = await screen.findByTestId('revision-failed')
    expect(note).toHaveTextContent('The last text edit failed: Slide checks failed')
    expect(within(note).getByTestId('slide-check-spill')).toHaveTextContent('Slide 2, 9:16')
  })
})

describe('CreativeEditor — alt text flags', () => {
  const applied = { id: 'r3', revisionNo: 3, status: 'APPLIED' as const, source: 'EDITOR' as const, createdAt: '2026-11-02T10:00:00Z' }
  const stale = { ready: true, items: [{ key: 'altTextStale', ok: false, blocking: false, message: 'slide 1 alt text still quotes the old text' }] }
  const detail = { altTextFlags: [1], textDiff: [{ slide: 1, path: 'texts[0].t', old: 'Hook line', new: 'New hook' }] }

  it('flags a slide whose alt text still quotes the replaced words', async () => {
    mockGets(creative({ latestRevision: applied, slideAltText: ['A poster saying Hook line', 'Plan', ''] }), { readiness: stale, detail })
    open()
    expect(await screen.findByTestId('slide-alt-stale-0')).toHaveTextContent('Quotes the old text — update')
    expect(screen.queryByTestId('slide-alt-stale-1')).not.toBeInTheDocument()
  })

  it('clears the flag as the alt text stops quoting the old words', async () => {
    mockGets(creative({ latestRevision: applied, slideAltText: ['A poster saying Hook line', '', ''] }), { readiness: stale, detail })
    open()
    const alt = await screen.findByLabelText('Alt text for slide 1')
    await screen.findByTestId('slide-alt-stale-0')
    await userEvent.clear(alt)
    await userEvent.type(alt, 'A poster saying New hook')
    await waitFor(() => expect(screen.queryByTestId('slide-alt-stale-0')).not.toBeInTheDocument())
  })

  it('shows no flag when the readiness list has no altTextStale item', async () => {
    mockGets(creative({ latestRevision: applied, slideAltText: ['A poster saying Hook line', '', ''] }), { detail })
    open()
    await screen.findByLabelText('Alt text for slide 1')
    expect(screen.queryByTestId('slide-alt-stale-0')).not.toBeInTheDocument()
  })
})
