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
  async redirects() {
    // The duplicate Settings homes for Workflows / Integrations / Agents were merged into
    // their top-level homes. Keep old bookmarks working.
    const base = '/app/projects/:projectId'
    return [
      { source: `${base}/settings/workflows`, destination: `${base}/workflows`, permanent: true },
      { source: `${base}/settings/workflows/new`, destination: `${base}/workflows/new`, permanent: true },
      { source: `${base}/settings/workflows/:workflowId/edit`, destination: `${base}/workflows/:workflowId/settings`, permanent: true },
      { source: `${base}/settings/integrations`, destination: `${base}/integrations`, permanent: true },
      { source: `${base}/settings/agents`, destination: `${base}/agents`, permanent: true },
      { source: `${base}/settings/agents/new`, destination: `${base}/agents/new`, permanent: true },
      { source: `${base}/settings/agents/:agentId/edit`, destination: `${base}/agents/:agentId/settings`, permanent: true },
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
