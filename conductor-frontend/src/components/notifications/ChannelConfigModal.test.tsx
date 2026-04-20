import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/components/ui/modal', () => ({
  Modal: ({
    open,
    children,
    title,
    footer,
  }: {
    open: boolean
    children: React.ReactNode
    title: string
    footer?: React.ReactNode
  }) =>
    open ? (
      <div data-testid="modal">
        <h2>{title}</h2>
        {children}
        {footer}
      </div>
    ) : null,
}))

import { GroupChannelConfigModal } from './GroupChannelConfigModal'
import type { NotificationGroupResponse } from '@/hooks/useNotifications'

const availableGroups = [
  {
    value: 'ISSUES',
    label: 'Issues',
    eventTypes: ['ISSUE_SUBMITTED', 'ISSUE_APPROVED', 'ISSUE_COMPLETED'],
  },
]

const existingGroup: NotificationGroupResponse = {
  channelGroup: 'ISSUES',
  label: 'Issues',
  provider: 'DISCORD',
  webhookUrl: 'https://discord.com/api/webhooks/123/abc',
  enabled: true,
  events: [
    { eventType: 'ISSUE_SUBMITTED', label: 'PRD submitted for review', enabled: true },
    { eventType: 'ISSUE_APPROVED', label: 'PRD approved', enabled: false },
    { eventType: 'ISSUE_COMPLETED', label: 'PRD marked as completed', enabled: false },
  ],
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

describe('GroupChannelConfigModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('renders nothing when closed', () => {
    render(
      <GroupChannelConfigModal
        open={false}
        onClose={vi.fn()}
        onSave={vi.fn()}
        availableGroups={availableGroups}
      />,
    )
    expect(screen.queryByTestId('modal')).not.toBeInTheDocument()
  })

  it('renders Add title when no existingGroup', () => {
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        availableGroups={availableGroups}
      />,
    )
    expect(screen.getByText('Add Notification Channel')).toBeInTheDocument()
  })

  it('renders Edit title with group label when existingGroup provided', () => {
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingGroup={existingGroup}
        availableGroups={[]}
      />,
    )
    expect(screen.getByText('Edit: Issues')).toBeInTheDocument()
  })

  it('pre-fills webhook URL when editing', () => {
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingGroup={existingGroup}
        availableGroups={[]}
      />,
    )
    const urlInput = screen.getByLabelText(/webhook url/i) as HTMLInputElement
    expect(urlInput.value).toBe('https://discord.com/api/webhooks/123/abc')
  })

  it('pre-checks enabled event types when editing', () => {
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingGroup={existingGroup}
        availableGroups={[]}
      />,
    )
    const submittedCheckbox = screen.getByRole('checkbox', {
      name: /prd submitted for review/i,
    }) as HTMLInputElement
    const approvedCheckbox = screen.getByRole('checkbox', {
      name: /prd approved/i,
    }) as HTMLInputElement
    expect(submittedCheckbox.checked).toBe(true)
    expect(approvedCheckbox.checked).toBe(false)
  })

  it('shows event type checkboxes for available group', () => {
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        availableGroups={availableGroups}
      />,
    )
    expect(screen.getByRole('checkbox', { name: /prd submitted for review/i })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /prd approved/i })).toBeInTheDocument()
  })

  it('shows Discord webhook instructions when Discord selected', () => {
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        availableGroups={availableGroups}
      />,
    )
    expect(screen.getByText(/server settings → integrations → webhooks/i)).toBeInTheDocument()
  })

  it('shows validation error for blank webhook URL', async () => {
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={vi.fn()}
        existingGroup={existingGroup}
        availableGroups={[]}
      />,
    )
    const urlInput = screen.getByLabelText(/webhook url/i)
    await userEvent.clear(urlInput)
    fireEvent.submit(document.querySelector('#channel-form')!)
    expect(await screen.findByText(/webhook url is required/i)).toBeInTheDocument()
  })

  it('calls onSave with channelGroup and request on valid submit', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined)
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={onSave}
        existingGroup={existingGroup}
        availableGroups={[]}
      />,
    )
    const urlInput = screen.getByLabelText(/webhook url/i)
    await userEvent.clear(urlInput)
    await userEvent.type(urlInput, 'https://discord.com/api/webhooks/new/url')
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    await waitFor(() => {
      expect(onSave).toHaveBeenCalledWith('ISSUES', expect.objectContaining({
        provider: 'DISCORD',
        webhookUrl: 'https://discord.com/api/webhooks/new/url',
        enabled: true,
      }))
    })
  })

  it('calls onClose when Cancel clicked', () => {
    const onClose = vi.fn()
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={onClose}
        onSave={vi.fn()}
        existingGroup={existingGroup}
        availableGroups={[]}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }))
    expect(onClose).toHaveBeenCalled()
  })

  it('shows error when onSave throws', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('API error'))
    render(
      <GroupChannelConfigModal
        open={true}
        onClose={vi.fn()}
        onSave={onSave}
        existingGroup={existingGroup}
        availableGroups={[]}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /save/i }))
    expect(await screen.findByText(/failed to save/i)).toBeInTheDocument()
  })
})
