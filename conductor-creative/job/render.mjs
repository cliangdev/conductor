#!/usr/bin/env node
/* render.mjs — the T3 render job's rendering core, plus a thin CLI entry
 * point at the bottom.
 *
 * `run()` below knows nothing about HTTP, Cloud Run, or a Conductor
 * Workflow — it takes a `transport` object (see transport.mjs) with four
 * methods (getSpec, putFrame, complete, fail) and drives Playwright against
 * this package's own frame.html/sheet.html. That split exists because how
 * this job is *launched and reports back* is expected to change (a direct
 * Cloud Run Job launch talking straight REST vs. sitting behind a Conductor
 * Workflow) while the rendering behavior — what gets rendered, how it is
 * checked, and what "done" or "failed" mean — should not have to change
 * alongside it. Tests exercise `run()` directly with an in-memory fake
 * transport (test/job.test.mjs); transport.mjs's HTTP behavior is tested on
 * its own (test/transport.test.mjs).
 *
 * Exit code (CLI only): 0 on success, 1 on any failure (spec fetch, page
 * load, an assertion, an upload, or a timeout on any of those).
 */
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { startServer } from './server.mjs';
import { createHttpTransport } from './transport.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PACKAGE_ROOT = join(HERE, '..'); // conductor-creative/, the static root

const PAGE_TIMEOUT_MS = 20_000;

async function defaultBrowserFactory() {
  const { chromium } = await import('playwright');
  return chromium.launch();
}

/** True when a local Chromium can actually launch. `playwright` lives only
 * in job/node_modules (see job/package.json's header comment on why it is
 * not a conductor-creative/ dependency), so anything outside job/ — e.g.
 * test/job.test.mjs, one directory up — cannot `import('playwright')`
 * itself and resolve it; it calls this instead, which resolves relative to
 * THIS file. Tests use it to skip cleanly on a machine that never ran
 * `npx playwright install chromium`. */
export async function isChromiumAvailable() {
  try {
    const browser = await defaultBrowserFactory();
    await browser.close();
    return true;
  } catch {
    return false;
  }
}

/* Which page + placementKey(s) this render needs, from the spec's own
 * shape — see the internal API contract in transport.mjs's header comment.
 * A story/carousel's beats are separate indexed frames at the sequence's one
 * placement key; a plain creative renders each of its placements once;
 * `previewOnly` always renders exactly one contact-sheet frame, regardless
 * of a sequence, uploaded as placementKey "sheet". */
export function framesFor(spec) {
  const { creative, placements, previewOnly } = spec;
  if (previewOnly) return [{ page: 'sheet.html', placementKey: 'sheet' }];
  const isSequence = creative.sequenceKind && Array.isArray(creative.sequence) && creative.sequence.length;
  if (isSequence) {
    const key = placements[0];
    return creative.sequence.map((_, index) => ({ page: 'frame.html', placementKey: key, index }));
  }
  return (placements || []).map((placementKey) => ({ page: 'frame.html', placementKey }));
}

/** Renders every frame a spec calls for, uploads each PNG, and reports
 * complete/fail. Options:
 *   transport        required. See transport.mjs.
 *   packageRoot       static root to serve (default: this package's own root).
 *   browserFactory    () => Promise<Browser> (default: launches real Playwright chromium).
 *   log               (...args) => void (default: console.log).
 * Resolves `true` on success, `false` on a reported failure (never throws —
 * a thrown error from a truly unexpected place still gets caught and turned
 * into a `transport.fail()` call before resolving `false`, so a caller never
 * has to guess whether `transport.fail` was already called). */
export async function run({ transport, packageRoot = PACKAGE_ROOT, browserFactory = defaultBrowserFactory, log = console.log }) {
  let spec;
  try {
    log('fetching spec');
    spec = await transport.getSpec();
  } catch (err) {
    log(`FAIL ${err.message}`);
    await reportFailure(transport, err.message, log);
    return false;
  }

  const { server, origin } = await startServer(packageRoot);
  let browser;
  const warnings = [];
  try {
    browser = await browserFactory();
    const frames = framesFor(spec);
    log(`rendering ${frames.length} frame(s)`);

    for (const frame of frames) {
      const label = frame.index !== undefined ? `${frame.placementKey}[${frame.index}]` : frame.placementKey;
      const page = await browser.newPage({ deviceScaleFactor: 2 });
      try {
        await page.addInitScript((s) => {
          window.__RENDER_SPEC__ = s;
        }, {
          creative: spec.creative,
          brand: spec.brand,
          placementKey: frame.placementKey,
          sequenceIndex: frame.index,
          placementKeys: spec.placements,
        });
        await page.goto(`${origin}/${frame.page}`, { waitUntil: 'load', timeout: PAGE_TIMEOUT_MS });
        await page.waitForFunction(() => window.__ready === true, null, { timeout: PAGE_TIMEOUT_MS });
        const result = await page.evaluate(() => window.__RENDER_RESULT);

        if (!result || !result.ok) {
          const message = (result && (result.error || (result.errors || []).join('; '))) || 'render failed for an unknown reason';
          throw new Error(`${label}: ${message}`);
        }
        for (const message of result.warnings || []) {
          warnings.push({ placementKey: frame.placementKey, index: frame.index, message });
        }

        const locator = frame.page === 'sheet.html' ? page.locator('#sheet') : page.locator('.cc-board').first();
        const png = await locator.screenshot();
        const box = await locator.boundingBox();
        const width = Math.round((box && box.width) || 0) * 2;
        const height = Math.round((box && box.height) || 0) * 2;

        await transport.putFrame(frame.placementKey, { index: frame.index, width, height, png });
        log(`ok   ${label}  ${width}x${height}`);
      } finally {
        await page.close();
      }
    }

    await transport.complete(warnings);
    log(`complete (${warnings.length} warning(s))`);
    return true;
  } catch (err) {
    log(`FAIL ${err.message}`);
    await reportFailure(transport, err.message, log);
    return false;
  } finally {
    if (browser) await browser.close().catch(() => {});
    server.close();
  }
}

async function reportFailure(transport, message, log) {
  try {
    await transport.fail(message);
  } catch (err) {
    log(`also failed to report failure to the backend: ${err.message}`);
  }
}

/* ── CLI entry point ───────────────────────────────────────────────────── */

function isMain() {
  return process.argv[1] && import.meta.url === `file://${process.argv[1]}`;
}

if (isMain()) {
  const apiUrl = process.env.CONDUCTOR_API_URL;
  const renderToken = process.env.CONDUCTOR_RENDER_TOKEN;
  const renderId = process.env.RENDER_ID;
  const missing = ['CONDUCTOR_API_URL', 'CONDUCTOR_RENDER_TOKEN', 'RENDER_ID'].filter((k) => !process.env[k]);
  if (missing.length) {
    console.error(`missing required env var(s): ${missing.join(', ')}`);
    process.exit(1);
  }

  const transport = createHttpTransport({ apiUrl, renderToken, renderId });
  const log = (...args) => console.log(`[render ${renderId}]`, ...args);
  run({ transport, log }).then((ok) => process.exit(ok ? 0 : 1));
}
