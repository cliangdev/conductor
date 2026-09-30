import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BrandSetupNotice } from './BrandSetupNotice'
import type { BrandKit } from './types'

Object.assign(navigator, {
  clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
})

function kit(overrides: Partial<BrandKit> = {}): BrandKit {
  return {
    id: 'kit-1',
    projectId: 'proj-1',
    slug: 'default',
    name: 'Default',
    isDefault: true,
    tokens: {},
    fontFamily: null,
    fontUrl: null,
    markUrl: null,
    wordmarkDarkUrl: null,
    wordmarkLightUrl: null,
    badgeUrl: null,
    ctaClaim: null,
    accentPhraseRequired: false,
    copyRules: [],
    approvedLines: [],
    enabledPlacements: ['9x16', '4x5', '1x1'],
    knowledgePagePath: '',
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    configured: false,
    ...overrides,
  }
}

describe('BrandSetupNotice', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('shows the notice for an unconfigured kit, with a link to Settings → Brand and a copyable ask-Claude prompt', async () => {
    render(<BrandSetupNotice projectId="proj-1" kit={kit()} />)

    expect(await screen.findByTestId('brand-setup-notice')).toBeInTheDocument()
    expect(screen.getByText(/Set up your brand first/)).toBeInTheDocument()
    const link = screen.getByRole('link', { name: /Settings → Brand/ })
    expect(link).toHaveAttribute('href', '/app/projects/proj-1/settings/brand')
    expect(screen.getByText('Set up our brand kit in Conductor from our website')).toBeInTheDocument()
  })

  it('does not show for a configured kit', () => {
    render(<BrandSetupNotice projectId="proj-1" kit={kit({ configured: true })} />)
    expect(screen.queryByTestId('brand-setup-notice')).not.toBeInTheDocument()
  })

  it('does not show when there is no kit yet', () => {
    render(<BrandSetupNotice projectId="proj-1" kit={null} />)
    expect(screen.queryByTestId('brand-setup-notice')).not.toBeInTheDocument()
  })

  it('dismisses and persists the dismissal per kit in localStorage', async () => {
    const { unmount } = render(<BrandSetupNotice projectId="proj-1" kit={kit()} />)
    await screen.findByTestId('brand-setup-notice')

    await userEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByTestId('brand-setup-notice')).not.toBeInTheDocument()
    expect(localStorage.getItem('conductor.brandSetupNoticeDismissed.kit-1')).toBe('1')

    // Persists across a remount (e.g. navigating away and back).
    unmount()
    render(<BrandSetupNotice projectId="proj-1" kit={kit()} />)
    await waitFor(() => expect(screen.queryByTestId('brand-setup-notice')).not.toBeInTheDocument())
  })

  it('dismissal on one kit does not hide the notice for a different kit', async () => {
    localStorage.setItem('conductor.brandSetupNoticeDismissed.kit-1', '1')
    render(<BrandSetupNotice projectId="proj-1" kit={kit({ id: 'kit-2' })} />)
    expect(await screen.findByTestId('brand-setup-notice')).toBeInTheDocument()
  })

  it('falls back to hidden when localStorage throws (private window / blocked storage)', async () => {
    const spy = vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked')
    })
    render(<BrandSetupNotice projectId="proj-1" kit={kit()} />)
    expect(await screen.findByTestId('brand-setup-notice')).toBeInTheDocument()
    spy.mockRestore()
  })
})
