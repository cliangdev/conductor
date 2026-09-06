import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  InstagramPublishOptions,
  isSingleImageTarget,
  normalizeInstagramOptions,
  parseCollaborators,
  type InstagramPublishOptionValues,
} from './InstagramPublishOptions'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'

const IMAGES = [
  { id: 'asset-a', type: 'instagram_post', label: 'Square', contentType: 'image/jpeg' },
  { id: 'asset-b', type: 'instagram_post', label: 'Portrait', contentType: 'image/jpeg' },
] as MediaAsset[]

function renderOptions(props: Partial<React.ComponentProps<typeof InstagramPublishOptions>> = {}) {
  const onChange = vi.fn()
  const result = render(
    <InstagramPublishOptions
      idPrefix="ig-conn-a"
      format="feed"
      images={IMAGES}
      isSingleImage={false}
      value={{}}
      onChange={onChange}
      {...props}
    />
  )
  return { onChange, ...result }
}

describe('parseCollaborators', () => {
  it('splits and trims a comma-separated list, dropping blanks', () => {
    expect(parseCollaborators(' alice, bob ,, carol')).toEqual(['alice', 'bob', 'carol'])
  })
})

describe('normalizeInstagramOptions', () => {
  it('carries through whatever was stored without inventing fields', () => {
    expect(normalizeInstagramOptions(null)).toEqual({})
    expect(normalizeInstagramOptions({ shareToFeed: true })).toEqual({ shareToFeed: true })
  })
})

describe('isSingleImageTarget', () => {
  it('is true for exactly one non-video asset', () => {
    expect(isSingleImageTarget([IMAGES[0]!])).toBe(true)
  })
  it('is false for a carousel', () => {
    expect(isSingleImageTarget(IMAGES)).toBe(false)
  })
  it('is false for a single video', () => {
    expect(
      isSingleImageTarget([{ id: 'v', type: 'x', label: 'Clip', contentType: 'video/mp4' } as MediaAsset])
    ).toBe(false)
  })
})

describe('InstagramPublishOptions', () => {
  it('hides reel-only fields on a feed post', () => {
    renderOptions({ format: 'feed' })
    expect(screen.queryByText('Also share to Feed')).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/cover image/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/audio name/i)).not.toBeInTheDocument()
  })

  it('shows reel-only fields on a reel', () => {
    renderOptions({ format: 'reel' })
    expect(screen.getByText('Also share to Feed')).toBeInTheDocument()
    expect(screen.getByLabelText(/cover image/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/audio name/i)).toBeInTheDocument()
  })

  it('shows alt text only on a single-image feed post', () => {
    renderOptions({ format: 'feed', isSingleImage: true })
    expect(screen.getByLabelText(/alt text/i)).toBeInTheDocument()
  })

  it('hides alt text on a carousel', () => {
    renderOptions({ format: 'feed', isSingleImage: false })
    expect(screen.queryByLabelText(/alt text/i)).not.toBeInTheDocument()
  })

  it('hides alt text on a reel', () => {
    renderOptions({ format: 'reel', isSingleImage: true })
    expect(screen.queryByLabelText(/alt text/i)).not.toBeInTheDocument()
  })

  it('round-trips shareToFeed', async () => {
    const { onChange } = renderOptions({ format: 'reel' })
    await userEvent.click(screen.getByRole('switch'))
    expect(onChange).toHaveBeenCalledWith({ shareToFeed: true })
  })

  it('parses up to 3 collaborators from a comma-separated box', () => {
    const { onChange } = renderOptions()
    fireEvent.change(screen.getByLabelText(/collaborators/i), { target: { value: 'alice, bob' } })
    expect(onChange).toHaveBeenLastCalledWith({ collaborators: ['alice', 'bob'] })
  })

  it('warns when more than 3 collaborators are entered', () => {
    renderOptions({ value: { collaborators: ['a', 'b', 'c', 'd'] } as InstagramPublishOptionValues })
    expect(screen.getByText(/at most 3 collaborators/i)).toBeInTheDocument()
  })

  it('omits altText rather than sending an empty string when cleared', async () => {
    const { onChange } = renderOptions({
      format: 'feed',
      isSingleImage: true,
      value: { altText: 'A photo' },
    })
    await userEvent.clear(screen.getByLabelText(/alt text/i))
    expect(onChange).toHaveBeenLastCalledWith({ altText: undefined })
  })

  it('lets a reel choose a cover from the Post images', async () => {
    const { onChange } = renderOptions({ format: 'reel' })
    await userEvent.selectOptions(screen.getByLabelText(/cover image/i), 'asset-b')
    expect(onChange).toHaveBeenCalledWith({ coverAssetId: 'asset-b' })
  })
})
