import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import { ConnectionRow, type ConnectionWithHealth } from './ConnectionRow'

function connection(overrides: Partial<ConnectionWithHealth> = {}): ConnectionWithHealth {
  return {
    id: 'conn-1',
    label: 'Acme Marketing',
    status: 'ACTIVE',
    authType: 'OAUTH2',
    ...overrides,
  }
}

function renderRow(conn: ConnectionWithHealth, props: Record<string, unknown> = {}) {
  return render(
    <ConnectionRow
      connection={conn}
      connectorId="acme"
      connectorName="Acme"
      iconLabel="AC"
      {...props}
    />
  )
}

describe('ConnectionRow', () => {
  it('shows an error state for an UNHEALTHY connection', () => {
    renderRow(connection({ healthStatus: 'UNHEALTHY', healthMessage: 'Token has been expired or revoked.' }))

    expect(screen.getByText('Needs reconnect')).toBeInTheDocument()
    expect(screen.getByText(/Token has been expired or revoked\./)).toBeInTheDocument()
    expect(screen.queryByText('Connected')).not.toBeInTheDocument()
  })

  it('colors the error state from the status ramp, not an invented color', () => {
    renderRow(connection({ healthStatus: 'UNHEALTHY', healthMessage: 'Token revoked' }))

    expect(screen.getByText('Needs reconnect').className).toContain('text-status-failed')
  })

  it('still presents an UNHEALTHY connection as connected, never as removed', () => {
    // Health is not status: the row must not imply the connection was dropped.
    renderRow(connection({ healthStatus: 'UNHEALTHY', healthMessage: 'Token revoked' }), {
      canMutate: true,
      onDisconnect: vi.fn(),
    })

    expect(screen.getByText('Acme Marketing')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Disconnect' })).toBeInTheDocument()
  })

  it('falls back to a readable reason when the platform gave no message', () => {
    renderRow(connection({ healthStatus: 'UNHEALTHY', healthMessage: null }))

    expect(screen.getByText(/Reconnect the account/)).toBeInTheDocument()
  })

  it('shows the normal connected state for a healthy connection', () => {
    renderRow(connection({ healthStatus: 'HEALTHY' }))

    expect(screen.getByText('Connected')).toBeInTheDocument()
    expect(screen.queryByText('Needs reconnect')).not.toBeInTheDocument()
  })

  it('shows the normal connected state when health has never been checked', () => {
    renderRow(connection())

    expect(screen.getByText('Connected')).toBeInTheDocument()
    expect(screen.queryByText('Needs reconnect')).not.toBeInTheDocument()
  })

  it('does not treat a data-fetch grade as a broken connection', () => {
    // DEGRADED/SETUP_REQUIRED grade the last data *fetch*; only UNHEALTHY means the credentials died.
    renderRow(connection({ healthStatus: 'DEGRADED' }))

    expect(screen.queryByText('Needs reconnect')).not.toBeInTheDocument()
  })

  it('falls back to the connector name when the connection has no label', () => {
    renderRow(connection({ label: null }))

    expect(screen.getByText('Acme')).toBeInTheDocument()
  })

  it('disconnects the connection it was given', async () => {
    const onDisconnect = vi.fn()
    renderRow(connection(), { canMutate: true, onDisconnect })

    await userEvent.click(screen.getByRole('button', { name: 'Disconnect' }))

    expect(onDisconnect).toHaveBeenCalledWith('conn-1')
  })

  it('hides the disconnect action without permission', () => {
    renderRow(connection(), { canMutate: false, onDisconnect: vi.fn() })

    expect(screen.queryByRole('button', { name: 'Disconnect' })).not.toBeInTheDocument()
  })
})
