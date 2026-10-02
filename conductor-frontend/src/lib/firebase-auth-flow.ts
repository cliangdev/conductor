import {
  createUserWithEmailAndPassword,
  getIdToken,
  sendEmailVerification,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  updateProfile,
  type User as FirebaseUser,
} from 'firebase/auth'
import type { AuthResponse } from '@/types'
import { apiPost, type ApiError } from '@/lib/api'
import { getFirebaseAuth } from '@/lib/firebase'
import { EmailNotVerifiedError } from '@/lib/auth-errors'

/**
 * Exchange a signed-in Firebase user for a Conductor session. An unverified email never reaches the
 * backend: the user is reloaded once (they may have just clicked the link), and if still unverified
 * an {@link EmailNotVerifiedError} is thrown. The ID token is force-refreshed so its `email_verified`
 * and `name` claims are current. A backend 403 EMAIL_NOT_VERIFIED maps to the same typed error.
 */
export async function exchangeFirebaseUser(user: FirebaseUser): Promise<AuthResponse> {
  if (!user.emailVerified) {
    await user.reload()
    if (!user.emailVerified) throw new EmailNotVerifiedError()
  }
  const idToken = await getIdToken(user, true)
  try {
    return await apiPost<AuthResponse>('/api/v1/auth/firebase', { idToken })
  } catch (err) {
    const apiErr = err as Partial<ApiError>
    if (apiErr.status === 403 && apiErr.code === 'EMAIL_NOT_VERIFIED') throw new EmailNotVerifiedError()
    throw err
  }
}

/** Firebase email/password sign-in, then the backend exchange. */
export async function signInWithEmailExchange(email: string, password: string): Promise<AuthResponse> {
  const cred = await signInWithEmailAndPassword(getFirebaseAuth(), email, password)
  return exchangeFirebaseUser(cred.user)
}

/** Create the Firebase account and send the verification email. Does not touch the backend. */
export async function createEmailAccount(name: string, email: string, password: string): Promise<void> {
  const cred = await createUserWithEmailAndPassword(getFirebaseAuth(), email, password)
  await updateProfile(cred.user, { displayName: name })
  await sendEmailVerification(cred.user, { url: `${window.location.origin}/login` })
}

/** Re-send the verification email to whoever is currently signed in to Firebase. */
export async function resendVerificationEmail(): Promise<void> {
  const user = getFirebaseAuth().currentUser
  if (!user) throw Object.assign(new Error('No signed-in user'), { code: 'auth/no-current-user' })
  await sendEmailVerification(user, { url: `${window.location.origin}/login` })
}

export async function sendPasswordReset(email: string): Promise<void> {
  await sendPasswordResetEmail(getFirebaseAuth(), email)
}
