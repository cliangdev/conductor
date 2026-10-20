import { fileURLToPath } from 'node:url'
import path from 'node:path'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  // @cliangdev/creative-render (conductor-creative/) ships plain ESM .js, not pre-built — this
  // tells Next to transpile it like first-party source rather than skip it as a "pre-built" dep.
  transpilePackages: ['@cliangdev/creative-render'],
  // Without this, Turbopack infers the workspace root by walking up from every resolved file
  // (including the file:../conductor-creative symlink target) looking for a lockfile, and — since
  // there's no npm workspace tying the two sibling packages together — walks all the way past the
  // repo root to the first one it finds, breaking package.json "exports" resolution (e.g.
  // '@cliangdev/creative-render/styles.css'). Pinning it to the repo root (one level up) fixes that.
  turbopack: {
    root: path.join(__dirname, '..'),
  },
  // Because src/middleware.ts exists, Next buffers request bodies (default cap 10MB) before they
  // reach a rewrite; a larger body is cut at the cap and the proxied request dies with a socket hang
  // up. Match the backend's spring.servlet.multipart.max-request-size (50MB) so a multipart call
  // that works direct also works through the /_backend proxy.
  experimental: {
    proxyClientMaxBodySize: '50mb',
  },
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
      // The Asset Library page was folded into Media (a Post's files are links into Media).
      { source: `${base}/marketing/assets`, destination: `${base}/marketing/media`, permanent: true },
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

      // Opt-in same-origin API proxy (per browser, `?api=proxy`; see src/lib/apiBase.ts and
      // docs/performance.md). A same-origin call needs no CORS preflight. The prefix is deliberately
      // outside /api/* so it can never shadow our own route handlers (/api/telemetry, /api/health).
      // Nothing uses it unless a browser opts in, so the default path is unchanged.
      rules.push({ source: '/_backend/:path*', destination: `${apiUrl}/:path*` })
    }

    return rules
  },
};

export default nextConfig;
