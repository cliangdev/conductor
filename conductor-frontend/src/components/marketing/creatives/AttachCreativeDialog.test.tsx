import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  apiErrorMessage: (_err: unknown, fallback: string) => fallback,
}))

const { toastSuccessSpy } = vi.hoisted(() => ({ toastSuccessSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/ui/toast')>()),
  toastSuccess: toastSuccessSpy,
}))

import { apiGet, apiPost } from '@/lib/api'
import { AttachCreativeDialog } from './AttachCreativeDialog'
import type { Creative } from './types'

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
    headline: 'Headline',
    body: 'Body',
    caption: 'Caption',
    altText: 'Alt',
    placements: [],
    sequenceKind: null,
    sequence: [],
    carouselRatio: null,
    typeOverrides: {},
    version: 3,
    createdBy: null,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    latestRenderId: 'render-1',
    latestRenderThumbnailUrl: 'https://storage.example/12a.png',
    ...overrides,
  }
}

function setup(overrides: Partial<React.ComponentProps<typeof AttachCreativeDialog>> = {}) {
  const onOpenChange = vi.fn()
  const onAttached = vi.fn()
  render(
    <AttachCreativeDialog
      open
      onOpenChange={onOpenChange}
      projectId="proj-1"
      workItemId="wi-1"
      token="tok"
      onAttached={onAttached}
      {...overrides}
    />,
  )
  return { onOpenChange, onAttached }
}

describe('AttachCreativeDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists only Creatives that have a SUCCEEDED render', async () => {
    ;(apiGet as Mock).mockResolvedValue([creative(), creative({ id: 'cr-2', displayId: '13a', latestRenderId: null })])
    setup()
    expect(await screen.findByText('12a')).toBeInTheDocument()
    expect(screen.queryByText('13a')).not.toBeInTheDocument()
  })

  it('attaches the picked Creative’s render to this Post and refreshes its media', async () => {
    ;(apiGet as Mock).mockResolvedValue([creative()])
    ;(apiPost as Mock).mockResolvedValue({
      assets: [{ assetId: 'a1', frameId: 'f1', placementKey: '4x5', sequenceIndex: null }],
      targetsUpdated: [{ targetId: 't1', platform: 'instagram', assetIds: ['a1'] }],
      targetsSkipped: [],
    })
    const { onAttached, onOpenChange } = setup()

    await userEvent.click(await screen.findByText('12a'))

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        '/api/v2/projects/proj-1/marketing/creatives/cr-1/attach',
        { renderId: 'render-1', workItemId: 'wi-1' },
        'tok',
      ),
    )
    await waitFor(() => expect(onAttached).toHaveBeenCalled())
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(toastSuccessSpy).toHaveBeenCalled()
  })

  it('says when nothing has a render yet', async () => {
    ;(apiGet as Mock).mockResolvedValue([])
    setup()
    expect(await screen.findByText(/No Creative has a render yet/)).toBeInTheDocument()
  })

  it('shows an error and does not call onAttached when attach fails', async () => {
    ;(apiGet as Mock).mockResolvedValue([creative()])
    ;(apiPost as Mock).mockRejectedValue(new Error('boom'))
    const { onAttached } = setup()

    await userEvent.click(await screen.findByText('12a'))

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(onAttached).not.toHaveBeenCalled()
  })
})
