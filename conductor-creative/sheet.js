/* sheet.js — builds a contact sheet: every enabled placement of a creative,
 * or every beat of its story/carousel sequence, scaled down onto one page.
 * Preview only — never screenshotted per-placement, never assertion-checked
 * (a bad frame still shows up in the sheet so a human can see what broke).
 *
 * Spec shape (set by job/render.mjs via page.addInitScript before this page
 * loads):
 *   {
 *     creative, brand,        // see README.md's Creative/Brand shapes
 *     placementKeys: [...],   // the render's placements (spec.placements from
 *                             // the backend); ignored when the creative has a
 *                             // sequence, which always renders every beat
 *     placements?, layouts?,  // registry overrides (default: the shipped ones)
 *     times?: number[],       // a MOTION creative's key moments (motion.js's
 *                             // motionKeyTimes()): one ROW per time, each row
 *                             // holding every placement in placementKeys at
 *                             // that time. Ignored for a sequence creative
 *                             // (the backend refuses a MOTION creative with a sequence).
 *   }
 *
 * Result contract: window.__RENDER_RESULT = { ok: true, width, height } |
 * { ok: false, error }; window.__ready = true once the sheet has been built,
 * fitted and scaled.
 */
import { resolveAd, resolveSequence, renderBoard, fitAll } from './render.js';
import { placements as DEFAULT_PLACEMENTS } from './placements.js';
import { layouts as DEFAULT_LAYOUTS } from './layouts/index.js';
import { ensureBrandFont, settleImages } from './font.js';
import { applyMotion } from './motion.js';

const SHEET_SCALE = 0.34;

function finish(result) {
  window.__RENDER_RESULT = result;
  window.__ready = true;
}

async function main() {
  const spec = window.__RENDER_SPEC__;
  if (!spec || !spec.creative) {
    finish({ ok: false, error: 'no render spec provided (window.__RENDER_SPEC__)' });
    return;
  }
  const placementsReg = spec.placements || DEFAULT_PLACEMENTS;
  const layoutsReg = spec.layouts || DEFAULT_LAYOUTS;
  const sheet = document.getElementById('sheet');
  const boards = [];
  const motionCells = []; // { board, time }, applied after fitAll (see below)

  function addCell(ad, placementKey, label, container) {
    const placement = placementsReg[placementKey];
    if (!placement) throw new Error(`unknown placement "${placementKey}"`);
    const cell = document.createElement('div');
    cell.className = 'cell';
    const shell = document.createElement('div');
    shell.className = 'shell';
    shell.style.width = Math.round(placement.w * SHEET_SCALE) + 'px';
    shell.style.height = Math.round(placement.h * SHEET_SCALE) + 'px';
    const board = renderBoard(ad, placementKey, placementsReg, layoutsReg, spec.brand || {});
    shell.appendChild(board);
    cell.appendChild(shell);
    const cap = document.createElement('div');
    cap.className = 'cap';
    cap.textContent = label;
    cell.appendChild(cap);
    (container || sheet).appendChild(cell);
    boards.push(board);
    return board;
  }

  try {
    await ensureBrandFont(spec.brand);
    const isSequence = spec.creative.sequenceKind && Array.isArray(spec.creative.sequence) && spec.creative.sequence.length;
    const motion = spec.creative.motion;
    const hasKeyTimes = !isSequence && Array.isArray(spec.times) && spec.times.length && motion;

    if (isSequence) {
      const frames = resolveSequence(spec.creative, placementsReg, layoutsReg);
      const key = spec.creative.sequenceKind === 'carousel' ? spec.creative.carouselRatio || '4x5' : 'story';
      frames.forEach((frame, i) => addCell(frame, key, `${i + 1} of ${frames.length}`));
    } else if (hasKeyTimes) {
      // One row per key moment, each row holding every enabled placement at that time — lets a human
      // see how the motion progresses without downloading the full MP4 (see job/render.mjs's
      // previewOnly MOTION path).
      sheet.classList.add('sheet--rows');
      const ad = resolveAd(spec.creative, placementsReg, layoutsReg);
      const keys = Array.isArray(spec.placementKeys) && spec.placementKeys.length
        ? spec.placementKeys
        : Object.keys(placementsReg).filter((k) => placementsReg[k].default);
      spec.times.forEach((t) => {
        const row = document.createElement('div');
        row.className = 'row';
        sheet.appendChild(row);
        keys.forEach((key) => {
          const label = `${(placementsReg[key] && placementsReg[key].label) || key} · ${t.toFixed(1)}s`;
          const board = addCell(ad, key, label, row);
          motionCells.push({ board, time: t });
        });
      });
    } else {
      const ad = resolveAd(spec.creative, placementsReg, layoutsReg);
      const keys = Array.isArray(spec.placementKeys) && spec.placementKeys.length
        ? spec.placementKeys
        : Object.keys(placementsReg).filter((k) => placementsReg[k].default);
      keys.forEach((key) => addCell(ad, key, (placementsReg[key] && placementsReg[key].label) || key));
    }

    // Every photo and logo must have arrived before fitting (a logo's width shapes the layout) and
    // before the job screenshots the sheet: a sheet captured mid-load showed boards with no photo and no
    // logo, which read as a render that had dropped them.
    await settleImages(sheet);

    // Fit every board at its TRUE, unscaled size first (a CSS transform
    // shrinks the measured rect and would throw fitBoard's line-count math
    // off), then apply the display scale — the same order mount.js uses.
    await fitAll(sheet, placementsReg);

    // Only after fitting (motion's translateY offsets are fixed px, independent of the fitted
    // headline size, but applying it before fitBoard risked the word-span wrapping interacting with
    // the auto-fit measurement — see motion.js).
    const durationSec = (motion && motion.durationSec) || 8;
    motionCells.forEach(({ board, time }) => applyMotion(board, motion, time, { durationSec }));

    boards.forEach((board) => {
      board.style.transformOrigin = 'top left';
      board.style.transform = `scale(${SHEET_SCALE})`;
    });

    const rect = sheet.getBoundingClientRect();
    finish({ ok: true, width: Math.round(rect.width), height: Math.round(rect.height) });
  } catch (err) {
    finish({ ok: false, error: String((err && err.message) || err) });
  }
}

main();
