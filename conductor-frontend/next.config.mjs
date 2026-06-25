/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  async headers() {
    return [
      {
        source: '/(.*)',
        headers: [
          { key: 'Cross-Origin-Opener-Policy', value: 'same-origin-allow-popups' },
        ],
      },
    ]
  },
  async rewrites() {
    // Proxy Firebase auth handler to our own domain so signInWithPopup opens
    // a same-origin popup — eliminates all COOP/window.closed issues in Chrome 149+.
    // This mirrors how Firebase Hosting handles custom auth domains.
    const authDomain = process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN
    if (!authDomain || authDomain === 'placeholder.firebaseapp.com') return []
    return [
      {
        source: '/__/auth/:path*',
        destination: `https://${authDomain}/__/auth/:path*`,
      },
    ]
  },
};

export default nextConfig;
