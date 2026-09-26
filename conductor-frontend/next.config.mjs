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
      { source: `${base}/settings/workflows/:workflowId/edit`, destination: `${base}/workflows/:workflowId/definition`, permanent: true },
      { source: `${base}/settings/integrations`, destination: `${base}/integrations`, permanent: true },
      { source: `${base}/settings/agents`, destination: `${base}/agents`, permanent: true },
      { source: `${base}/settings/agents/new`, destination: `${base}/agents/new`, permanent: true },
      { source: `${base}/settings/agents/:agentId/edit`, destination: `${base}/agents/:agentId/settings`, permanent: true },
      // The automation workflow detail's Overview + Settings tabs were merged into Runs (now the
      // default landing tab) and Definition (settings' real content — the YAML/diagram editor —
      // absorbed here; enable/disable/delete moved to the page header's overflow menu).
      { source: `${base}/workflows/:workflowId/overview`, destination: `${base}/workflows/:workflowId/runs`, permanent: true },
      { source: `${base}/workflows/:workflowId/settings`, destination: `${base}/workflows/:workflowId/definition`, permanent: true },
    ]
  },
  async rewrites() {
    const rules = []

    // Proxy Firebase auth handler to our own domain so signInWithPopup opens
    // a same-origin popup — eliminates all COOP/window.closed issues in Chrome 149+.
    // This mirrors how Firebase Hosting handles custom auth domains.
    const authDomain = process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN
    if (authDomain && authDomain !== 'placeholder.firebaseapp.com') {
      rules.push({ source: '/__/auth/:path*', destination: `https://${authDomain}/__/auth/:path*` })
    }

    // The OAuth callback for the platform apps Conductor registers (TikTok, Meta) lives on this
    // domain, so their allowed redirect URI reads https://conductor.rexipe.io/api/v1/oauth/callback
    // rather than a *.run.app address. Only this one path is proxied: the backend's 302 onward to
    // the app passes straight through to the browser. The backend records which callback address a
    // flow started with and replays it at the token exchange, so this and the backend's own
    // callback URL both keep working. See OAuthFlowService.oauthCallbackUri(String).
    const apiUrl = (process.env.NEXT_PUBLIC_API_URL ?? '').replace(/\/+$/, '')
    if (/^https?:\/\//.test(apiUrl)) {
      rules.push({ source: '/api/v1/oauth/callback', destination: `${apiUrl}/api/v1/oauth/callback` })
    }

    return rules
  },
};

export default nextConfig;
