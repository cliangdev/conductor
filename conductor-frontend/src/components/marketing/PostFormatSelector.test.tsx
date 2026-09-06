import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PostFormatSelector, FormatBadge, formatHelperText } from './PostFormatSelector'

describe('PostFormatSelector', () => {
  it('renders nothing when the platform only offers feed', () => {
    const { container } = render(
      <PostFormatSelector
        idPrefix="fb"
        platform="youtube"
        formats={['feed']}
        value="feed"
        onChange={vi.fn()}
      />
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when formats is undefined', () => {
    const { container } = render(
      <PostFormatSelector idPrefix="fb" platform="youtube" formats={undefined} value="feed" onChange={vi.fn()} />
    )
    expect(container).toBeEmptyDOMElement()
  })

  it('offers only the formats the platform lists, in feed/reel/story order', () => {
    render(
      <PostFormatSelector
        idPrefix="ig"
        platform="instagram"
        formats={['feed', 'reel', 'story']}
        value="feed"
        onChange={vi.fn()}
      />
    )
    expect(screen.getAllByRole('radio').map((el) => el.textContent)).toEqual(['Feed', 'Reel', 'Story'])
  })

  it('omits a format the platform does not offer', () => {
    render(
      <PostFormatSelector idPrefix="fb" platform="facebook" formats={['feed', 'reel']} value="feed" onChange={vi.fn()} />
    )
    expect(screen.getAllByRole('radio').map((el) => el.textContent)).toEqual(['Feed', 'Reel'])
  })

  it('marks the current value as the checked radio', () => {
    render(
      <PostFormatSelector
        idPrefix="ig"
        platform="instagram"
        formats={['feed', 'reel', 'story']}
        value="reel"
        onChange={vi.fn()}
      />
    )
    expect(screen.getByRole('radio', { name: 'Reel' })).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByRole('radio', { name: 'Feed' })).toHaveAttribute('aria-checked', 'false')
  })

  it('fires onChange with the clicked format', async () => {
    const onChange = vi.fn()
    render(
      <PostFormatSelector
        idPrefix="ig"
        platform="instagram"
        formats={['feed', 'reel', 'story']}
        value="feed"
        onChange={onChange}
      />
    )
    screen.getByRole('radio', { name: 'Story' }).click()
    expect(onChange).toHaveBeenCalledWith('story')
  })

  it('shows the story helper text for a story destination', () => {
    render(
      <PostFormatSelector
        idPrefix="ig"
        platform="instagram"
        formats={['feed', 'reel', 'story']}
        value="story"
        onChange={vi.fn()}
      />
    )
    expect(screen.getByText(/disappears after 24 hours/i)).toBeInTheDocument()
    expect(screen.getByText(/no caption/i)).toBeInTheDocument()
  })

  it("shows each platform's own reel duration hint", () => {
    expect(formatHelperText('facebook', 'reel')).toMatch(/3–90 seconds/)
    expect(formatHelperText('instagram', 'reel')).toMatch(/15 minutes/)
  })

  it('shows no helper text for feed', () => {
    expect(formatHelperText('facebook', 'feed')).toBeNull()
  })
})

describe('FormatBadge', () => {
  it('renders nothing for feed', () => {
    const { container } = render(<FormatBadge format="feed" />)
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing when format is absent', () => {
    const { container } = render(<FormatBadge format={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('labels a reel', () => {
    render(<FormatBadge format="reel" />)
    expect(screen.getByText('Reel')).toBeInTheDocument()
  })

  it('labels a story', () => {
    render(<FormatBadge format="story" />)
    expect(screen.getByText('Story')).toBeInTheDocument()
  })
})
