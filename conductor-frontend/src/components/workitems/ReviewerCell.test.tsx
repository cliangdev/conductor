import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/lib/api', () => ({
  apiPost: vi.fn(),
  apiDelete: vi.fn(),
  apiErrorMessage: (_e: unknown, fallback: string) => fallback,
}))
vi.mock('@/components/ui/toast', () => ({ toastError: vi.fn() }))

import { ReviewerCell } from './ReviewerCell'

const members = [
  { userId: 'u-1', name: 'Ada Admin', email: 'a@x.com', roles: ['ADMIN' as const] },
  { userId: 'u-2', name: 'Cara Both', email: 'c@x.com', roles: ['CREATOR' as const, 'REVIEWER' as const] },
  { userId: 'u-3', name: 'Ed Editor', email: 'e@x.com', roles: ['CREATOR' as const] },
  { userId: 'u-4', name: 'Legacy Rita', email: 'r@x.com', role: 'REVIEWER' as const },
]

function open(excludeUserId?: string | null) {
  render(
    <ReviewerCell issueId="i-1" projectId="p-1" reviewers={[]} members={members} token="t" onChanged={vi.fn()} excludeUserId={excludeUserId} />
  )
  return userEvent.click(screen.getByRole('button', { name: /reviewers/i }))
}

describe('ReviewerCell', () => {
  it('lists members who can review (Reviewer or Admin, by set or legacy role), not plain Editors', async () => {
    await open()
    expect(await screen.findByText('Ada Admin')).toBeInTheDocument()
    expect(screen.getByText('Cara Both')).toBeInTheDocument()
    expect(screen.getByText('Legacy Rita')).toBeInTheDocument()
    expect(screen.queryByText('Ed Editor')).not.toBeInTheDocument()
  })

  it('leaves out the excluded member (the author of an independent-review gate)', async () => {
    await open('u-2')
    expect(await screen.findByText('Ada Admin')).toBeInTheDocument()
    expect(screen.queryByText('Cara Both')).not.toBeInTheDocument()
  })
})
