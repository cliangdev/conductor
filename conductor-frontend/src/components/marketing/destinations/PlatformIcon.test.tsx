import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

import { PlatformIcon } from './PlatformIcon'
import type { PublishPlatform } from './types'

const PLATFORMS: [PublishPlatform, string, string][] = [
  ['facebook', 'Facebook', 'Fb'],
  ['instagram', 'Instagram', 'Ig'],
  ['youtube', 'YouTube', 'Yt'],
  ['tiktok', 'TikTok', 'Tt'],
]

describe('PlatformIcon', () => {
  it.each(PLATFORMS)('shows the %s brand mark', (platform, name) => {
    render(<PlatformIcon platform={platform} />)

    const img = screen.getByRole('img', { name })
    expect(img).toHaveAttribute('src', `/integrations/${platform}.svg`)
  })

  it.each(PLATFORMS)('falls back to the %s monogram when the mark fails to load', (platform, name, monogram) => {
    render(<PlatformIcon platform={platform} />)

    fireEvent.error(screen.getByRole('img', { name }))

    expect(screen.getByRole('img', { name })).toHaveTextContent(monogram)
  })

  it.each(PLATFORMS)('ships a %s.svg that parses as valid SVG', (platform) => {
    const path = join(process.cwd(), 'public', 'integrations', `${platform}.svg`)
    expect(existsSync(path)).toBe(true)

    const doc = new DOMParser().parseFromString(readFileSync(path, 'utf8'), 'image/svg+xml')
    expect(doc.querySelector('parsererror')).toBeNull()
  })
})
