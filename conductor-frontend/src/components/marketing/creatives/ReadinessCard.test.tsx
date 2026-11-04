import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ReadinessCard, type ReadinessCardProps } from './ReadinessCard'
import { readinessCopy } from './readinessCopy'
import type { CreativeReadinessItem } from './types'

const item = (key: string, ok: boolean, blocking: boolean, message: string): CreativeReadinessItem => ({ key, ok, blocking, message })

function setup(items: CreativeReadinessItem[], over: Partial<ReadinessCardProps> = {}) {
  const props: ReadinessCardProps = {
    readiness: { ready: items.every((i) => i.ok || !i.blocking), items },
    state: 'DRAFT',
    canManage: true,
    onMarkReady: vi.fn(),
    ...over,
  }
  render(<ReadinessCard {...props} />)
  return props
}

describe('ReadinessCard header', () => {
  it('says the Creative is ready for a Post and offers Mark ready when nothing blocks', async () => {
    const props = setup([item('caption', true, true, 'caption is set'), item('state', false, true, 'Creative is still a draft')])
    expect(screen.getByTestId('readiness-title')).toHaveTextContent('Ready for a Post')
    expect(screen.getByTestId('readiness-subtitle')).toHaveTextContent(
      'Nothing blocks this Creative. Mark it ready, or attach it to a Post and it becomes ready on its own.',
    )
    const button = screen.getByRole('button', { name: 'Mark ready' })
    expect(button).toBeEnabled()
    await userEvent.click(button)
    expect(props.onMarkReady).toHaveBeenCalledTimes(1)
  })

  it('shows an archived Creative as archived, with no Mark ready', () => {
    setup([item('caption', true, true, 'caption is set')], { state: 'ARCHIVED' })
    expect(screen.getByTestId('readiness-title')).toHaveTextContent('Archived')
    expect(screen.getByTestId('readiness-title')).not.toHaveTextContent('Ready')
    expect(screen.getByTestId('readiness-subtitle')).toHaveTextContent('This Creative is archived.')
    expect(screen.queryByRole('button', { name: 'Mark ready' })).not.toBeInTheDocument()
  })

  it('counts what blocks, disables Mark ready, and ignores the state item', () => {
    setup([
      item('caption', false, true, 'caption is missing'),
      item('altText', false, true, 'alt text is missing'),
      item('state', false, true, 'Creative is still a draft'),
    ])
    expect(screen.getByTestId('readiness-title')).toHaveTextContent('2 things to fix before this can go on a Post')
    expect(screen.getByTestId('readiness-subtitle')).toHaveTextContent('Fix these, then mark it ready or attach it to a Post.')
    expect(screen.getByTestId('readiness-disc')).toHaveTextContent('2')
    expect(screen.getByRole('button', { name: 'Mark ready' })).toBeDisabled()
  })

  it('uses the singular for one blocker', () => {
    setup([item('caption', false, true, 'caption is missing')])
    expect(screen.getByTestId('readiness-title')).toHaveTextContent('1 thing to fix before this can go on a Post')
  })

  it('says Ready, with no button, once the Creative is READY', () => {
    setup([item('caption', true, true, 'caption is set')], { state: 'READY' })
    expect(screen.getByTestId('readiness-title')).toHaveTextContent(/^Ready$/)
    expect(screen.queryByRole('button', { name: 'Mark ready' })).not.toBeInTheDocument()
  })

  it('hides Mark ready, and every fix, from someone who cannot manage Creatives', () => {
    setup([item('caption', false, true, 'caption is missing')], { canManage: false, actions: { caption: vi.fn() } })
    expect(screen.queryByRole('button', { name: 'Mark ready' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add caption' })).not.toBeInTheDocument()
    expect(screen.getByText('Add a caption')).toBeInTheDocument()
  })

  it('never lists the state item as a row, to fix or passed', async () => {
    setup([item('caption', true, true, 'caption is set'), item('state', false, true, 'Creative is still a draft')])
    expect(screen.queryByText(/still a draft/i)).not.toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Show passed checks' }))
    expect(screen.queryByText(/still a draft/i)).not.toBeInTheDocument()
    expect(screen.getByText('1 check passed')).toBeInTheDocument()
  })
})

describe('ReadinessCard rows', () => {
  it('runs the row action for each fixable key', async () => {
    const actions = { altText: vi.fn(), caption: vi.fn(), slides: vi.fn(), photoChecked: vi.fn() }
    setup(
      [
        item('slides', false, true, 'no slides chosen: add at least one image'),
        item('caption', false, true, 'caption is missing: the post needs primary text'),
        item('altText', false, true, 'alt text is missing'),
        item('photoChecked', false, true, '10 of 10 slide images have not been checked for burned-in text'),
      ],
      { actions },
    )
    for (const [label, key] of [
      ['Choose slides', 'slides'],
      ['Add caption', 'caption'],
      ['Add alt text', 'altText'],
      ["I've checked them", 'photoChecked'],
    ] as const) {
      await userEvent.click(within(screen.getByTestId(`readiness-row-${key}`)).getByRole('button', { name: label }))
      expect(actions[key]).toHaveBeenCalledTimes(1)
    }
  })

  it('explains the photo check with the count and no button when no action is wired', () => {
    setup([item('photoChecked', false, true, '10 of 10 slide images have not been checked for burned-in text')])
    const row = screen.getByTestId('readiness-row-photoChecked')
    expect(row).toHaveTextContent('Look over 10 photos for text in the image')
    expect(row).toHaveTextContent('Signs, logos or watermarks inside a photo clash with the headline Conductor adds.')
    expect(within(row).queryByRole('button')).not.toBeInTheDocument()
  })

  it('disables a busy action', () => {
    setup([item('photoChecked', false, true, '1 of 1 slide images have not been checked')], {
      actions: { photoChecked: vi.fn() },
      busyKeys: ['photoChecked'],
    })
    expect(screen.getByRole('button', { name: "I've checked them" })).toBeDisabled()
  })

  it('lists non-blocking misses under Before you publish', () => {
    setup([
      item('aiDisclosure', false, false, "a slide is AI-generated: turn on the platform's AI-generated label when publishing"),
      item('altTextStale', false, false, 'slides 1, 3 alt text still quotes the old text'),
    ])
    expect(screen.getByText('Before you publish')).toBeInTheDocument()
    expect(screen.queryByText('To fix')).not.toBeInTheDocument()
    const ai = screen.getByTestId('readiness-row-aiDisclosure')
    expect(ai).toHaveTextContent('A slide is AI-generated')
    expect(ai).toHaveTextContent("Turn on each platform's AI-generated label when you publish. The Post page reminds you.")
    expect(screen.getByTestId('readiness-row-altTextStale')).toHaveTextContent('Alt text on slides 1, 3 still quote the old text')
    // warnings do not block
    expect(screen.getByTestId('readiness-title')).toHaveTextContent('Ready for a Post')
  })

  it('falls back to the sentence-cased server message for an unknown key', () => {
    setup([item('newCheck', false, true, 'something odd: look at the thing')])
    const row = screen.getByTestId('readiness-row-newCheck')
    expect(row).toHaveTextContent('Something odd')
    expect(row).toHaveTextContent('Look at the thing')
    expect(within(row).queryByRole('button')).not.toBeInTheDocument()
  })
})

describe('ReadinessCard passed checks', () => {
  const items = [item('slides', true, true, 'slides are chosen'), item('caption', true, true, 'caption is set')]

  it('collapses them behind one row and expands on demand', async () => {
    setup(items)
    expect(screen.getByText('2 checks passed')).toBeInTheDocument()
    expect(screen.queryByText('Slides are chosen')).not.toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: 'Show passed checks' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(toggle)
    expect(screen.getByRole('button', { name: 'Hide passed checks' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('Slides are chosen')).toBeInTheDocument()
    expect(screen.getByText('Caption is set')).toBeInTheDocument()
  })

  it('shows no passed row when nothing passed', () => {
    setup([item('caption', false, true, 'caption is missing')])
    expect(screen.queryByText(/checks? passed/)).not.toBeInTheDocument()
  })
})

describe('readinessCopy', () => {
  it('has copy for every key the backend returns', () => {
    const keys = [
      'slides', 'caption', 'altText', 'altTextStale', 'photoChecked', 'mediaProvenance', 'photoProvenance',
      'clipProvenance', 'audioProvenance', 'clip', 'motionBackground', 'aiDisclosure',
    ]
    for (const key of keys) {
      const copy = readinessCopy(item(key, false, true, 'a slide is AI-generated: x'))
      expect(copy.title).toBeTruthy()
      expect(copy.explanation).toBeTruthy()
    }
  })

  it('counts media missing provenance from the server message', () => {
    expect(readinessCopy(item('mediaProvenance', false, true, '3 of 4 slide images are missing source and/or licence')).title).toBe(
      '3 images are missing a source and licence',
    )
    expect(readinessCopy(item('mediaProvenance', false, true, 'the clip is missing source and/or licence')).title).toBe(
      'Media is missing a source and licence',
    )
  })

  it('handles a single photo and a missing photo', () => {
    expect(readinessCopy(item('photoChecked', false, true, 'photo has not been checked for burned-in text')).title).toBe(
      'Look over 1 photo for text in the image',
    )
    expect(readinessCopy(item('photoChecked', false, true, 'no photo chosen')).title).toBe('Choose a photo')
  })
})
