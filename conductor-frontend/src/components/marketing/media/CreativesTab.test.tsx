import { describe, it, expect, vi, beforeEach, type Mock } from 'vitest'
import { render as rtl, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/components/marketing/creatives/types', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/components/marketing/creatives/types')>()),
  getCreative: vi.fn(),
  listCreatives: vi.fn(),
  listCreativePhotos: vi.fn(),
  requestCreativeRender: vi.fn(),
}))

import { getCreative, listCreativePhotos, requestCreativeRender } from '@/components/marketing/creatives/types'
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
const clip = creative('22a', {
  name: 'Dinner clip',
  kind: 'CLIP',
  latestRenderId: null,
  latestRenderThumbnailUrl: null,
  clipMedia: { default: 'm-clip' },
})
const stillNoRender = creative('23a', { name: 'Needs render', latestRenderId: null, latestRenderThumbnailUrl: null })
const ALL = [carousel, still, draft, unrendered, clip, stillNoRender]

const IG: ChooserDestination = { platform: 'instagram', format: 'feed', label: '@acme' }
const YT: ChooserDestination = { platform: 'youtube', format: 'feed', label: 'Acme Channel' }

function setup(props: Partial<React.ComponentProps<typeof CreativesTab>> = {}) {
  ;(getCreative as Mock).mockImplementation(async (_p: string, id: string) => {
    const c = ALL.find((x) => x.id === id)!
    if (c.kind === 'CLIP') return { ...c, latestRender: null }
    const frames = c.kind === 'CAROUSEL' ? CAROUSEL_FRAMES : [frame('9x16', null), frame('4x5', null), frame('1x1', null)]
    return { ...c, latestRender: render(`render-${c.displayId}`, frames) }
  })
  ;(listCreativePhotos as Mock).mockResolvedValue([
    { id: 'm-clip', url: 'https://storage.example/clip.mp4', posterUrl: 'https://storage.example/clip-poster.jpg', durationSeconds: 12 },
  ])
  ;(requestCreativeRender as Mock).mockResolvedValue(
    render('render-22a', [frame('9x16', null, { contentType: 'video/mp4', posterUrl: 'https://storage.example/p.jpg' })]),
  )
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
    expect(cardNames()).toEqual([
      'Choose Creative 18a Import carousel',
      'Choose Creative 19a Sunset',
      'Choose Creative 21a Not rendered',
      'Choose Creative 22a Dinner clip',
      'Choose Creative 23a Needs render',
    ])
    const stillBtn = screen.getByRole('button', { name: 'Choose Creative 21a Not rendered' })
    expect(stillBtn).toBeDisabled()
    expect(stillBtn).toHaveAttribute('title', 'Render it from Claude Code first')
    expect(screen.getAllByText('Not rendered yet')).toHaveLength(2)
    expect(screen.queryByText('No render yet')).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))
    expect(cardNames()).toHaveLength(6)
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
    expect(cardNames()).toEqual(['Choose Creative 20a Draft video', 'Choose Creative 22a Dinner clip'])
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

  it('marks the selected card with the brand colour and shows each frame at its own aspect ratio', async () => {
    setup()
    const card = screen.getByRole('button', { name: 'Choose Creative 18a Import carousel' })
    await userEvent.click(card)
    expect(card.className).toContain('border-primary')
    expect(card.className).toContain('ring-primary')
    expect(card.className).not.toContain('accent')
    const preview = screen.getByTestId('creatives-preview')
    await waitFor(() => expect(within(preview).getAllByTestId('frame-thumb').length).toBeGreaterThan(0))
    // 4:5 frames are 32px wide at the fixed 40px height, not a one-size portrait box.
    expect(within(preview).getAllByTestId('frame-thumb').every((t) => (t as HTMLElement).style.height === '40px')).toBe(true)
    expect((within(preview).getAllByTestId('frame-thumb')[0] as HTMLElement).style.width).toBe('32px')
  })

  it('shows an unrendered READY Clip with its poster and lets it be chosen', async () => {
    setup()
    const btn = screen.getByRole('button', { name: 'Choose Creative 22a Dinner clip' })
    expect(btn).toBeEnabled()
    await waitFor(() => expect(btn.querySelector('img')).toHaveAttribute('src', 'https://storage.example/clip-poster.jpg'))
    expect(await within(btn).findByText('12s')).toBeInTheDocument()
  })

  it('prepares a Clip once on selection and previews the returned frames', async () => {
    const { onUse } = setup()
    await userEvent.click(screen.getByRole('button', { name: 'Choose Creative 22a Dinner clip' }))
    const panel = screen.getByTestId('creatives-preview')
    // The 9:16 cut serves both Instagram feed (published as a Reel) and YouTube.
    expect(await within(panel).findAllByTestId('frame-thumb')).toHaveLength(2)
    expect(requestCreativeRender).toHaveBeenCalledTimes(1)
    expect(requestCreativeRender).toHaveBeenCalledWith('proj-1', 'cr-22a', { renderer: 'web' }, 'tok')
    await userEvent.click(screen.getByRole('button', { name: 'Use Creative 22a' }))
    expect(onUse.mock.calls[0]![0]).toMatchObject({ renderId: 'render-22a' })
  })

  it('shows the API error when preparing a Clip fails', async () => {
    setup()
    ;(requestCreativeRender as Mock).mockRejectedValue({ detail: 'Clip has no default media' })
    await userEvent.click(screen.getByRole('button', { name: 'Choose Creative 22a Dinner clip' }))
    expect(await screen.findByText('Clip has no default media')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Use Creative 22a' })).toBeDisabled()
  })

  it('counts a READY Clip with no render as ready, so Ready stays the default filter', () => {
    setup({ creatives: [clip, draft] })
    expect(cardNames()).toEqual(['Choose Creative 22a Dinner clip'])
    expect(screen.getByRole('button', { name: 'Ready' })).toHaveAttribute('aria-pressed', 'true')
  })
})
