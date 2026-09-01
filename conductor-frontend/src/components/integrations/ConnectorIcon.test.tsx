import { describe, it, expect } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'

import { ConnectorIcon } from './ConnectorIcon'

const INTEGRATIONS_DIR = join(process.cwd(), 'public', 'integrations')

/** The three connectors that previously fell back to a two-letter text badge. */
const PREVIOUSLY_MISSING = ['meta', 'youtube', 'tiktok'] as const

describe('ConnectorIcon', () => {
  it.each(PREVIOUSLY_MISSING)('resolves %s to its logo asset rather than a text badge', (connectorId) => {
    render(<ConnectorIcon connectorId={connectorId} iconLabel="XX" />)

    const img = screen.getByRole('img')
    expect(img).toHaveAttribute('src', `/integrations/${connectorId}.svg`)
    expect(screen.queryByText('XX')).not.toBeInTheDocument()
  })

  it('falls back to the text badge when the asset fails to load', () => {
    render(<ConnectorIcon connectorId="not-a-real-connector" iconLabel="NR" />)

    fireEvent.error(screen.getByRole('img'))

    expect(screen.getByText('NR')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })
})

describe('connector logo assets', () => {
  it.each(PREVIOUSLY_MISSING)('ships a %s.svg that parses as valid SVG', (connectorId) => {
    const path = join(INTEGRATIONS_DIR, `${connectorId}.svg`)
    expect(existsSync(path)).toBe(true)

    const source = readFileSync(path, 'utf8')
    const doc = new DOMParser().parseFromString(source, 'image/svg+xml')

    expect(doc.querySelector('parsererror')).toBeNull()
    expect(doc.documentElement.tagName).toBe('svg')
    expect(doc.documentElement.getAttribute('viewBox')).toBe('0 0 32 32')
    expect(doc.documentElement.getAttribute('role')).toBe('img')
    expect(doc.documentElement.getAttribute('aria-label')).toBeTruthy()
  })

  it('notes that each added mark is a placeholder for the official brand asset', () => {
    for (const connectorId of PREVIOUSLY_MISSING) {
      const source = readFileSync(join(INTEGRATIONS_DIR, `${connectorId}.svg`), 'utf8')
      expect(source).toMatch(/not an official asset/i)
    }
  })

  it('gives every asset its own opaque tile so it reads on both light and dark backgrounds', () => {
    for (const file of readdirSync(INTEGRATIONS_DIR).filter((f) => f.endsWith('.svg'))) {
      const source = readFileSync(join(INTEGRATIONS_DIR, file), 'utf8')
      expect(source, file).toMatch(/<rect width="32" height="32" rx="7" fill="#[0-9a-fA-F]{6}"\/>/)
    }
  })
})
