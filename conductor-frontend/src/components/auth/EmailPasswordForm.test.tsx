import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EmailPasswordForm, type EmailPasswordFormProps } from './EmailPasswordForm'
import { EmailNotVerifiedError } from '@/lib/auth-errors'

function setup(overrides: Partial<EmailPasswordFormProps> = {}) {
  const props = {
    onSignIn: vi.fn().mockResolvedValue(undefined),
    onSignUp: vi.fn().mockResolvedValue(undefined),
    onResendVerification: vi.fn().mockResolvedValue(undefined),
    onSendPasswordReset: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
  render(<EmailPasswordForm {...props} />)
  return props
}

async function fillSignIn(email = 'a@b.co', password = 'hunter2hunter2') {
  await userEvent.type(screen.getByLabelText('Email'), email)
  await userEvent.type(screen.getByLabelText('Password'), password)
}

describe('EmailPasswordForm', () => {
  beforeEach(() => vi.clearAllMocks())

  it('signs in with the entered credentials', async () => {
    const props = setup()
    await fillSignIn()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(props.onSignIn).toHaveBeenCalledWith('a@b.co', 'hunter2hunter2'))
  })

  it('toggles between sign in and create account', async () => {
    setup()
    expect(screen.queryByLabelText('Name')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Forgot password?' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /new to conductor/i }))
    expect(screen.getByLabelText('Name')).toHaveAttribute('autocomplete', 'name')
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByText(/at least 8 characters/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Forgot password?' })).not.toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /already have an account/i }))
    expect(screen.queryByLabelText('Name')).not.toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password')
  })

  it('opens on create when initialMode is create', () => {
    setup({ initialMode: 'create' })
    expect(screen.getByRole('button', { name: 'Create account' })).toBeInTheDocument()
  })

  it('shows the verify state after sign up', async () => {
    const props = setup({ initialMode: 'create' })
    await userEvent.type(screen.getByLabelText('Name'), 'Ada Lovelace')
    await fillSignIn('ada@example.com', 'correct-horse')
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }))

    await waitFor(() =>
      expect(props.onSignUp).toHaveBeenCalledWith('Ada Lovelace', 'ada@example.com', 'correct-horse'),
    )
    expect(await screen.findByText(/check your inbox/i)).toBeInTheDocument()
    expect(screen.getByText('ada@example.com')).toBeInTheDocument()
    // Just sent, so resend is on cooldown.
    expect(screen.getByRole('button', { name: /resend email/i })).toBeDisabled()
    expect(props.onSignIn).not.toHaveBeenCalled()
  })

  it('shows a mapped error when sign up fails', async () => {
    setup({
      initialMode: 'create',
      onSignUp: vi.fn().mockRejectedValue({ code: 'auth/email-already-in-use' }),
    })
    await userEvent.type(screen.getByLabelText('Name'), 'Ada')
    await fillSignIn('ada@example.com', 'correct-horse')
    await userEvent.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/already exists/i)
  })

  it('moves to the verify state when sign in hits an unverified email', async () => {
    setup({ onSignIn: vi.fn().mockRejectedValue(new EmailNotVerifiedError()) })
    await fillSignIn()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText(/check your inbox/i)).toBeInTheDocument()
    // Nothing was just sent, so Resend is available immediately.
    expect(screen.getByRole('button', { name: 'Resend email' })).toBeEnabled()
  })

  it('shows a mapped error for bad credentials', async () => {
    setup({ onSignIn: vi.fn().mockRejectedValue({ code: 'auth/invalid-credential' }) })
    await fillSignIn()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Email or password is incorrect.')
  })

  it('resends the verification email and starts the cooldown', async () => {
    const props = setup({ onSignIn: vi.fn().mockRejectedValue(new EmailNotVerifiedError()) })
    await fillSignIn()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Resend email' }))

    await waitFor(() => expect(props.onResendVerification).toHaveBeenCalledTimes(1))
    expect(await screen.findByText(/verification email sent/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /resend email/i })).toBeDisabled()
  })

  it('re-enables resend once the cooldown elapses', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      setup({ onSignIn: vi.fn().mockRejectedValue(new EmailNotVerifiedError()) })
      await fillSignIn()
      await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
      await userEvent.click(await screen.findByRole('button', { name: 'Resend email' }))
      await waitFor(() => expect(screen.getByRole('button', { name: /resend email/i })).toBeDisabled())
      await act(async () => {
        await vi.advanceTimersByTimeAsync(31_000)
      })
      expect(screen.getByRole('button', { name: 'Resend email' })).toBeEnabled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('retries sign in from "I\'ve verified my email"', async () => {
    const onSignIn = vi
      .fn()
      .mockRejectedValueOnce(new EmailNotVerifiedError())
      .mockRejectedValueOnce(new EmailNotVerifiedError())
      .mockResolvedValueOnce(undefined)
    setup({ onSignIn })
    await fillSignIn()
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    const verified = await screen.findByRole('button', { name: /i've verified my email/i })

    await userEvent.click(verified)
    expect(await screen.findByRole('alert')).toHaveTextContent(/haven't seen your verification/i)

    await userEvent.click(screen.getByRole('button', { name: /i've verified my email/i }))
    await waitFor(() => expect(onSignIn).toHaveBeenCalledTimes(3))
    expect(onSignIn).toHaveBeenLastCalledWith('a@b.co', 'hunter2hunter2')
  })

  it('shows a neutral confirmation after a password reset request', async () => {
    const props = setup()
    await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }))
    await userEvent.type(screen.getByLabelText('Email'), 'who@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send reset link' }))

    await waitFor(() => expect(props.onSendPasswordReset).toHaveBeenCalledWith('who@example.com'))
    expect(await screen.findByRole('status')).toHaveTextContent(
      "If an account exists for that address, we've sent a reset link",
    )
  })

  it('does not reveal that an account is missing on reset', async () => {
    setup({ onSendPasswordReset: vi.fn().mockRejectedValue({ code: 'auth/user-not-found' }) })
    await userEvent.click(screen.getByRole('button', { name: 'Forgot password?' }))
    await userEvent.type(screen.getByLabelText('Email'), 'nobody@example.com')
    await userEvent.click(screen.getByRole('button', { name: 'Send reset link' }))

    expect(await screen.findByRole('status')).toHaveTextContent(/if an account exists/i)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  afterEach(() => vi.useRealTimers())
})
