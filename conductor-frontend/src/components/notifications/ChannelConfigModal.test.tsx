import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/components/ui/modal', () => ({
  Modal: ({
    open,
    children,
    title,
  }: {
    open: boolean
    children: React.ReactNode
    title: string
  }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
      </div>
    ) : null,
}))

import { ChannelConfigModal } from './ChannelConfigModal'
import type { NotificationChannelResponse } from '@/hooks/useNotifications'

const existingChannel: NotificationChannelResponse = {
  eventType: 'ISSUE_SUBMITTED',
  provider: 'DISCORD',
  webhookUrl: 'https://discord.com/api/webhooks/123/abc',
  enabled: true,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

describe('ChannelConfigModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing when closed', () => {
    render(
      <ChannelConfigModal
        open={false}
        onClose={vi.fn()}
        onSave={vi.fn()}
        usedEventTypes={[]}
      />,
    )
    expect(screen.queryByTestId('modal')).not.toBeInTheDocument()
  })

  it('renders Add title when no existingChannel', () => {
    render(
      <ChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        usedEventTypes={[]}
      />,
    )
    expect(screen.getByText('Add Notification Channel')).toBeInTheDocument()
  })

  it('renders Edit title when existingChannel provided', () => {
    render(
      <ChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingChannel={existingChannel}
        usedEventTypes={[]}
      />,
    )
    expect(screen.getByText('Edit Notification Channel')).toBeInTheDocument()
  })

  it('pre-fills fields when editing', () => {
    render(
      <ChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingChannel={existingChannel}
        usedEventTypes={[]}
      />,
    )
    const urlInput = screen.getByLabelText(/webhook url/i) as HTMLInputElement
    expect(urlInput.value).toBe('https://discord.com/api/webhooks/123/abc')
  })

  it('shows validation error for blank webhook URL', async () => {
    render(
      <ChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingChannel={existingChannel}
        usedEventTypes={[]}
      />,
    )
    const urlInput = screen.getByLabelText(/webhook url/i)
    await userEvent.clear(urlInput)
    fireEvent.submit(screen.getByRole('button', { name: /save/i }).closest('form')!)
    expect(await screen.findByText(/webhook url is required/i)).toBeInTheDocument()
  })

  it('shows validation error for non-http URL', async () => {
    render(
      <ChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingChannel={existingChannel}
        usedEventTypes={[]}
      />,
    )
    const urlInput = screen.getByLabelText(/webhook url/i)
    await userEvent.clear(urlInput)
    await userEvent.type(urlInput, 'ftp://invalid.url')
    fireEvent.submit(screen.getByRole('button', { name: /save/i }).closest('form')!)
    expect(
      await screen.findByText(/must start with http:\/\/ or https:\/\//i),
    ).toBeInTheDocument()
  })

  it('calls onSave with correct payload on valid submit', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    const onClose = vi.fn()
    render(
      <ChannelConfigModal
        open={true}
        onClose={onClose}
        onSave={onSave}
        existingChannel={existingChannel}
        usedEventTypes={[]}
      />,
    )
    const urlInput = screen.getByLabelText(/webhook url/i)
    await userEvent.clear(urlInput)
    await userEvent.type(urlInput, 'https://discord.com/api/webhooks/new/url')
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => {
      expect(onSave).toHaveBeenCalledWith({
        provider: 'DISCORD',
        webhookUrl: 'https://discord.com/api/webhooks/new/url',
        enabled: true,
      })
    })
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('calls onClose when Cancel clicked', () => {
    const onClose = vi.fn()
    render(
      <ChannelConfigModal
        open={true}
        onClose={onClose}
        onSave={vi.fn()}
        existingChannel={existingChannel}
        usedEventTypes={[]}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onClose).toHaveBeenCalled()
  })

  it('shows error when onSave throws', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('API error'))
    render(
      <ChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={onSave}
        existingChannel={existingChannel}
        usedEventTypes={[]}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    expect(await screen.findByText(/failed to save channel/i)).toBeInTheDocument()
  })
})
