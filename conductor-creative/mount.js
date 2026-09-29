/* mount.js — the browser-facing API: mount one placement of one creative into
 * a container element, keep it live as the creative or brand changes, and let
 * the caller drag a photo's focal point. Everything here touches the DOM
 * (document, Image, pointer events); render.js's own exports stay DOM-free
 * except where they must build or measure elements.
 *
 * No network call happens here beyond what the browser does to load an image
 * or a font (`brand.fontUrl`, `creative.photoUrl`, logo URLs) — no fetch of
 * JSON, no API round trip. That is what makes the editor's live preview live:
 * every keystroke re-renders and re-fits locally.
 */

import { resolveAd, resolveSequence, renderBoard, fitBoard, enabledPlacements } from './render.js';
import { placements as defaultPlacements } from './placements.js';
import { layouts as defaultLayouts } from './layouts/index.js';
import { loadFont } from './font.js';
import { applyMotion, seekBackgroundVideo } from './motion.js';

export { enabledPlacements };
export { loadFont };

function resolveFrame(creative, placementsReg, layoutsReg, sequenceIndex) {
  if (creative.sequenceKind && Array.isArray(creative.sequence) && creative.sequence.length) {
    const frames = resolveSequence(creative, placementsReg, layoutsReg);
    if (!frames.length) return resolveAd(creative, placementsReg, layoutsReg);
    const idx = Math.min(Math.max(sequenceIndex || 0, 0), frames.length - 1);
    return frames[idx];
  }
  return resolveAd(creative, placementsReg, layoutsReg);
}

/* Mounts one placement of one creative into `container`, at its true pixel
 * size, visually scaled down (CSS transform, `transform-origin: top left`) to
 * fit inside whatever size `container` happens to be. Fitting the headline
 * type always measures the board at its TRUE, unscaled size — the visual
 * scale is applied only after fitBoard has run, so the auto-fit math is never
 * thrown off by the display scale.
 *
 * options:
 *   creative       required. The creative shape (see render.js's resolveAd).
 *   brand          required (may be `{}`). The brand object renderBoard reads.
 *   placementKey   required. A key from the placements registry, e.g. '9x16'.
 *   scale          optional. Forces the display scale instead of auto-fitting
 *                  to the container (e.g. for a fixed-size thumbnail grid).
 *   sequenceIndex  optional. Which story/carousel frame to show (0-based);
 *                  ignored when the creative has no sequence.
 *   placements     optional registry override (default: placements.js).
 *   layouts        optional registry override (default: layouts/index.js).
 *
 * Returns { update(nextOptions), destroy() }. `update` merges `nextOptions`
 * onto the current ones (so `update({ creative: patched })` alone re-renders
 * with the same brand/placementKey) and re-fits. `destroy` removes everything
 * this call added to `container`. */
export function mountBoard(container, options) {
  const state = { placementsReg: options.placements || defaultPlacements, layoutsReg: options.layouts || defaultLayouts, ...options };

  const shell = document.createElement('div');
  shell.style.position = 'relative';
  shell.style.overflow = 'hidden';
  container.appendChild(shell);

  let board = null;
  let destroyed = false;
  let generation = 0;

  // MOTION playback state (see motion.js): currentT is the scrub position, reset to 0 whenever draw()
  // rebuilds the board (a new creative/brand/placement starts its preview from the top). `playing`
  // guards the requestAnimationFrame loop so pause() (or a rebuild mid-play) can stop it cleanly.
  let currentT = 0;
  let playing = false;
  // What the caller asked for (play()/pause()), kept across rebuilds and across a play() that lands before
  // the first board exists; `playing` is only whether the rAF loop is running right now.
  let wantPlaying = false;
  let rafId = null;
  const timeListeners = new Set();

  function motionOf() {
    return state.creative && state.creative.motion;
  }
  function durationSecOf() {
    const m = motionOf();
    return (m && m.durationSec) || 8;
  }

  // Each draw owns its board. A draw awaits fonts, so an update() or destroy() can land mid-draw: the
  // generation check drops a superseded draw instead of fitting a board that is gone or replaced.
  async function draw() {
    if (destroyed) return;
    const gen = ++generation;
    const stale = () => destroyed || gen !== generation;
    stopPlayback();
    await loadFont(state.brand);
    if (stale()) return;
    const placement = state.placementsReg[state.placementKey];
    const ad = resolveFrame(state.creative, state.placementsReg, state.layoutsReg, state.sequenceIndex);
    const next = renderBoard(ad, state.placementKey, state.placementsReg, state.layoutsReg, state.brand);
    next.style.transformOrigin = 'top left';
    next.style.transform = 'none';
    shell.innerHTML = '';
    shell.appendChild(next);
    board = next;

    // Fit at true (unscaled) size first...
    if (typeof document !== 'undefined' && document.fonts && document.fonts.ready) await document.fonts.ready;
    if (stale()) return;
    fitBoard(next, state.placementsReg);

    // A rebuilt board always starts its MOTION preview from t=0 — STILL creatives (no `motion`) never
    // touch applyMotion/seekBackgroundVideo at all, leaving their look exactly as before this feature.
    // Keep the scrub position across a rebuild (an edit while previewing the end card stays on the end
    // card), clamped to a possibly shorter duration.
    currentT = Math.min(currentT, durationSecOf());
    const motion = motionOf();
    if (motion) {
      applyMotion(next, motion, currentT, { durationSec: durationSecOf() });
      await seekBackgroundVideo(next, currentT, state.creative && state.creative.clipStartSec);
      if (stale()) return;
    }

    // ...then scale the whole board down to fit the container.
    const rect = container.getBoundingClientRect ? container.getBoundingClientRect() : { width: container.clientWidth, height: container.clientHeight };
    const s = state.scale != null
      ? state.scale
      : Math.max(0.001, Math.min(
          (rect.width || placement.w) / placement.w,
          (rect.height || placement.h) / placement.h
        ));
    shell.style.width = Math.round(placement.w * s) + 'px';
    shell.style.height = Math.round(placement.h * s) + 'px';
    next.style.transform = `scale(${s})`;

    // A play() that landed before this board existed, or playback a rebuild interrupted, resumes here.
    if (wantPlaying) startLoop();
  }

  /* Applies motion at `tSec` (clamped to [0, durationSec]) to the CURRENT board without rebuilding it,
   * awaits the clip background's own seek when one is present, and notifies onTime() listeners. A
   * no-op (resolves immediately) for a STILL creative (no `creative.motion`) or before the first
   * board exists. */
  async function seek(tSec) {
    if (!board || !motionOf()) return;
    const durationSec = durationSecOf();
    const t = Math.max(0, Math.min(durationSec, tSec));
    currentT = t;
    applyMotion(board, motionOf(), t, { durationSec });
    await seekBackgroundVideo(board, t, state.creative && state.creative.clipStartSec);
    timeListeners.forEach((cb) => cb(t));
  }

  function scheduleFrame(cb) {
    if (typeof requestAnimationFrame === 'function') return requestAnimationFrame(cb);
    return setTimeout(() => cb(Date.now()), 16);
  }
  function cancelFrame(id) {
    if (typeof cancelAnimationFrame === 'function') cancelAnimationFrame(id);
    else clearTimeout(id);
  }

  function stopPlayback() {
    playing = false;
    if (rafId != null) {
      cancelFrame(rafId);
      rafId = null;
    }
  }

  /* Plays the MOTION preview in real time via requestAnimationFrame, looping back to 0 at
   * durationSec. A no-op for a STILL creative or before the first board exists. */
  function play() {
    wantPlaying = true;
    startLoop();
  }

  function startLoop() {
    if (!board || !motionOf() || playing) return;
    playing = true;
    let last = null;
    const step = async (ts) => {
      if (!playing) return;
      if (last == null) last = ts;
      const deltaSec = Math.max(0, (ts - last) / 1000);
      last = ts;
      const durationSec = durationSecOf();
      let next = currentT + deltaSec;
      if (durationSec > 0 && next >= durationSec) next %= durationSec;
      await seek(next);
      if (!playing) return; // pause()/destroy() may have landed during the await
      rafId = scheduleFrame(step);
    };
    rafId = scheduleFrame(step);
  }

  function pause() {
    wantPlaying = false;
    stopPlayback();
  }

  /* Subscribes to every seek() (from play() or a manual scrub); returns an unsubscribe function. */
  function onTime(cb) {
    timeListeners.add(cb);
    return () => timeListeners.delete(cb);
  }

  const ready = draw();

  return {
    ready, // resolves once the first frame has drawn and fit; awaiting is optional
    get board() { return board; },
    get shell() { return shell; },
    async update(next) {
      Object.assign(state, next);
      await draw();
    },
    destroy() {
      destroyed = true;
      stopPlayback();
      timeListeners.clear();
      shell.remove();
      board = null;
    },
    seek,
    play,
    pause,
    onTime,
  };
}

/* Client-side mirror of the studio's drag-to-set-focal-point interaction.
 * `handle` is a mountBoard() return value; `onChange(xyString)` is called
 * with a "x% y%" string (rounded whole percentages, clamped 0-100) as the
 * pointer moves. This module never persists the value — the caller decides
 * whether it lands on `creative.focalOverride[placementKey]` or on the
 * photo's own `focal[placementKey]`, then calls `handle.update(...)` with the
 * patched creative so the board reflects it immediately. */
export function attachFocalDrag(handle, onChange, { onClick, threshold = 4 } = {}) {
  const shell = handle.shell;
  // A press only becomes a drag once the pointer moves `threshold` px; a plain click leaves the focal
  // point alone (and calls `onClick`, e.g. to open the frame full size) instead of jumping the photo
  // to wherever the click landed.
  let start = null;
  let dragging = false;
  const apply = (e) => {
    const box = shell.getBoundingClientRect();
    const x = Math.round(Math.min(100, Math.max(0, ((e.clientX - box.left) / box.width) * 100)));
    const y = Math.round(Math.min(100, Math.max(0, ((e.clientY - box.top) / box.height) * 100)));
    const value = `${x}% ${y}%`;
    if (handle.board) handle.board.style.setProperty('--cc-focal', value);
    onChange(value);
  };
  const onPointerDown = (e) => {
    start = { x: e.clientX, y: e.clientY };
    dragging = false;
    // Not every environment implements pointer capture (some DOM test shims
    // do not); dragging still works without it, it just will not continue
    // past the shell's own edges.
    if (shell.setPointerCapture) { try { shell.setPointerCapture(e.pointerId); } catch { /* no-op */ } }
  };
  const onPointerMove = (e) => {
    if (!start) return;
    if (!dragging && Math.hypot(e.clientX - start.x, e.clientY - start.y) < threshold) return;
    dragging = true;
    apply(e);
  };
  const onPointerUp = (e) => {
    const wasClick = start && !dragging;
    start = null;
    dragging = false;
    if (wasClick && onClick) onClick(e);
  };
  shell.addEventListener('pointerdown', onPointerDown);
  shell.addEventListener('pointermove', onPointerMove);
  shell.addEventListener('pointerup', onPointerUp);
  return {
    detach() {
      shell.removeEventListener('pointerdown', onPointerDown);
      shell.removeEventListener('pointermove', onPointerMove);
      shell.removeEventListener('pointerup', onPointerUp);
    },
  };
}
