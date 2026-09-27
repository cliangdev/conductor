/* frame.js — builds one `.cc-board` from window.__RENDER_SPEC__ at its true
 * pixel size, waits for fonts and images to settle, fits the headline, runs
 * the in-page assertions (assertions.js), and reports the outcome.
 *
 * Spec shape (set by job/render.mjs via page.addInitScript before this page
 * loads):
 *   {
 *     creative, brand,             // see README.md's Creative/Brand shapes
 *     placementKey: '9x16' | ...,  // required, a key from the placements registry
 *     sequenceIndex?: number,      // required when creative.sequenceKind is set
 *     placements?, layouts?,       // registry overrides (default: the shipped ones)
 *   }
 *
 * Result contract:
 *   window.__RENDER_RESULT = { ok, errors?, warnings?, width, height } | { ok: false, error }
 *   window.__ready = true   // set exactly once, success or failure, when the
 *                           // job's page.waitForFunction should stop waiting
 */
import { resolveAd, resolveSequence, renderBoard, fitBoard } from './render.js';
import { placements as DEFAULT_PLACEMENTS } from './placements.js';
import { layouts as DEFAULT_LAYOUTS } from './layouts/index.js';
import { ensureBrandFont } from './font.js';
import { runAssertions } from './assertions.js';

function finish(result) {
  window.__RENDER_RESULT = result;
  window.__ready = true;
}

async function main() {
  const spec = window.__RENDER_SPEC__;
  if (!spec || !spec.creative || !spec.placementKey) {
    finish({ ok: false, error: 'no render spec provided (window.__RENDER_SPEC__)' });
    return;
  }

  const placementsReg = spec.placements || DEFAULT_PLACEMENTS;
  const layoutsReg = spec.layouts || DEFAULT_LAYOUTS;
  const placement = placementsReg[spec.placementKey];
  if (!placement) {
    finish({ ok: false, error: `unknown placement "${spec.placementKey}"` });
    return;
  }

  let ad;
  const isSequence = spec.creative.sequenceKind && Array.isArray(spec.creative.sequence) && spec.creative.sequence.length;
  if (isSequence) {
    const frames = resolveSequence(spec.creative, placementsReg, layoutsReg);
    const idx = spec.sequenceIndex || 0;
    ad = frames[idx];
    if (!ad) {
      finish({ ok: false, error: `no sequence frame at index ${idx} (sequence has ${frames.length})` });
      return;
    }
  } else {
    ad = resolveAd(spec.creative, placementsReg, layoutsReg);
  }

  let board;
  try {
    await ensureBrandFont(spec.brand);
    board = renderBoard(ad, spec.placementKey, placementsReg, layoutsReg, spec.brand || {});
    document.body.appendChild(board);

    await document.fonts.ready;
    await Promise.all([...document.images].map((img) => (img.decode ? img.decode().catch(() => {}) : null)));
    fitBoard(board, placementsReg);

    const { errors, warnings, width, height } = await runAssertions(board, placement, spec.brand || {});
    finish({ ok: errors.length === 0, errors, warnings, width, height });
  } catch (err) {
    finish({ ok: false, error: String((err && err.message) || err) });
  }
}

main();
