import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { NotificationChannelTable } from './NotificationChannelTable'
import type { NotificationChannelResponse } from '@/hooks/useNotifications'

const channel: NotificationChannelResponse = {
  eventType: 'ISSUE_SUBMITTED',
  provider: 'DISCORD',
  webhookUrl: 'https://discord.com/api/webhooks/123/abc',
  enabled: true,
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const disabledChannel: NotificationChannelResponse = {
  ...channel,
  eventType: 'ISSUE_APPROVED',
  enabled: false,
}

describe('NotificationChannelTable', () => {
  it('renders empty state when no channels', () => {
    render(
      <NotificationChannelTable
        channels={[]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingEventType={null}
      />,
    )
    expect(screen.getByText(/no notification channels configured/i)).toBeInTheDocument()
  })

  it('renders table with channel data', () => {
    render(
      <NotificationChannelTable
        channels={[channel]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingEventType={null}
      />,
    )
    expect(screen.getByText('PRD submitted for review')).toBeInTheDocument()
    expect(screen.getByText('ISSUE_SUBMITTED')).toBeInTheDocument()
    expect(screen.getByText('DISCORD')).toBeInTheDocument()
  })

  it('shows Enabled badge for enabled channel', () => {
    render(
      <NotificationChannelTable
        channels={[channel]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingEventType={null}
      />,
    )
    const badges = screen.getAllByText('Enabled')
    expect(badges.some((el) => el.closest('div[class*="rounded-full"]') !== null)).toBe(true)
  })

  it('shows Disabled badge for disabled channel', () => {
    render(
      <NotificationChannelTable
        channels={[disabledChannel]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingEventType={null}
      />,
    )
    expect(screen.getByText('Disabled')).toBeInTheDocument()
  })

  it('calls onEdit when Edit button clicked', () => {
    const onEdit = vi.fn()
    render(
      <NotificationChannelTable
        channels={[channel]}
        onEdit={onEdit}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingEventType={null}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /edit/i }))
    expect(onEdit).toHaveBeenCalledWith(channel)
  })

  it('calls onDelete when Delete button clicked', () => {
    const onDelete = vi.fn()
    render(
      <NotificationChannelTable
        channels={[channel]}
        onEdit={vi.fn()}
        onDelete={onDelete}
        onTest={vi.fn()}
        testingEventType={null}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /delete/i }))
    expect(onDelete).toHaveBeenCalledWith('ISSUE_SUBMITTED')
  })

  it('calls onTest when Test button clicked', () => {
    const onTest = vi.fn()
    render(
      <NotificationChannelTable
        channels={[channel]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={onTest}
        testingEventType={null}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /test/i }))
    expect(onTest).toHaveBeenCalledWith('ISSUE_SUBMITTED')
  })

  it('disables Test button when testingEventType matches', () => {
    render(
      <NotificationChannelTable
        channels={[channel]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingEventType="ISSUE_SUBMITTED"
      />,
    )
    const testBtn = screen.getByRole('button', { name: /testing/i })
    expect(testBtn).toBeDisabled()
  })

  it('shows full webhook URL in title attribute for truncation', () => {
    render(
      <NotificationChannelTable
        channels={[channel]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingEventType={null}
      />,
    )
    const urlSpan = screen.getByTitle('https://discord.com/api/webhooks/123/abc')
    expect(urlSpan).toBeInTheDocument()
  })
})
