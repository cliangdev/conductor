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
import { createApiTransport } from './transport.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const PACKAGE_ROOT = join(HERE, '..'); // conductor-creative/, the static root

const PAGE_TIMEOUT_MS = 20_000;

/* Placement frames are JPEG — Instagram feed images and TikTok photo posts both refuse PNG (see
 * MediaTargetValidator on the backend) — at quality 92, a level export tooling generally treats as
 * visually lossless while still compressing meaningfully smaller than PNG. The `sheet` contact sheet
 * (a preview-only render, never attached to a Post) is a 1x JPEG: it exists to be looked at inside a
 * chat (preview_creative returns it as an image), which caps it near 1 MB, and a 2x PNG grid of every
 * placement runs well past that. Every `.cc-board` paints an explicit
 * `background-color` (frame.css) — dark or light theme — so there is no transparency for JPEG's opaque
 * export to clip. */
const PLACEMENT_FRAME_SCREENSHOT = { type: 'jpeg', quality: 92 };
const PLACEMENT_FRAME_CONTENT_TYPE = 'image/jpeg';
const SHEET_SCREENSHOT = { type: 'jpeg', quality: 85 };
const SHEET_CONTENT_TYPE = 'image/jpeg';
const FRAME_SCALE = 2;
const SHEET_SCALE = 1;

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
  if (!placements || !placements.length) {
    throw new Error('the render spec names no placements to render (check the Brand Kit\'s enabled placements, or the carousel ratio)');
  }
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
      const isSheet = frame.page === 'sheet.html';
      const scale = isSheet ? SHEET_SCALE : FRAME_SCALE;
      const page = await browser.newPage({ deviceScaleFactor: scale });
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

        const locator = isSheet ? page.locator('#sheet') : page.locator('.cc-board').first();
        const bytes = await locator.screenshot(isSheet ? SHEET_SCREENSHOT : PLACEMENT_FRAME_SCREENSHOT);
        const contentType = isSheet ? SHEET_CONTENT_TYPE : PLACEMENT_FRAME_CONTENT_TYPE;
        const box = await locator.boundingBox();
        const width = Math.round((box && box.width) || 0) * scale;
        const height = Math.round((box && box.height) || 0) * scale;

        await transport.putFrame(frame.placementKey, { index: frame.index, width, height, bytes, contentType });
        log(`ok   ${label}  ${width}x${height}  ${contentType}`);
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
  // A spec fetch the API refused never created a render, so there is nothing to mark failed.
  if (typeof transport.getRenderId === 'function' && !transport.getRenderId()) return;
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
  const apiKey = process.env.CONDUCTOR_API_KEY;
  const projectId = process.env.CONDUCTOR_PROJECT_ID;
  const creativeId = process.env.CREATIVE_ID;
  const previewOnly = /^(1|true)$/i.test(process.env.PREVIEW_ONLY || '');
  const renderer = process.env.RENDERER || 'cli';
  const workflowRunId = process.env.WORKFLOW_RUN_ID || undefined;
  const missing = ['CONDUCTOR_API_URL', 'CONDUCTOR_API_KEY', 'CONDUCTOR_PROJECT_ID', 'CREATIVE_ID'].filter((k) => !process.env[k]);
  if (missing.length) {
    console.error(`missing required env var(s): ${missing.join(', ')}`);
    process.exit(1);
  }

  const transport = createApiTransport({ apiUrl, apiKey, projectId, creativeId, previewOnly, renderer, workflowRunId });
  const log = (...args) => console.log(`[render ${creativeId}]`, ...args);
  run({ transport, log }).then((ok) => process.exit(ok ? 0 : 1));
}
