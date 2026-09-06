import { describe, it, expect, vi } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  YouTubePublishOptions,
  normalizeYouTubeOptions,
  parsePlaylistIds,
} from './YouTubePublishOptions'
import type { MediaAsset } from '@/components/workitems/MediaUploadPanel'

const IMAGES = [
  { id: 'asset-a', type: 'youtube_video', label: 'Thumb A', contentType: 'image/jpeg' },
  { id: 'asset-b', type: 'youtube_video', label: 'Thumb B', contentType: 'image/jpeg' },
] as MediaAsset[]

function renderOptions(props: Partial<React.ComponentProps<typeof YouTubePublishOptions>> = {}) {
  const onChange = vi.fn()
  const result = render(
    <YouTubePublishOptions idPrefix="yt-conn-a" images={IMAGES} value={{}} onChange={onChange} {...props} />
  )
  return { onChange, ...result }
}

describe('parsePlaylistIds', () => {
  it('splits and trims a comma-separated list, dropping blanks', () => {
    expect(parsePlaylistIds(' PL1, PL2 ,, PL3')).toEqual(['PL1', 'PL2', 'PL3'])
  })
})

describe('normalizeYouTubeOptions', () => {
  it('carries through whatever was stored without inventing fields', () => {
    expect(normalizeYouTubeOptions(null)).toEqual({})
    expect(normalizeYouTubeOptions({ madeForKids: true })).toEqual({ madeForKids: true })
  })
})

describe('YouTubePublishOptions', () => {
  it('renders every toggle unchecked by default', () => {
    renderOptions()
    expect(screen.getByRole('switch', { name: 'Notify subscribers' })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: 'Made for kids' })).not.toBeChecked()
    expect(screen.getByRole('switch', { name: /synthetic media/i })).not.toBeChecked()
  })

  it('round-trips notifySubscribers', async () => {
    const { onChange } = renderOptions()
    await userEvent.click(screen.getByRole('switch', { name: 'Notify subscribers' }))
    expect(onChange).toHaveBeenCalledWith({ notifySubscribers: true })
  })

  it('parses playlist ids from a comma-separated box', () => {
    const { onChange } = renderOptions()
    fireEvent.change(screen.getByLabelText(/playlists/i), { target: { value: 'PL1, PL2' } })
    expect(onChange).toHaveBeenLastCalledWith({ playlistIds: ['PL1', 'PL2'] })
  })

  it('lets a thumbnail be chosen from the Post images', async () => {
    const { onChange } = renderOptions()
    await userEvent.selectOptions(screen.getByLabelText(/thumbnail/i), 'asset-b')
    expect(onChange).toHaveBeenCalledWith({ thumbnailAssetId: 'asset-b' })
  })

  it('hydrates a saved value', () => {
    renderOptions({ value: { madeForKids: true, playlistIds: ['PL9'] } })
    expect(screen.getByRole('switch', { name: 'Made for kids' })).toBeChecked()
    expect(screen.getByLabelText(/playlists/i)).toHaveValue('PL9')
  })
})
