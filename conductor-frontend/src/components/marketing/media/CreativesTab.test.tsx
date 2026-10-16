import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render as rtl, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/components/marketing/creatives/types', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/marketing/creatives/types')>()),
  getCreative: vi.fn(),
  listCreatives: vi.fn(),
}))

import { getCreative } from '@/components/marketing/creatives/types'
import { CreativesTab, type ChooserDestination } from './CreativesTab'
import { CAROUSEL_FRAMES, creative, frame, render } from './creativeFixtures'

const carousel = creative('18a', {
  name: 'Import carousel',
  kind: 'CAROUSEL',
  headline: 'Shop once',
  slides: { '4x5': ['a', 'b', 'c'], '1x1': ['a', 'b', 'c'], '9x16': ['a', 'b', 'c'] },
})
const still = creative('19a', { name: 'Sunset', headline: 'Golden hour' })
const draft = creative('20a', { name: 'Draft video', kind: 'CLIP', state: 'DRAFT' })
const unrendered = creative('21a', { name: 'Not rendered', latestRenderId: null, latestRenderThumbnailUrl: null })
const ALL = [carousel, still, draft, unrendered]

const IG: ChooserDestination = { platform: 'instagram', format: 'feed', label: '@acme' }
const YT: ChooserDestination = { platform: 'youtube', format: 'feed', label: 'Acme Channel' }

function setup(props: Partial<React.ComponentProps<typeof CreativesTab>> = {}) {
  ;(getCreative as Mock).mockImplementation(async (_p: string, id: string) => {
    const c = ALL.find((x) => x.id === id)!
    const frames = c.kind === 'CAROUSEL' ? CAROUSEL_FRAMES : [frame('9x16', null), frame('4x5', null), frame('1x1', null)]
    return { ...c, latestRender: render(`render-${c.displayId}`, frames) }
  })
  const onUse = vi.fn()
  const onCancel = vi.fn()
  rtl(
    <CreativesTab
      creatives={ALL}
      projectId="proj-1"
      token="tok"
      destinations={[IG, YT]}
      captionEmpty
      onUse={onUse}
      onCancel={onCancel}
      {...props}
    />,
  )
  return { onUse, onCancel }
}

const cardNames = () => screen.getAllByRole('button', { name: /^Choose Creative/ }).map((b) => b.getAttribute('aria-label'))

describe('CreativesTab', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows Ready creatives by default and everything under All; unrendered ones are disabled', async () => {
    setup()
    expect(cardNames()).toEqual(['Choose Creative 18a Import carousel', 'Choose Creative 19a Sunset', 'Choose Creative 21a Not rendered'])
    expect(screen.getByRole('button', { name: 'Choose Creative 21a Not rendered' })).toBeDisabled()
    expect(screen.getByText('No render yet')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    expect(cardNames()).toHaveLength(4)
  })

  it('searches by name, display ID and headline', async () => {
    setup()
    const search = screen.getByLabelText('Search Creatives')
    await userEvent.type(search, 'golden')
    expect(cardNames()).toEqual(['Choose Creative 19a Sunset'])
    await userEvent.clear(search)
    await userEvent.type(search, '18a')
    expect(cardNames()).toEqual(['Choose Creative 18a Import carousel'])
    await userEvent.clear(search)
    await userEvent.type(search, 'carousel')
    expect(cardNames()).toEqual(['Choose Creative 18a Import carousel'])
  })

  it('filters by kind, treating clips and motion as Video', async () => {
    setup()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    await userEvent.click(screen.getByRole('button', { name: 'Video' }))
    expect(cardNames()).toEqual(['Choose Creative 20a Draft video'])
    await userEvent.click(screen.getByRole('button', { name: 'Video' }))
    await userEvent.click(screen.getByRole('button', { name: 'Carousel' }))
    expect(cardNames()).toEqual(['Choose Creative 18a Import carousel'])
  })

  it('previews the size each destination gets and warns where there is none', async () => {
    setup()
    expect(screen.getByText(/Pick a Creative to see/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Choose Creative 18a Import carousel' }))
    const panel = screen.getByTestId('creatives-preview')
    expect(await within(panel).findByText('4:5 ×3')).toBeInTheDocument()
    expect(within(panel).getByText('@acme')).toBeInTheDocument()
    expect(within(panel).getAllByTestId('frame-strip')).toHaveLength(1)
    expect(within(panel).getByTestId('creative-warning')).toHaveTextContent('YouTube takes video only — this Creative is a carousel.')
    // One detail call for the selected Creative, none for the other cards.
    expect(getCreative).toHaveBeenCalledTimes(1)
  })

  it('lists the Creative’s sizes when no destination is picked yet', async () => {
    setup({ destinations: [] })
    await userEvent.click(screen.getByRole('button', { name: 'Choose Creative 18a Import carousel' }))
    expect(await screen.findByText(/No destinations picked yet/)).toBeInTheDocument()
    expect(screen.getByText('9:16')).toBeInTheDocument()
    expect(screen.getByText('4:5')).toBeInTheDocument()
  })

  it('ticks the caption box only when the caption is empty', () => {
    setup({ captionEmpty: true })
    expect(screen.getByRole('checkbox', { name: /Use the Creative’s caption/ })).toBeChecked()
  })

  it('leaves the caption box unticked when a caption is already written', () => {
    setup({ captionEmpty: false })
    expect(screen.getByRole('checkbox', { name: /Use the Creative’s caption/ })).not.toBeChecked()
  })

  it('hands back the Creative, its render, frames and the caption choice', async () => {
    const { onUse } = setup()
    expect(screen.getByRole('button', { name: 'Use Creative' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Choose Creative 18a Import carousel' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Use Creative 18a' }))
    expect(onUse).toHaveBeenCalledTimes(1)
    const choice = onUse.mock.calls[0]![0]
    expect(choice).toMatchObject({ renderId: 'render-18a', useCaption: true })
    expect(choice.creative.id).toBe('cr-18a')
    expect(choice.frames).toHaveLength(9)
  })

  it('refuses a Creative whose only render is a preview', async () => {
    setup()
    ;(getCreative as Mock).mockResolvedValue({ ...still, latestRender: render('r', [frame('4x5', null)], { previewOnly: true }) })
    await userEvent.click(screen.getByRole('button', { name: 'Choose Creative 19a Sunset' }))
    expect(await screen.findByText(/Only a preview was rendered/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Use Creative 19a' })).toBeDisabled())
  })
})
