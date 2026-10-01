import { describe, it, expect } from 'vitest'
import { authErrorMessage, EmailNotVerifiedError, isEmailNotVerifiedError } from './auth-errors'

const msg = (code: string) => authErrorMessage({ code })

describe('authErrorMessage', () => {
  it('explains an existing account and points at Google', () => {
    expect(msg('auth/email-already-in-use')).toMatch(/already exists.*Continue with Google/)
  })

  it.each(['auth/invalid-credential', 'auth/wrong-password', 'auth/user-not-found'])(
    'maps %s to one neutral message',
    (code) => {
      expect(msg(code)).toBe('Email or password is incorrect.')
    },
  )

  it('maps the remaining known codes', () => {
    expect(msg('auth/weak-password')).toMatch(/8 characters/)
    expect(msg('auth/invalid-email')).toMatch(/valid email/)
    expect(msg('auth/too-many-requests')).toMatch(/too many attempts/i)
    expect(msg('auth/network-request-failed')).toMatch(/connection/)
    expect(msg('auth/operation-not-allowed')).toBe("Email sign-in isn't enabled yet.")
    expect(msg('auth/email-not-verified')).toMatch(/verify your email/i)
  })

  it('falls back to a generic message and never leaks the code', () => {
    expect(msg('auth/something-new')).toBe('Something went wrong. Please try again.')
    expect(authErrorMessage(new Error('boom'))).toBe('Something went wrong. Please try again.')
    expect(authErrorMessage(null)).toBe('Something went wrong. Please try again.')
  })
})

describe('EmailNotVerifiedError', () => {
  it('is recognised by code', () => {
    expect(isEmailNotVerifiedError(new EmailNotVerifiedError())).toBe(true)
    expect(isEmailNotVerifiedError({ code: 'auth/email-not-verified' })).toBe(true)
    expect(isEmailNotVerifiedError(new Error('x'))).toBe(false)
  })
})
