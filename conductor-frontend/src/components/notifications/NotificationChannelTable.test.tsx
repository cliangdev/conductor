import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { NotificationGroupTable } from './NotificationGroupTable'
import type { NotificationGroupResponse } from '@/hooks/useNotifications'

const issuesGroup: NotificationGroupResponse = {
  channelGroup: 'ISSUES',
  label: 'Issues',
  provider: 'DISCORD',
  webhookUrl: 'https://discord.com/api/webhooks/123/abc',
  enabled: true,
  events: [
    { eventType: 'ISSUE_SUBMITTED', label: 'PRD submitted for review', enabled: true },
    { eventType: 'ISSUE_APPROVED', label: 'PRD approved', enabled: true },
    { eventType: 'ISSUE_COMPLETED', label: 'PRD marked as completed', enabled: false },
  ],
  createdAt: '2024-01-01T00:00:00Z',
  updatedAt: '2024-01-01T00:00:00Z',
}

const disabledGroup: NotificationGroupResponse = {
  ...issuesGroup,
  channelGroup: 'MEMBERS',
  label: 'Members',
  enabled: false,
  events: [
    { eventType: 'MEMBER_JOINED', label: 'New member joined the project', enabled: false },
  ],
}

describe('NotificationGroupTable', () => {
  it('shows empty state when no groups', () => {
    render(
      <NotificationGroupTable
        groups={[]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    expect(screen.getByText(/no notification channels configured/i)).toBeInTheDocument()
  })

  it('renders group label and provider', () => {
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    expect(screen.getByText('Issues')).toBeInTheDocument()
    expect(screen.getByText('DISCORD')).toBeInTheDocument()
  })

  it('shows Enabled badge for enabled group', () => {
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    expect(screen.getByText('Enabled')).toBeInTheDocument()
  })

  it('shows Disabled badge for disabled group', () => {
    render(
      <NotificationGroupTable
        groups={[disabledGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    expect(screen.getByText('Disabled')).toBeInTheDocument()
  })

  it('renders event type labels', () => {
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    expect(screen.getByText('PRD submitted for review')).toBeInTheDocument()
    expect(screen.getByText('PRD approved')).toBeInTheDocument()
    expect(screen.getByText('PRD marked as completed')).toBeInTheDocument()
  })

  it('shows enabled event count', () => {
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    expect(screen.getByText(/2 of 3 events enabled/i)).toBeInTheDocument()
  })

  it('calls onEdit when Edit clicked', () => {
    const onEdit = vi.fn()
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={onEdit}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /edit/i }))
    expect(onEdit).toHaveBeenCalledWith(issuesGroup)
  })

  it('calls onDelete when Delete clicked', () => {
    const onDelete = vi.fn()
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={vi.fn()}
        onDelete={onDelete}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /delete/i }))
    expect(onDelete).toHaveBeenCalledWith('ISSUES')
  })

  it('calls onTest when Test clicked', () => {
    const onTest = vi.fn()
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={onTest}
        testingGroup={null}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: /test/i }))
    expect(onTest).toHaveBeenCalledWith('ISSUES')
  })

  it('disables Test button and shows Sending when testingGroup matches', () => {
    render(
      <NotificationGroupTable
        groups={[issuesGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup="ISSUES"
      />,
    )
    const testBtn = screen.getByRole('button', { name: /sending/i })
    expect(testBtn).toBeDisabled()
  })

  it('renders multiple groups', () => {
    render(
      <NotificationGroupTable
        groups={[issuesGroup, disabledGroup]}
        onEdit={vi.fn()}
        onDelete={vi.fn()}
        onTest={vi.fn()}
        testingGroup={null}
      />,
    )
    expect(screen.getByText('Issues')).toBeInTheDocument()
    expect(screen.getByText('Members')).toBeInTheDocument()
  })
})
