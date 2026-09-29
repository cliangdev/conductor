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

const { toastSpy, mockCan } = vi.hoisted(() => ({ toastSpy: vi.fn(), mockCan: vi.fn((_cap?: string) => true) }))
vi.mock('@/components/workitems/MediaUploadPanel', () => ({ putToSignedUrl: vi.fn(() => Promise.resolve()) }))
vi.mock('@/components/ui/toast', () => ({ useToast: () => ({ showToast: toastSpy }) }))
vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: (cap: string) => mockCan(cap),
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: mockCan, refresh: vi.fn() }),
}))

import { apiGet, apiPost } from '@/lib/api'
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
    mockCan.mockReturnValue(true)
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/creative-registry')) return Promise.resolve(REGISTRY)
      if (path.includes('/brand-kits')) return Promise.resolve([kit()])
      return Promise.reject(new Error(`unexpected GET ${path}`))
    })
  })

  it('keeps unsaved edits when a logo is uploaded', async () => {
    ;(apiPost as Mock).mockImplementation((path: string) => {
      if (path.endsWith('/images/mark')) return Promise.resolve({ uploadUrl: 'https://up.example/mark', gcsPath: 'p/mark.png' })
      if (path.endsWith('/images/mark/confirm')) return Promise.resolve(kit({ markUrl: 'https://cdn.example/mark.png' }))
      return Promise.reject(new Error(`unexpected POST ${path}`))
    })
    const { container } = render(<BrandKitForm projectId="proj-1" token="tok" />)

    const name = await screen.findByLabelText('Name')
    await userEvent.clear(name)
    await userEvent.type(name, 'Acme')
    const fileInput = container.querySelector('input[type=file]') as HTMLInputElement
    await userEvent.upload(fileInput, new File(['png'], 'mark.png', { type: 'image/png' }))

    await waitFor(() => expect(apiPost).toHaveBeenCalledWith(expect.stringContaining('/images/mark/confirm'), expect.anything(), 'tok'))
    expect(screen.getByLabelText('Name')).toHaveValue('Acme')
  })

  it('runs the kit copy rules live against a test line', async () => {
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    const testLineInput = await screen.findByLabelText('Test a line')
    await userEvent.type(testLineInput, 'Every day, finally organized!')

    expect(await screen.findByText(/No exclamation marks\./)).toBeInTheDocument()
  })

  it('shows no issues for a line that clears every rule and the accent-phrase requirement', async () => {
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    const testLineInput = await screen.findByLabelText('Test a line')
    await userEvent.type(testLineInput, 'Every day, *finally organized*.')

    expect(await screen.findByText('No issues.')).toBeInTheDocument()
  })

  it('flags a missing accent phrase when the kit requires one', async () => {
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    const testLineInput = await screen.findByLabelText('Test a line')
    await userEvent.type(testLineInput, 'Every day, finally organized.')

    expect(await screen.findByText(/found 0/)).toBeInTheDocument()
  })

  it('labels the two wordmark slots by their own color and which frame they land on', async () => {
    render(<BrandKitForm projectId="proj-1" token="tok" />)
    await screen.findByLabelText('Brand kit')

    expect(screen.getByText('Wordmark · dark')).toBeInTheDocument()
    expect(screen.getByText('for light frames')).toBeInTheDocument()
    expect(screen.getByText('Wordmark · light')).toBeInTheDocument()
    expect(screen.getByText('for dark frames')).toBeInTheDocument()
  })

  it('disables kit fields and hides management actions for a role without creative.manage (REVIEWER)', async () => {
    mockCan.mockReturnValue(false)
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    await screen.findByLabelText('Brand kit')

    expect(screen.getByLabelText('Name')).toBeDisabled()
    expect(screen.getByLabelText('Test a line')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save kit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New kit' })).not.toBeInTheDocument()
  })

  it('asks only for a Name and derives the slug client-side', async () => {
    ;(apiPost as Mock).mockResolvedValue(kit({ id: 'kit-2', slug: 'holiday-drop', name: 'Holiday Drop!' }))
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    await userEvent.click(await screen.findByRole('button', { name: 'New kit' }))
    expect(screen.queryByLabelText('Slug')).not.toBeInTheDocument()
    await userEvent.type(document.getElementById('new-kit-name')!, 'Holiday Drop!')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        expect.stringContaining('/brand-kits'),
        { slug: 'holiday-drop', name: 'Holiday Drop!' },
        'tok',
      ),
    )
    expect(toastSpy).toHaveBeenCalledWith('Brand kit created')
  })

  it('retries a duplicate-slug conflict with a numeric suffix', async () => {
    const conflict = Object.assign(new Error('slug taken'), { status: 409 })
    ;(apiPost as Mock)
      .mockImplementationOnce(() => Promise.reject(conflict))
      .mockImplementationOnce(() => Promise.resolve(kit({ id: 'kit-3', slug: 'summer-drop-2', name: 'Summer Drop' })))
    render(<BrandKitForm projectId="proj-1" token="tok" />)

    await userEvent.click(await screen.findByRole('button', { name: 'New kit' }))
    await userEvent.type(document.getElementById('new-kit-name')!, 'Summer Drop')
    await userEvent.click(screen.getByRole('button', { name: 'Create' }))

    await waitFor(() =>
      expect(apiPost).toHaveBeenNthCalledWith(
        1,
        expect.stringContaining('/brand-kits'),
        { slug: 'summer-drop', name: 'Summer Drop' },
        'tok',
      ),
    )
    await waitFor(() =>
      expect(apiPost).toHaveBeenNthCalledWith(
        2,
        expect.stringContaining('/brand-kits'),
        { slug: 'summer-drop-2', name: 'Summer Drop' },
        'tok',
      ),
    )
    expect(toastSpy).toHaveBeenCalledWith('Brand kit created')
  })
})
