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

  it('opens a picker of Creatives with a render, attaches the picked one, and refreshes assets', async () => {
    ;(apiGet as Mock).mockResolvedValue([creative()])
    ;(apiPost as Mock).mockResolvedValue({
      assets: [{ assetId: 'a1', frameId: 'f1', placementKey: '4x5', sequenceIndex: null }],
      targetsUpdated: [{ targetId: 't1', platform: 'instagram', assetIds: ['a1'] }],
      targetsSkipped: [],
    })
    const { onAssetsChanged } = setup()

    await userEvent.click(screen.getByRole('button', { name: /from a creative/i }))
    expect(await screen.findByText('12a')).toBeInTheDocument()

    await userEvent.click(screen.getByText('12a'))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/marketing/creatives/cr-1/attach',
        { renderId: 'render-1', workItemId: 'wi-1' },
        'tok',
      ),
    )
    await waitFor(() => expect(onAssetsChanged).toHaveBeenCalled())
  })

  it('only lists Creatives that have a render', async () => {
    ;(apiGet as Mock).mockResolvedValue([creative(), creative({ id: 'cr-2', displayId: '13a', latestRenderId: null })])
    setup()
    await userEvent.click(screen.getByRole('button', { name: /from a creative/i }))
    expect(await screen.findByText('12a')).toBeInTheDocument()
    expect(screen.queryByText('13a')).not.toBeInTheDocument()
  })
})

describe('PostContentSection — From media', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  function library() {
    const base = {
      projectId: 'proj-1',
      contentType: 'image/png',
      sizeBytes: 10,
      mediaKind: 'IMAGE',
      aiGenerated: false,
      checked: true,
      blocked: false,
      focal: {},
      uploadStatus: 'UPLOADED',
      warnings: [],
      createdAt: '2026-01-01T00:00:00Z',
    }
    return [
      { ...base, id: 'm-1', label: 'first pic', url: 'https://storage.example/1.png' },
      { ...base, id: 'm-2', label: 'second pic', url: 'https://storage.example/2.png' },
      { ...base, id: 'm-3', label: 'a track', mediaKind: 'AUDIO', contentType: 'audio/mpeg', durationSeconds: 5, url: 'https://storage.example/3.mp3' },
    ]
  }

  it('hides "From media" for a viewer who cannot edit', () => {
    setup({ canEdit: false })
    expect(screen.queryByRole('button', { name: /from media/i })).not.toBeInTheDocument()
  })

  it('links the picked media in click order, then refreshes the strip', async () => {
    ;(apiGet as Mock).mockResolvedValue(library())
    ;(apiPost as Mock).mockResolvedValue([{ id: 'a1' }, { id: 'a2' }])
    const { onAssetsChanged } = setup()

    await userEvent.click(screen.getByRole('button', { name: /from media/i }))
    // Posts can't carry audio: no Audio tab, and the audio item is not listed.
    expect(await screen.findByRole('tab', { name: 'Images' })).toBeInTheDocument()
    expect(screen.queryByRole('tab', { name: 'Audio' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Select a track' })).not.toBeInTheDocument()
    await userEvent.click(await screen.findByRole('button', { name: 'Select second pic' }))
    await userEvent.click(screen.getByRole('button', { name: 'Select first pic' }))
    await userEvent.click(screen.getByRole('button', { name: 'Add 2' }))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/work-items/wi-1/assets/from-media',
        { mediaIds: ['m-2', 'm-1'] },
        'tok',
      ),
    )
    await waitFor(() => expect(onAssetsChanged).toHaveBeenCalled())
  })

  it('says why when the server refuses the link', async () => {
    ;(apiGet as Mock).mockResolvedValue(library())
    ;(apiPost as Mock).mockRejectedValue(new Error('nope'))
    const { onAssetsChanged } = setup()
    await userEvent.click(screen.getByRole('button', { name: /from media/i }))
    await userEvent.click(await screen.findByRole('button', { name: 'Select first pic' }))
    await userEvent.click(screen.getByRole('button', { name: 'Add 1' }))
    expect(await screen.findByText('Could not add this media')).toBeInTheDocument()
    expect(onAssetsChanged).not.toHaveBeenCalled()
  })
})
