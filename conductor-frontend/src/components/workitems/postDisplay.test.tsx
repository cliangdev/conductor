import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { DestinationMarks, destinationsOf } from './postDisplay'

describe('destinations', () => {
  it('marks a failed destination and keeps the state label in the tooltip', () => {
    const [d] = destinationsOf({ publishTargets: [{ platform: 'tiktok', state: 'FAILED', stateLabel: 'Failed' }] })
    expect(d).toMatchObject({ platform: 'tiktok', failed: true, inactive: false, title: 'Failed' })
  })

  it('treats a taken-back (REVOKED) destination as inactive, with its label as the tooltip', () => {
    const [d] = destinationsOf({ publishTargets: [{ platform: 'instagram', state: 'REVOKED', stateLabel: 'Taken back' }] })
    expect(d).toMatchObject({ platform: 'instagram', inactive: true, failed: false, title: 'Taken back' })
  })

  it('keeps a platform active while any of its targets is still live', () => {
    const [d] = destinationsOf({
      publishTargets: [
        { platform: 'instagram', state: 'REVOKED', stateLabel: 'Taken back' },
        { platform: 'instagram', state: 'PUBLISHED', stateLabel: 'Published' },
      ],
    })
    expect(d!.inactive).toBe(false)
    expect(d!.title).toBe('Taken back, Published')
  })

  it('keeps pending, handed-off, publishing, awaiting and published targets normal', () => {
    for (const state of ['PENDING', 'HANDED_OFF', 'PUBLISHING', 'AWAITING_MANUAL', 'PUBLISHED']) {
      expect(destinationsOf({ publishTargets: [{ platform: 'facebook', state }] })[0]!.inactive).toBe(false)
    }
  })

  it('leaves a platform known only from a published link normal (no target states)', () => {
    const [d] = destinationsOf({ externalLinks: [{ url: 'https://x', type: 'instagram_post' }] })
    expect(d).toMatchObject({ platform: 'instagram', inactive: false, failed: false })
  })

  it('renders the inactive mark muted with a data flag, and the failed one with the destructive ring', () => {
    render(
      <DestinationMarks
        issue={{
          publishTargets: [
            { platform: 'instagram', state: 'REVOKED', stateLabel: 'Taken back' },
            { platform: 'tiktok', state: 'FAILED', stateLabel: 'Failed' },
            { platform: 'facebook', state: 'PENDING', stateLabel: 'Waiting' },
          ],
        }}
      />,
    )
    const ig = screen.getByTitle('Taken back')
    expect(ig).toHaveAttribute('data-inactive', 'true')
    expect(ig.className).toContain('opacity-40')
    expect(screen.getByTitle('Failed')).toHaveAttribute('data-failed', 'true')
    const fb = screen.getByTitle('Waiting')
    expect(fb).not.toHaveAttribute('data-inactive')
    expect(fb.className).not.toContain('opacity-40')
  })
})
