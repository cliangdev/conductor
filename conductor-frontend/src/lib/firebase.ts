import { initializeApp, getApps, type FirebaseApp } from 'firebase/app'
import { getAuth, type Auth } from 'firebase/auth'

const firebaseConfig = {
  apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY ?? 'placeholder',
  authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN ?? 'placeholder.firebaseapp.com',
  projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID ?? 'placeholder',
}

let app: FirebaseApp
let auth: Auth

function getFirebaseApp(): FirebaseApp {
  if (!app) {
    // Use the current host as authDomain so Firebase opens its auth popup to our own
    // domain (proxied via /__/auth/*). This makes the popup same-origin, eliminating
    // Chrome 149 COOP cross-origin issues with window.closed and postMessage.
    const authDomain = typeof window !== 'undefined'
      ? window.location.host
      : firebaseConfig.authDomain
    app = getApps().length ? getApps()[0] : initializeApp({ ...firebaseConfig, authDomain })
  }
  return app
}

function getFirebaseAuth(): Auth {
  if (!auth) {
    auth = getAuth(getFirebaseApp())
  }
  return auth
}

export { getFirebaseApp, getFirebaseAuth }
