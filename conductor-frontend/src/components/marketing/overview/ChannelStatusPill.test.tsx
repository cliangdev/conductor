import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { ChannelStatusPill } from './ChannelStatusPill'

describe('ChannelStatusPill', () => {
  it('shows MISMATCH on the red status ramp', () => {
    render(<ChannelStatusPill status="MISMATCH" />)
    expect(screen.getByText('Mismatch').closest('span')).toHaveClass('text-status-failed')
  })

  it.each([
    ['LIVE', 'Live'],
    ['STALE', 'Stale'],
    ['NOT_CONNECTED', 'Not connected'],
    ['COMING_SOON', 'Coming soon'],
  ] as const)('labels %s', (status, label) => {
    render(<ChannelStatusPill status={status} />)
    expect(screen.getByText(label)).toBeInTheDocument()
  })
})
