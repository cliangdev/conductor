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

vi.mock('@/components/ui/toast', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/ui/toast')>()),
  toastSuccess: vi.fn(),
  toastError: vi.fn(),
}))

vi.mock('@/contexts/PermissionsContext', () => ({
  useCan: () => true,
  usePermissions: () => ({ role: 'ADMIN', loading: false, can: () => true, refresh: vi.fn() }),
}))

import { apiGet, apiPost } from '@/lib/api'
import { PostContentSection } from './PostContentSection'
import type { WorkflowView } from '@/types/workItem'

const VIEW: WorkflowView = {
  slug: 'MARKETING',
  noun: 'Post',
  area: 'marketing',
  defaultView: 'list',
  version: 1,
  types: ['POST'],
  assetTypes: ['post_media'],
  statuses: [{ id: 'DRAFT', label: 'Draft', category: 'open', initial: true }],
  transitions: [],
}

function creative(overrides: Record<string, unknown> = {}) {
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
    kind: 'STILL',
    layout: 'stacked',
    theme: 'dark',
    photoId: null,
    photoUrl: null,
    focalOverride: null,
    headline: 'Headline',
    body: 'Body',
    caption: 'Caption',
    altText: 'Alt',
    placements: [],
    sequenceKind: null,
    sequence: [],
    carouselRatio: null,
    typeOverrides: {},
    lockup: 'plain',
    version: 3,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    latestRenderId: 'render-1',
    latestRenderThumbnailUrl: 'https://storage.example/12a.png',
    ...overrides,
  }
}

function setup(overrides: Partial<React.ComponentProps<typeof PostContentSection>> = {}) {
  const onAssetsChanged = vi.fn()
  render(
    <PostContentSection
      projectId="proj-1"
      workItemId="wi-1"
      token="tok"
      status="DRAFT"
      workflowView={VIEW}
      description="Caption text"
      assets={[]}
      canEdit
      onCaptionSaved={vi.fn()}
      onAssetsChanged={onAssetsChanged}
      {...overrides}
    />,
  )
  return { onAssetsChanged }
}

describe('PostContentSection — From a creative', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows a "From a creative" tile in the media strip when the user can edit', () => {
    setup()
    expect(screen.getByRole('button', { name: /from a creative/i })).toBeInTheDocument()
  })

  it('hides the tile for a viewer who cannot edit', () => {
    setup({ canEdit: false })
    expect(screen.queryByRole('button', { name: /from a creative/i })).not.toBeInTheDocument()
  })

  function mockCreatives(list: ReturnType<typeof creative>[]) {
    ;(apiGet as Mock).mockImplementation(async (url: string) => {
      const m = /creatives\/([^/?]+)$/.exec(url)
      if (m) {
        const c = list.find((x) => x.id === m[1])!
        return {
          ...c,
          latestRender: {
            id: c.latestRenderId,
            state: 'SUCCEEDED',
            previewOnly: false,
            creativeVersion: 1,
            requestedAt: '2026-01-01T00:00:00Z',
            frames: [],
          },
        }
      }
      return list
    })
  }

  it('opens the Creative picker, attaches the chosen one, and refreshes assets', async () => {
    mockCreatives([creative()])
    ;(apiPost as Mock).mockResolvedValue({
      assets: [{ assetId: 'a1', frameId: 'f1', placementKey: '4x5', sequenceIndex: null }],
      targetsUpdated: [{ targetId: 't1', platform: 'instagram', assetIds: ['a1'] }],
      targetsSkipped: [],
    })
    const { onAssetsChanged } = setup()

    await userEvent.click(screen.getByRole('button', { name: /from a creative/i }))
    await userEvent.click(await screen.findByRole('button', { name: /Choose Creative 12a/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Use Creative 12a' }))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/marketing/creatives/cr-1/attach',
        { renderId: 'render-1', workItemId: 'wi-1' },
        'tok',
      ),
    )
    await waitFor(() => expect(onAssetsChanged).toHaveBeenCalled())
  })

  it('does not let an unrendered Creative be chosen', async () => {
    mockCreatives([creative(), creative({ id: 'cr-2', displayId: '13a', latestRenderId: null })])
    setup()
    await userEvent.click(screen.getByRole('button', { name: /from a creative/i }))
    expect(await screen.findByRole('button', { name: /Choose Creative 12a/ })).toBeEnabled()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    expect(screen.getByRole('button', { name: /Choose Creative 13a/ })).toBeDisabled()
  })
})
