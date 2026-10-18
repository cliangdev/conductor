import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MediaTile } from './MediaTile'
import type { CreativePhoto } from './types'

function item(overrides: Partial<CreativePhoto>): CreativePhoto {
  return {
    id: 'm1',
    projectId: 'p1',
    contentType: 'image/png',
    sizeBytes: 1,
    mediaKind: 'IMAGE',
    url: 'https://storage.example/m1.png',
    label: 'Hero',
    uploadStatus: 'UPLOADED',
    warnings: [],
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  } as CreativePhoto
}

describe('MediaTile', () => {
  it('lazy-loads and async-decodes an image tile', () => {
    render(<MediaTile item={item({})} />)
    const img = screen.getByAltText('Hero')
    expect(img).toHaveAttribute('loading', 'lazy')
    expect(img).toHaveAttribute('decoding', 'async')
  })

  it('lazy-loads a video poster tile', () => {
    render(<MediaTile item={item({ mediaKind: 'VIDEO', posterUrl: 'https://storage.example/p.jpg', label: 'Clip A' })} />)
    expect(screen.getByAltText('Clip A')).toHaveAttribute('loading', 'lazy')
  })
})
