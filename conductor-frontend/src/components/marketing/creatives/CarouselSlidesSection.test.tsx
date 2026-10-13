import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { CarouselSlidesSection } from './CarouselSlidesSection'
import type { CreativePhoto } from './types'

function img(id: string, w = 1080, h = 1350): CreativePhoto {
  return {
    id,
    projectId: 'p',
    label: id,
    contentType: 'image/jpeg',
    sizeBytes: 1,
    width: w,
    height: h,
    mediaKind: 'IMAGE',
    aiGenerated: false,
    checked: true,
    blocked: false,
    focal: {},
    uploadStatus: 'UPLOADED',
    url: `https://storage.example/${id}.jpg`,
    createdAt: '2026-01-01T00:00:00Z',
  } as CreativePhoto
}

const MEDIA = new Map([img('a'), img('b'), img('c'), img('wide', 1080, 1080)].map((m) => [m.id, m]))

function Harness({
  initial = { '4x5': ['a', 'b', 'c'], '9x16': ['a'] } as Record<string, string[]>,
  alt = [] as string[],
  onChangeSpy = vi.fn(),
}) {
  const [state, setState] = useState({ slides: initial, slideAltText: alt })
  return (
    <CarouselSlidesSection
      {...state}
      mediaById={MEDIA}
      canManage
      onAddImages={vi.fn()}
      onChange={(n) => {
        onChangeSpy(n)
        setState(n)
      }}
    />
  )
}

const order = () => screen.getAllByTestId(/^slide-row-/).map((r) => within(r).getByText(/^(a|b|c|wide)$/).textContent)

describe('CarouselSlidesSection', () => {
  it('steps with the buttons and the arrow keys, showing "n / total"', async () => {
    render(<Harness />)
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('1 / 3')
    await userEvent.click(screen.getByRole('button', { name: 'Next slide' }))
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('2 / 3')
    expect(screen.getByTestId('carousel-viewer-image')).toHaveAttribute('src', 'https://storage.example/b.jpg')

    const viewer = screen.getByTestId('carousel-viewer')
    fireEvent.keyDown(viewer, { key: 'ArrowRight' })
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('3 / 3')
    fireEvent.keyDown(viewer, { key: 'ArrowRight' })
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('3 / 3')
    fireEvent.keyDown(viewer, { key: 'ArrowLeft' })
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('2 / 3')
  })

  it('swipes with a pointer drag past the threshold', () => {
    render(<Harness />)
    const viewer = screen.getByTestId('carousel-viewer')
    fireEvent.pointerDown(viewer, { clientX: 200 })
    fireEvent.pointerUp(viewer, { clientX: 100 })
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('2 / 3')
    fireEvent.pointerDown(viewer, { clientX: 100 })
    fireEvent.pointerUp(viewer, { clientX: 110 })
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('2 / 3')
  })

  it('switches placement and shows that placement\'s slides', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('tab', { name: /9:16/ }))
    expect(screen.getByTestId('carousel-position')).toHaveTextContent('1 / 1')
    await userEvent.click(screen.getByRole('tab', { name: /1:1/ }))
    expect(screen.getByText(/No images for 1:1/)).toBeInTheDocument()
  })

  it('reorders with the up/down buttons and by drag and drop; alt text travels with the slide', async () => {
    const spy = vi.fn()
    render(<Harness alt={['alt-a', 'alt-b', 'alt-c']} onChangeSpy={spy} />)
    await userEvent.click(screen.getByRole('button', { name: 'Move slide 1 down' }))
    expect(order()).toEqual(['b', 'a', 'c'])
    expect(spy.mock.lastCall![0].slideAltText).toEqual(['alt-b', 'alt-a', 'alt-c'])
    await userEvent.click(screen.getByRole('button', { name: 'Move slide 3 up' }))
    expect(order()).toEqual(['b', 'c', 'a'])

    fireEvent.dragStart(screen.getByTestId('slide-row-2'))
    fireEvent.drop(screen.getByTestId('slide-row-0'))
    expect(order()).toEqual(['a', 'b', 'c'])
  })

  it('edits per-slide alt text and removes a slide with its alt text', async () => {
    const spy = vi.fn()
    render(<Harness alt={['x', 'y']} onChangeSpy={spy} />)
    await userEvent.type(screen.getByLabelText('Alt text for slide 3'), 'z')
    expect(spy.mock.lastCall![0].slideAltText).toEqual(['x', 'y', 'z'])
    await userEvent.click(screen.getByRole('button', { name: 'Remove slide 1' }))
    expect(order()).toEqual(['b', 'c'])
    expect(spy.mock.lastCall![0].slideAltText).toEqual(['y', 'z'])
  })

  it('keeps alt text past this placement\'s last slide when reordering or removing (it belongs to a longer placement)', async () => {
    const spy = vi.fn()
    render(<Harness initial={{ '4x5': ['a', 'b'], '9x16': ['a', 'b', 'c'] }} alt={['x', 'y', 'z']} onChangeSpy={spy} />)
    await userEvent.click(screen.getByRole('button', { name: 'Move slide 1 down' }))
    expect(spy.mock.lastCall![0].slideAltText).toEqual(['y', 'x', 'z'])
    await userEvent.click(screen.getByRole('button', { name: 'Remove slide 2' }))
    expect(spy.mock.lastCall![0].slideAltText).toEqual(['y', 'z'])
  })

  it('warns, without blocking, when an image does not match the placement aspect', () => {
    render(<Harness initial={{ '4x5': ['a', 'wide'] }} />)
    expect(screen.queryByTestId('slide-aspect-warning-0')).not.toBeInTheDocument()
    expect(screen.getByTestId('slide-aspect-warning-1')).toHaveTextContent('1080×1080')
  })

  it('shows server violations as given', () => {
    render(
      <CarouselSlidesSection slides={{}} slideAltText={[]} mediaById={MEDIA} canManage onAddImages={vi.fn()} onChange={vi.fn()} violations={['slides["4x5"][1] must be an IMAGE']} />,
    )
    expect(screen.getByText('slides["4x5"][1] must be an IMAGE')).toBeInTheDocument()
  })
})
