import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import { clearAllSidebarCaches } from '@/lib/workflows'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiPatch: vi.fn(),
  apiPut: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

vi.mock('@/contexts/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'user-1', name: 'Test User', email: 'test@example.com' },
    accessToken: 'test-token',
    loading: false,
  }),
}))

import { apiGet } from '@/lib/api'
import { AssetLibraryGrid, type AreaAsset } from './AssetLibraryGrid'

const MARKETING_WORKFLOW = {
  id: 'wf-marketing',
  projectId: 'proj-1',
  name: 'MARKETING',
  enabled: true,
  kind: 'LIFECYCLE',
  sidebarEnabled: true,
  area: 'MARKETING',
  slug: 'MARKETING',
  noun: 'Post',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z',
}

const MARKETING_VIEW = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'MARKETING',
  defaultView: 'active',
  version: 1,
  types: ['instagram_post'],
  statuses: [
    { id: 'DRAFT', label: 'Draft', category: 'open' },
    { id: 'IN_REVIEW', label: 'In Review', category: 'in_progress' },
  ],
  transitions: [],
}

function asset(overrides: Partial<AreaAsset> & { workItem?: Partial<AreaAsset['workItem']> } = {}): AreaAsset {
  const { workItem, ...rest } = overrides
  return {
    assetId: 'asset-1',
    previewUrl: 'https://storage.example/asset-1.png',
    contentType: 'image/png',
    sizeBytes: 1024,
    uploadedAt: '2026-08-01T10:00:00Z',
    ...rest,
    workItem: {
      id: 'wi-1',
      displayId: 'MK-1',
      title: 'Launch teaser',
      status: 'DRAFT',
      workflow: 'MARKETING',
      ...workItem,
    },
  }
}

const IMAGE_ASSETS: AreaAsset[] = [
  asset(),
  asset({
    assetId: 'asset-2',
    previewUrl: 'https://storage.example/asset-2.jpg',
    contentType: 'image/jpeg',
    workItem: { id: 'wi-2', displayId: 'MK-2', title: 'Product shot', status: 'IN_REVIEW', workflow: 'MARKETING' },
  }),
]

const VIDEO_ASSET = asset({
  assetId: 'asset-3',
  previewUrl: 'https://storage.example/asset-3.mp4',
  contentType: 'video/mp4',
  workItem: { id: 'wi-3', displayId: 'MK-3', title: 'Teaser cut', status: 'DRAFT', workflow: 'MARKETING' },
})

/**
 * One apiGet stub for the three calls the grid fans out: the sidebar workflow list (noun + workflow
 * filter options), the WorkflowView (status filter options), and the Area asset library itself.
 */
function stubApi(assetsFor: (query: URLSearchParams) => AreaAsset[]) {
  ;(apiGet as Mock).mockImplementation((path: string) => {
    if (path.includes('/areas/')) {
      const query = new URLSearchParams(path.split('?')[1] ?? '')
      return Promise.resolve(assetsFor(query))
    }
    if (path.includes('/workflows/by-slug/')) return Promise.resolve(MARKETING_VIEW)
    if (path.includes('/workflows?')) return Promise.resolve([MARKETING_WORKFLOW])
    return Promise.resolve([])
  })
}

function renderGrid() {
  return render(<AssetLibraryGrid projectId="proj-1" area="MARKETING" />)
}

describe('AssetLibraryGrid', () => {
  beforeEach(() => {
    clearAllSidebarCaches()
    localStorage.clear()
    ;(apiGet as Mock).mockReset()
  })

  // [auto] The grid renders thumbnails for every asset and click-through opens the owning work item
  it('renders a thumbnail for every asset', async () => {
    stubApi(() => IMAGE_ASSETS)
    renderGrid()

    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(2))
    expect(screen.getByAltText('Launch teaser')).toHaveAttribute('src', 'https://storage.example/asset-1.png')
    expect(screen.getByAltText('Product shot')).toHaveAttribute('src', 'https://storage.example/asset-2.jpg')
  })

  it('links each thumbnail to its owning work item detail route', async () => {
    stubApi(() => IMAGE_ASSETS)
    renderGrid()

    const tile = await screen.findByRole('link', { name: /launch teaser/i })
    // area + pluralized noun from the owning workflow, built by the shared path helper.
    expect(tile).toHaveAttribute('href', '/app/projects/proj-1/marketing/posts/MK-1')
    expect(screen.getByRole('link', { name: /product shot/i })).toHaveAttribute(
      'href',
      '/app/projects/proj-1/marketing/posts/MK-2',
    )
  })

  it('renders a video as a poster frame with a video badge rather than an image', async () => {
    stubApi(() => [VIDEO_ASSET])
    renderGrid()

    const tile = await screen.findByRole('link', { name: /teaser cut/i })
    expect(within(tile).queryByRole('img')).not.toBeInTheDocument()
    expect(tile.querySelector('video')).toHaveAttribute('src', 'https://storage.example/asset-3.mp4')
    expect(within(tile).getByText('Video')).toBeInTheDocument()
  })

  it('shows the owning work item id and status on each tile', async () => {
    stubApi(() => IMAGE_ASSETS)
    renderGrid()

    expect(await screen.findByText('MK-1')).toBeInTheDocument()
    const tile = await screen.findByRole('link', { name: /product shot/i })
    await waitFor(() => expect(within(tile).getByText('In Review')).toBeInTheDocument())
  })

  // [auto] Filtering by media type narrows the grid
  it('re-queries with mediaType=video and lists only videos when the media filter is set', async () => {
    stubApi((query) =>
      query.get('mediaType') === 'video' ? [VIDEO_ASSET] : [...IMAGE_ASSETS, VIDEO_ASSET],
    )
    renderGrid()

    await waitFor(() => expect(screen.getAllByRole('link')).toHaveLength(3))

    fireEvent.change(screen.getByLabelText('Media type'), { target: { value: 'video' } })

    await waitFor(() => expect(screen.getAllByRole('link')).toHaveLength(1))
    expect(screen.getByRole('link', { name: /teaser cut/i })).toBeInTheDocument()
    expect(screen.queryByAltText('Launch teaser')).not.toBeInTheDocument()

    const assetCalls = (apiGet as Mock).mock.calls
      .map((c) => c[0] as string)
      .filter((p) => p.includes('/areas/'))
    expect(assetCalls.some((p) => p.includes('mediaType=video'))).toBe(true)
  })

  it('re-queries with the selected owning workflow', async () => {
    stubApi(() => IMAGE_ASSETS)
    renderGrid()
    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(2))

    fireEvent.change(await screen.findByLabelText('Workflow'), { target: { value: 'MARKETING' } })

    await waitFor(() => {
      const calls = (apiGet as Mock).mock.calls.map((c) => c[0] as string)
      expect(calls.some((p) => p.includes('/areas/') && p.includes('workflow=MARKETING'))).toBe(true)
    })
  })

  it('re-queries with the selected owning item status', async () => {
    stubApi(() => IMAGE_ASSETS)
    renderGrid()

    // Status options come from the Area's WorkflowViews, so wait for them to land.
    const statusFilter = await screen.findByLabelText('Status')
    await waitFor(() => expect(within(statusFilter).getByText('In Review')).toBeInTheDocument())

    fireEvent.change(statusFilter, { target: { value: 'IN_REVIEW' } })

    await waitFor(() => {
      const calls = (apiGet as Mock).mock.calls.map((c) => c[0] as string)
      expect(calls.some((p) => p.includes('/areas/') && p.includes('status=IN_REVIEW'))).toBe(true)
    })
  })

  it('re-queries with an uploadedAfter bound when an upload-date window is chosen', async () => {
    stubApi(() => IMAGE_ASSETS)
    renderGrid()
    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(2))

    fireEvent.change(screen.getByLabelText('Uploaded'), { target: { value: '7' } })

    await waitFor(() => {
      const calls = (apiGet as Mock).mock.calls.map((c) => c[0] as string)
      expect(calls.some((p) => p.includes('/areas/') && p.includes('uploadedAfter='))).toBe(true)
    })
  })

  it('renders a skeleton while the first page is loading', () => {
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/areas/')) return new Promise(() => {})
      return Promise.resolve([])
    })
    renderGrid()

    expect(screen.getByTestId('asset-grid-skeleton')).toBeInTheDocument()
    expect(screen.queryByText(/no assets/i)).not.toBeInTheDocument()
  })

  it('renders the empty state rather than a blank grid when the library is empty', async () => {
    stubApi(() => [])
    renderGrid()

    expect(await screen.findByText('No assets yet')).toBeInTheDocument()
    expect(screen.queryByTestId('asset-grid')).not.toBeInTheDocument()
  })

  it('surfaces a load failure instead of an empty state', async () => {
    ;(apiGet as Mock).mockImplementation((path: string) => {
      if (path.includes('/areas/')) return Promise.reject(new Error('boom'))
      return Promise.resolve([])
    })
    renderGrid()

    expect(await screen.findByText(/could not load the asset library/i)).toBeInTheDocument()
    expect(screen.queryByText('No assets yet')).not.toBeInTheDocument()
  })

  // Pagination — the endpoint returns a bare array, so a full page implies there may be another.
  it('pages forward and back through full pages', async () => {
    const fullPage = Array.from({ length: 24 }, (_, i) =>
      asset({
        assetId: `a-${i}`,
        workItem: { id: `wi-${i}`, displayId: `MK-${i}`, title: `Post ${i}`, status: 'DRAFT', workflow: 'MARKETING' },
      }),
    )
    stubApi((query) => (query.get('page') === '0' ? fullPage : [asset({ assetId: 'a-last' })]))
    renderGrid()

    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(24))
    const next = screen.getByRole('button', { name: /next/i })
    expect(screen.getByRole('button', { name: /previous/i })).toBeDisabled()

    fireEvent.click(next)

    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(1))
    expect(screen.getByRole('button', { name: /next/i })).toBeDisabled()
    expect(screen.getByRole('button', { name: /previous/i })).toBeEnabled()

    fireEvent.click(screen.getByRole('button', { name: /previous/i }))
    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(24))
  })

  it('resets to the first page when a filter changes', async () => {
    const fullPage = Array.from({ length: 24 }, (_, i) =>
      asset({
        assetId: `a-${i}`,
        workItem: { id: `wi-${i}`, displayId: `MK-${i}`, title: `Post ${i}`, status: 'DRAFT', workflow: 'MARKETING' },
      }),
    )
    stubApi((query) => (query.get('page') === '0' ? fullPage : [asset({ assetId: 'a-last' })]))
    renderGrid()

    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(24))
    fireEvent.click(screen.getByRole('button', { name: /next/i }))
    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(1))

    fireEvent.change(screen.getByLabelText('Media type'), { target: { value: 'image' } })

    await waitFor(() => {
      const calls = (apiGet as Mock).mock.calls.map((c) => c[0] as string)
      expect(calls.some((p) => p.includes('mediaType=image') && p.includes('page=0'))).toBe(true)
    })
  })
})
