/** Thrown when a Firebase account exists but its email address hasn't been verified yet. The code
 * mirrors Firebase's `auth/*` namespace so the UI can treat it like any other auth error. */
export const EMAIL_NOT_VERIFIED_CODE = 'auth/email-not-verified'

export class EmailNotVerifiedError extends Error {
  readonly code = EMAIL_NOT_VERIFIED_CODE

  constructor() {
    super('Email address is not verified')
    this.name = 'EmailNotVerifiedError'
  }
}

function errorCode(err: unknown): string | undefined {
  const code = err && typeof err === 'object' ? (err as { code?: unknown }).code : undefined
  return typeof code === 'string' ? code : undefined
}

export function isEmailNotVerifiedError(err: unknown): boolean {
  return errorCode(err) === EMAIL_NOT_VERIFIED_CODE
}

/** Plain-language message for a Firebase Auth (or typed Conductor) error. Never leaks raw codes. */
export function authErrorMessage(err: unknown): string {
  switch (errorCode(err)) {
    case 'auth/email-already-in-use':
      return "An account with this email already exists. Sign in instead — or use Continue with Google if that's how you signed up."
    case 'auth/invalid-credential':
    case 'auth/wrong-password':
    case 'auth/user-not-found':
      return 'Email or password is incorrect.'
    case 'auth/weak-password':
      return 'Choose a stronger password — at least 8 characters.'
    case 'auth/invalid-email':
      return 'Enter a valid email address.'
    case 'auth/too-many-requests':
      return 'Too many attempts. Wait a few minutes and try again.'
    case 'auth/network-request-failed':
      return "Couldn't reach the server. Check your connection and try again."
    case 'auth/operation-not-allowed':
      return "Email sign-in isn't enabled yet."
    case EMAIL_NOT_VERIFIED_CODE:
      return 'Verify your email address to continue.'
    default:
      return 'Something went wrong. Please try again.'
  }
}
