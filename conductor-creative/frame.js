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
 *     motion?,                     // a MOTION creative's `creative.motion` (see motion.js)
 *     time?: number,               // applied once at load when `motion` is set (default 0)
 *     clipStartSec?: number,       // creative.clipStartSec, for a clip background's own seek
 *   }
 *
 * Result contract:
 *   window.__RENDER_RESULT = { ok, errors?, warnings?, checks?, width, height } | { ok: false, error }
 *   window.__ready = true   // set exactly once, success or failure, when the
 *                           // job's page.waitForFunction should stop waiting
 *
 * MOTION mode (`spec.motion` set): the job needs many frames off of ONE page
 * load (a fresh navigation per frame would be far too slow — 8s at 30fps is
 * 240 frames), so after applying `spec.time`/`spec.motion` once at load, this
 * page also exposes a small driver API for the job to call directly via
 * repeated `page.evaluate()`s against the SAME already-open page:
 *   window.__seekMotion(t) -> Promise<true>     // applies motion + seeks the
 *                                                // clip background (if any) to t
 *   window.__assertBoard() -> Promise<{errors, warnings, width, height}>
 *                                                // runs the normal assertions once,
 *                                                // on demand (never per seek — see
 *                                                // job/render.mjs, which calls this
 *                                                // only on the final end-card frame)
 * __RENDER_RESULT in this mode reports whether the board itself built (not
 * whether it passes assertions, which __assertBoard reports separately).
 */
import { resolveAd, resolveSequence, renderBoard, fitBoard } from './render.js';
import { placements as DEFAULT_PLACEMENTS } from './placements.js';
import { layouts as DEFAULT_LAYOUTS } from './layouts/index.js';
import { ensureBrandFont, settleImages } from './font.js';
import { runAssertions, expectedElements } from './assertions.js';
import { applyMotion, seekBackgroundVideo } from './motion.js';

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
    await settleImages(board); // also the bleed photo layer, a CSS background `document.images` does not list
    // Motion presets restructure the headline (word spans): build that structure, in its final state,
    // before fitting, so the type is sized for the layout every captured frame will have.
    if (spec.motion) applyMotion(board, spec.motion, spec.motion.durationSec || 8, { durationSec: spec.motion.durationSec || 8 });
    fitBoard(board, placementsReg);
    // What this spec requires on the board: a required element that did not render fails the checks.
    const expect = expectedElements(ad, spec.brand);

    if (spec.motion) {
      const durationSec = spec.motion.durationSec || 8;
      const t0 = spec.time || 0;
      applyMotion(board, spec.motion, t0, { durationSec });
      await seekBackgroundVideo(board, t0, spec.clipStartSec);

      // The job's frame-stepped capture driver (see the header comment above): one page load, many
      // in-page seeks. Assertions are NOT run here — only once, on demand, via __assertBoard.
      window.__seekMotion = async (t) => {
        applyMotion(board, spec.motion, t, { durationSec });
        await seekBackgroundVideo(board, t, spec.clipStartSec);
        return true;
      };
      // A motion frame is captured at 1x (video), so the photo only needs to cover the placement itself.
      window.__assertBoard = async () => runAssertions(board, placement, spec.brand || {}, { outputScale: 1, expect });

      finish({ ok: true, width: placement.w, height: placement.h });
      return;
    }

    const { errors, warnings, checks, width, height } = await runAssertions(board, placement, spec.brand || {}, { expect });
    finish({ ok: errors.length === 0, errors, warnings, checks, width, height });
  } catch (err) {
    finish({ ok: false, error: String((err && err.message) || err) });
  }
}

main();
