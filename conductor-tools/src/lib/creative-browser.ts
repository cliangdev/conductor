/**
 * Finds a locally-installed browser to render Creatives with, using `playwright-core` (a dependency
 * of this package, not the browser-bundling `playwright`). `conductor-creative/job/render.mjs`'s own
 * CLI bootstrap depends on full `playwright` (it ships its own Chromium download) because it's meant
 * to run inside a container built for that purpose; the CLI/MCP server run on a user's own machine,
 * so they discover whatever Chromium-family browser is already there instead of bundling one — the
 * same shape nexus-marketing's `rexipe-social` MCP server used.
 *
 * `playwright-core` itself is lazy-imported (see `findBrowserFactory`) so requiring it doesn't add
 * to MCP server startup cost for sessions that never call a creative tool.
 */
import type { Browser, LaunchOptions } from 'playwright-core'

export type BrowserFactory = () => Promise<Browser>

export const CHROME_INSTALL_HINT =
  'No installed browser could be launched for creative rendering. Try one of:\n' +
  '  - Google Chrome (launched via Playwright channel "chrome")\n' +
  '  - Microsoft Edge (launched via Playwright channel "msedge")\n' +
  '  - a Playwright-managed Chromium: run `npx playwright install chromium`'

interface Attempt {
  label: string
  options: LaunchOptions
}

const ATTEMPTS: Attempt[] = [
  { label: 'system Chrome (channel: chrome)', options: { channel: 'chrome' } },
  { label: 'system Edge (channel: msedge)', options: { channel: 'msedge' } },
  { label: 'Playwright-managed Chromium', options: {} },
]

/**
 * Launches the first browser it can find, in the order above, and returns a factory that hands back
 * that same already-launched instance. `run()` in the render core calls its `browserFactory` exactly
 * once per render, so launching eagerly here (rather than deferring to the factory call) avoids a
 * pointless extra launch/close just to "probe" availability.
 *
 * Throws `CHROME_INSTALL_HINT` plus what each attempt failed with when nothing could be launched.
 */
export async function findBrowserFactory(log: (...args: unknown[]) => void = () => {}): Promise<BrowserFactory> {
  const { chromium } = await import('playwright-core')
  const errors: string[] = []
  for (const attempt of ATTEMPTS) {
    try {
      const browser = await chromium.launch(attempt.options)
      log(`using ${attempt.label}`)
      return async () => browser
    } catch (err) {
      errors.push(`${attempt.label}: ${err instanceof Error ? err.message : String(err)}`)
    }
  }
  throw new Error(`${CHROME_INSTALL_HINT}\n\nTried:\n${errors.map((e) => `  - ${e}`).join('\n')}`)
}
