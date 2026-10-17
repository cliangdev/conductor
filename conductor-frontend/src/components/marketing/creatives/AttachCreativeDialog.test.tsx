import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render as rtl, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/components/marketing/creatives/types', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/marketing/creatives/types')>()),
  listCreatives: vi.fn(),
  getCreative: vi.fn(),
  listCreativePhotos: vi.fn(),
  requestCreativeRender: vi.fn(),
  attachCreativeRender: vi.fn(),
}))

const { toastSuccessSpy } = vi.hoisted(() => ({ toastSuccessSpy: vi.fn() }))
vi.mock('@/components/ui/toast', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/ui/toast')>()),
  toastSuccess: toastSuccessSpy,
}))

import {
  attachCreativeRender,
  getCreative,
  listCreativePhotos,
  listCreatives,
  requestCreativeRender,
} from '@/components/marketing/creatives/types'
import { CAROUSEL_FRAMES, creative, frame, render } from '@/components/marketing/media/creativeFixtures'
import { AttachCreativeDialog } from './AttachCreativeDialog'

const still = creative('12a', { name: 'Paste a link', headline: 'Paste it' })
const draft = creative('13a', { name: 'Draft one', state: 'DRAFT' })
const carousel = creative('14a', {
  name: 'Import carousel',
  kind: 'CAROUSEL',
  latestRenderId: null,
  latestRenderThumbnailUrl: null,
  slides: { '4x5': ['a', 'b', 'c'] },
})
const ALL = [still, draft, carousel]

const IG = { platform: 'instagram' as const, format: 'feed', label: '@acme' }

function setup(overrides: Partial<React.ComponentProps<typeof AttachCreativeDialog>> = {}, list = ALL) {
  ;(listCreatives as Mock).mockResolvedValue(list)
  ;(listCreativePhotos as Mock).mockResolvedValue([])
  ;(getCreative as Mock).mockImplementation(async (_p: string, id: string) => {
    const c = ALL.find((x) => x.id === id)!
    if (c.kind === 'CAROUSEL') return { ...c, latestRender: null }
    return { ...c, latestRender: render(`render-${c.displayId}`, [frame('9x16', null), frame('4x5', null), frame('1x1', null)]) }
  })
  ;(requestCreativeRender as Mock).mockResolvedValue(render('render-14a', CAROUSEL_FRAMES))
  const onOpenChange = vi.fn()
  const onAttached = vi.fn()
  rtl(
    <AttachCreativeDialog
      open
      onOpenChange={onOpenChange}
      projectId="proj-1"
      workItemId="wi-1"
      token="tok"
      destinations={[IG]}
      onAttached={onAttached}
      {...overrides}
    />,
  )
  return { onOpenChange, onAttached }
}

describe('AttachCreativeDialog', () => {
  beforeEach(() => vi.clearAllMocks())

  it('offers search, Ready/All and kind filters over the project’s Creatives', async () => {
    setup()
    expect(await screen.findByRole('button', { name: /Choose Creative 12a/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Choose Creative 13a/ })).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    expect(screen.getByRole('button', { name: /Choose Creative 13a/ })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('Search Creatives'), 'import')
    expect(screen.getAllByRole('button', { name: /^Choose Creative/ })).toHaveLength(1)
    expect(screen.getByRole('button', { name: /Choose Creative 14a/ })).toBeInTheDocument()
  })

  it('previews the size the Post’s real destinations will get, and has no caption option', async () => {
    setup()
    await userEvent.click(await screen.findByRole('button', { name: /Choose Creative 12a/ }))
    const panel = screen.getByTestId('creatives-preview')
    expect(await within(panel).findByText('@acme')).toBeInTheDocument()
    expect(within(panel).getByText('4:5')).toBeInTheDocument()
    expect(screen.queryByText(/Creative’s caption/)).not.toBeInTheDocument()
  })

  it('attaches the chosen Creative’s render to this Post, refreshes its media and says what changed', async () => {
    ;(attachCreativeRender as Mock).mockResolvedValue({
      assets: [{ assetId: 'a1', frameId: 'f1', placementKey: '4x5', sequenceIndex: null }],
      targetsUpdated: [{ targetId: 't1', platform: 'instagram', assetIds: ['a1'] }],
      targetsSkipped: [
        { targetId: 't2', platform: 'facebook', reason: 'custom media' },
        { targetId: 't3', platform: 'tiktok', reason: 'custom media' },
      ],
    })
    const { onAttached, onOpenChange } = setup()

    await userEvent.click(await screen.findByRole('button', { name: /Choose Creative 12a/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Use Creative 12a' }))

    await waitFor(() =>
      expect(attachCreativeRender).toHaveBeenCalledWith('proj-1', 'cr-12a', { renderId: 'render-12a', workItemId: 'wi-1' }, 'tok'),
    )
    await waitFor(() => expect(onAttached).toHaveBeenCalled())
    expect(onOpenChange).toHaveBeenCalledWith(false)
    expect(toastSuccessSpy).toHaveBeenCalledWith(expect.stringContaining('2 destinations kept their own media'))
  })

  it('renders a Carousel on demand before attaching it', async () => {
    ;(attachCreativeRender as Mock).mockResolvedValue({ assets: [], targetsUpdated: [], targetsSkipped: [] })
    setup()
    await userEvent.click(await screen.findByRole('button', { name: /Choose Creative 14a/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Use Creative 14a' }))
    expect(requestCreativeRender).toHaveBeenCalledWith('proj-1', 'cr-14a', { renderer: 'web' }, 'tok')
    await waitFor(() =>
      expect(attachCreativeRender).toHaveBeenCalledWith('proj-1', 'cr-14a', { renderId: 'render-14a', workItemId: 'wi-1' }, 'tok'),
    )
  })

  it('shows an error and keeps the dialog open when attach fails', async () => {
    ;(attachCreativeRender as Mock).mockRejectedValue(new Error('boom'))
    const { onAttached, onOpenChange } = setup()

    await userEvent.click(await screen.findByRole('button', { name: /Choose Creative 12a/ }))
    await userEvent.click(await screen.findByRole('button', { name: 'Use Creative 12a' }))

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    expect(onAttached).not.toHaveBeenCalled()
    expect(onOpenChange).not.toHaveBeenCalledWith(false)
  })

  it('says when there are no Creatives', async () => {
    setup({}, [])
    expect(await screen.findByText('No Creatives yet')).toBeInTheDocument()
  })
})
