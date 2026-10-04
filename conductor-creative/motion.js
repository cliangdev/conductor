/* motion.js — the MOTION creative's animation timeline: a pure function of
 * time. Everything here computes, for a given `board` (a `.cc-board` element
 * `render.js`'s `renderBoard` already built) and a time `tSec`, the exact
 * inline styles/CSS vars that time's frame should show, and writes them.
 * Nothing here uses `setTimeout`, a CSS `@keyframes`/`animation`, or `Date`.
 *
 * That purity is the whole point: the headless render job (`job/render.mjs`)
 * captures a MOTION video by calling `applyMotion(board, motion, t)` for a
 * sequence of `t` values and screenshotting after each call — a "frame-
 * stepped" capture. If `applyMotion` depended on wall-clock time or a running
 * CSS animation, two calls at the same `t` could render two different
 * pixels, and the exported video would not match what the editor's live
 * preview showed. `mount.js`'s `play()` (real-time, via
 * `requestAnimationFrame`) and the render job's frame stepper both end up
 * calling this exact same function — that is what keeps the live preview and
 * the exported MP4 from ever drifting apart, the same guarantee `render.js`
 * already gives the STILL frame vs. the editor's preview.
 *
 * A `motion` object (see README.md and the T-video contract):
 *   {
 *     preset: 'fade-up' | 'word-by-word' | 'accent-pop' | 'none',  // default 'fade-up'
 *     durationSec: number,                                        // default 8
 *     background: { motion: 'zoom-in'|'zoom-out'|'pan-left'|'pan-right'|'none' }, // default 'zoom-in'
 *     endCard: boolean,                                            // default true
 *   }
 *
 * Every element's intro is keyed to seconds-from-start, independent of
 * `durationSec` (a 3s and a 60s creative both fade the lockup in over
 * 0.2-0.6s) — except the end card, which is always the LAST 2 seconds,
 * however long the creative is. When `endCard` is true (the default) and
 * `tSec` falls in that final window, every text/lockup/CTA element is forced
 * to its finished, fully-visible state regardless of where its own intro
 * timeline would otherwise put it — a 3s creative's CTA (whose intro window
 * is 1.8-2.3s) still needs to be showing throughout the 1-3s hold. Background
 * photo/clip motion is on its own separate, continuous timeline (the whole
 * `0..durationSec` span) and is never affected by the end-card hold or by
 * `preset` — panning/zooming is what makes a still photo read as video for
 * the full runtime, hold included.
 */

/* ── easing + small numeric helpers (exported: pure, used by mount.js/tests) */

const clamp01 = (x) => Math.max(0, Math.min(1, x));
const lerp = (a, b, t) => a + (b - a) * t;

/** Standard easeOutCubic: fast start, gentle settle. Used for every fade/
 * transform interpolation in this file, including the background pan/zoom. */
export function easeOutCubic(x) {
  const t = clamp01(x);
  return 1 - Math.pow(1 - t, 3);
}

/** Eased 0-1 progress of `tSec` through the window `[start, end]`. `end <=
 * start` is treated as an instant switch at `start` (never divides by zero). */
function windowProgress(tSec, start, end) {
  if (end <= start) return tSec >= start ? 1 : 0;
  return easeOutCubic(clamp01((tSec - start) / (end - start)));
}

/* ── per-element timing windows (seconds from the creative's own start) ──── */

const LOCKUP_WINDOW = [0.2, 0.6];
const HEADLINE_FADEUP_WINDOW = [0.3, 0.9];
const ACCENT_POP_FADE_WINDOW = [0.3, 0.8];
const ACCENT_POP_POP_WINDOW = [0.8, 1.1];
const WORD_WINDOW = [0.3, 1.5];
const WORD_FADE_DURATION = 0.3; // each word's own fade length within WORD_WINDOW
const BODY_WINDOW = [1.2, 1.7];
const CTA_WINDOW = [1.8, 2.3];
const FADE_UP_PX = 24; // headline/body/cta translateY start offset
const WORD_UP_PX = 16; // a smaller offset per word reads better than 24px x N words
const END_CARD_LEAD_SEC = 2; // the hold window is [durationSec - 2, durationSec]

/* ── DOM helpers: always set BOTH opacity and transform on every call, never
 * only one — mount.js's live preview scrubs `t` forward and backward, so a
 * value this function does not explicitly set this call is a value left over
 * from a previous call, not "unset". That is the one correctness rule this
 * whole file exists to uphold. */

function setFade(el, progress, translateY) {
  if (!el) return;
  el.style.opacity = String(progress);
  if (translateY == null) return;
  const y = lerp(translateY, 0, progress);
  el.style.transform = y === 0 ? 'none' : `translateY(${round2(y)}px)`;
}

function finalState(el) {
  if (!el) return;
  el.style.opacity = '1';
  el.style.transform = 'none';
}

function round2(n) {
  return Math.round(n * 100) / 100;
}

/* ── word-by-word: wrap the headline's own text into <span class="cc-word">,
 * once, idempotently (a rebuilt board from a fresh renderBoard() call always
 * starts unwrapped; the same board is safe to wrap repeatedly across seeks). */

function ensureWordSpans(headline) {
  if (headline.querySelector('.cc-word')) return;
  wrapWordsInPlace(headline);
}

function wrapWordsInPlace(container) {
  const nodes = Array.from(container.childNodes);
  const frag = container.ownerDocument.createDocumentFragment();
  for (const node of nodes) {
    if (node.nodeType === 3) {
      // TEXT_NODE: split on whitespace, keeping the whitespace itself as plain text nodes so word
      // spacing/wrapping behaves exactly as it did before splitting.
      const parts = node.textContent.split(/(\s+)/);
      for (const part of parts) {
        if (!part) continue;
        if (/^\s+$/.test(part)) {
          frag.appendChild(container.ownerDocument.createTextNode(part));
        } else {
          const span = container.ownerDocument.createElement('span');
          span.className = 'cc-word';
          span.textContent = part;
          frag.appendChild(span);
        }
      }
    } else if (node.nodeType === 1) {
      // ELEMENT_NODE: the accent <em> — wrap its own words too, keep the <em> wrapper (and its
      // italic/accent-color styling) intact around them.
      wrapWordsInPlace(node);
      frag.appendChild(node);
    } else {
      frag.appendChild(node);
    }
  }
  container.textContent = '';
  container.appendChild(frag);
}

/* ── accent-pop: how far a word may grow without eating the spaces around it ─────────────────────
 * A scaled inline-block keeps its layout box, so growing it moves its ink into the neighbouring spaces:
 * at 1.18x a long accent word swallowed the space before it ("slightdelay") for the first half second.
 * The pop is therefore limited so each side grows by at most POP_GAP_EM of the font size (two accent words share one space, so each takes only a sliver of it), and only on a
 * side that HAS a space (a word glued to punctuation, like "delay" in "delay.", does not grow towards it);
 * the origin is placed so the growth lands on the open sides. Layout-less environments (a DOM shim)
 * report no width and get the plain, uncapped pop. */

const POP_PEAK = 1.18;
const POP_GAP_EM = 0.04;

function hasGap(word, direction) {
  const prop = direction < 0 ? 'previousSibling' : 'nextSibling';
  let node = word[prop];
  // The first/last word of the accent <em> neighbours whatever sits beside the <em> itself.
  if (!node && word.parentNode && word.parentNode.tagName === 'EM') node = word.parentNode[prop];
  if (!node) return true; // a line edge: nothing to run into
  if (node.nodeType !== 3) return false;
  return direction < 0 ? /\s$/.test(node.textContent) : /^\s/.test(node.textContent);
}

/** Pure-ish (reads the word's measured width and font size): `{ peak, originX }` — the largest pop scale
 * that keeps the word clear of its neighbours, and the transform-origin x (percent) that puts the growth
 * on the sides that have a space. */
function popLimits(word) {
  const width = word.offsetWidth;
  const view = word.ownerDocument && word.ownerDocument.defaultView;
  const size = view && view.getComputedStyle ? parseFloat(view.getComputedStyle(word).fontSize) : NaN;
  if (!(width > 0) || !(size > 0)) return { peak: POP_PEAK, originX: 50 };
  const budget = POP_GAP_EM * size;
  const left = hasGap(word, -1) ? budget : 0;
  const right = hasGap(word, 1) ? budget : 0;
  if (left + right === 0) return { peak: 1, originX: 50 };
  return {
    peak: Math.min(POP_PEAK, 1 + (left + right) / width),
    originX: round2((100 * left) / (left + right)),
  };
}

/* ── headline (preset-dependent) ──────────────────────────────────────────── */

function applyHeadline(board, preset, tSec, inEndCard) {
  const headline = board.querySelector('.cc-headline');
  if (!headline) return;

  if (preset === 'word-by-word') {
    ensureWordSpans(headline);
    headline.style.opacity = '1';
    headline.style.transform = 'none';
    const words = headline.querySelectorAll('.cc-word');
    const n = words.length;
    const [start, end] = WORD_WINDOW;
    const span = n > 1 ? Math.max(0, (end - WORD_FADE_DURATION - start) / (n - 1)) : 0;
    words.forEach((word, i) => {
      if (inEndCard) {
        finalState(word);
        return;
      }
      const wordStart = start + i * span;
      const progress = windowProgress(tSec, wordStart, wordStart + WORD_FADE_DURATION);
      setFade(word, progress, WORD_UP_PX);
    });
    return;
  }

  if (preset === 'none') {
    finalState(headline);
    const em = headline.querySelector('em');
    if (em) {
      em.style.transform = 'none';
      em.style.filter = 'none';
    }
    return;
  }

  if (preset === 'accent-pop') {
    // Transforms don't apply to inline text, so the accent phrase's words are wrapped in inline-block
    // spans (wrapping between words is unchanged) and each word scales about its own centre.
    const em = headline.querySelector('em');
    if (em && !em.querySelector('.cc-word')) wrapWordsInPlace(em);
    const accentWords = em ? em.querySelectorAll('.cc-word') : [];
    if (inEndCard) {
      finalState(headline);
      accentWords.forEach((w) => {
        w.style.transform = 'none';
        w.style.filter = 'none';
      });
      return;
    }
    const fadeProgress = windowProgress(tSec, ACCENT_POP_FADE_WINDOW[0], ACCENT_POP_FADE_WINDOW[1]);
    setFade(headline, fadeProgress, null);
    const popProgress = windowProgress(tSec, ACCENT_POP_POP_WINDOW[0], ACCENT_POP_POP_WINDOW[1]);
    // Scale settles peak → 1; brightness pulses up and back to 1, so the end card starts with no jump.
    const brightness = round2(1 + 0.3 * Math.sin(Math.PI * popProgress));
    accentWords.forEach((w) => {
      const { peak, originX } = popLimits(w);
      const scale = round2(lerp(peak, 1, popProgress));
      w.style.transformOrigin = `${originX}% 70%`;
      w.style.transform = scale === 1 ? 'none' : `scale(${scale})`;
      w.style.filter = brightness === 1 ? 'none' : `brightness(${brightness})`;
    });
    return;
  }

  // 'fade-up' (default)
  if (inEndCard) {
    finalState(headline);
    return;
  }
  const progress = windowProgress(tSec, HEADLINE_FADEUP_WINDOW[0], HEADLINE_FADEUP_WINDOW[1]);
  setFade(headline, progress, FADE_UP_PX);
}

/* ── lockup / body / cta: a shared fade(-up) treatment ────────────────────── */

function applyFadeElement(board, selector, [start, end], tSec, preset, inEndCard, translateY) {
  const el = board.querySelector(selector);
  if (!el) return;
  if (preset === 'none' || inEndCard) {
    finalState(el);
    return;
  }
  const progress = windowProgress(tSec, start, end);
  setFade(el, progress, translateY);
}

/* ── background photo/clip motion ─────────────────────────────────────────── */

const ZOOM_IN = [1, 1.12];
const ZOOM_OUT = [1.12, 1];
const PAN_SCALE = 1.08;
const PAN_OFFSET_PCT = 4;

/** Pure: the background's scale + horizontal pan (percent, ± PAN_OFFSET_PCT)
 * at `tSec` of `durationSec`, given `motion.background.motion`. Runs the
 * whole duration, eased, independent of preset/end-card. Exported for tests
 * and for anything that wants the numbers without touching the DOM. */
export function backgroundMotionState(motion, tSec, durationSec) {
  const kind = (motion && motion.background && motion.background.motion) || 'zoom-in';
  const progress = durationSec > 0 ? easeOutCubic(clamp01(tSec / durationSec)) : 0;
  if (kind === 'zoom-in') return { scale: round2(lerp(ZOOM_IN[0], ZOOM_IN[1], progress)), panPct: 0 };
  if (kind === 'zoom-out') return { scale: round2(lerp(ZOOM_OUT[0], ZOOM_OUT[1], progress)), panPct: 0 };
  if (kind === 'pan-left') return { scale: PAN_SCALE, panPct: round2(lerp(PAN_OFFSET_PCT, -PAN_OFFSET_PCT, progress)) };
  if (kind === 'pan-right') return { scale: PAN_SCALE, panPct: round2(lerp(-PAN_OFFSET_PCT, PAN_OFFSET_PCT, progress)) };
  return { scale: 1, panPct: 0 }; // 'none'
}

/** Pure: the rect (relative to the board/band/card it fills) the background layer occupies once
 * `backgroundMotionState`'s transform is applied to a layer that exactly fills a `w` x `h` box. The
 * transform is `translateX(panPct%) scale(scale)` (translate first, so the pan is in the BOX's own
 * pixels, not the scaled layer's): a scale >= 1 overhangs each side by (scale - 1) / 2, and the pan is
 * kept inside that overhang, so the layer covers the box at every `t`. Exported for tests. */
export function backgroundLayerRect(w, h, state) {
  const { scale, panPct } = state;
  const width = w * scale;
  const height = h * scale;
  const left = (w - width) / 2 + (panPct / 100) * w;
  const top = (h - height) / 2;
  return { left, top, right: left + width, bottom: top + height, width, height };
}

function applyBackgroundMotion(board, motion, tSec, durationSec) {
  const { scale, panPct } = backgroundMotionState(motion, tSec, durationSec);
  // One CSS var, consumed by every layout's photo layer, with a no-op fallback so a STILL board (which
  // never calls applyMotion at all) is untouched: `.cc-bg-photo` (bleed's photo layer),
  // `.cc-board__band img` / `.cc-board__card img` and `.cc-bg-video`. The layer is `cover`-sized, never
  // scaled below 1 and never panned past its overhang, so no edge or repeat can show (see
  // backgroundLayerRect). The focal point stays the layer's own object-/background-position.
  board.style.setProperty('--cc-bg-transform', `translateX(${panPct}%) scale(${scale})`);
}

/* ── the public entry point ───────────────────────────────────────────────── */

/** Sets every inline style/CSS var a MOTION board needs to look right at
 * `tSec`, on `board` (a live `.cc-board` from `renderBoard`, already
 * `fitBoard`-ed). `motion` is `creative.motion` (see the header comment);
 * `opts.durationSec` overrides `motion.durationSec` when the caller has
 * already resolved it. Safe to call every frame, in any order of `tSec`
 * (forward playback, scrubbing backward, or the render job's frame-stepped
 * capture) — every call fully determines every value it touches. Returns a
 * small summary (`{ preset, durationSec, inEndCard, background }`), mostly
 * useful for tests. */
export function applyMotion(board, motion, tSec, opts) {
  if (!board) return null;
  motion = motion || {};
  const durationSec = (opts && opts.durationSec) || motion.durationSec || 8;
  const preset = motion.preset || 'fade-up';
  const endCardEnabled = motion.endCard !== false;
  const inEndCard = endCardEnabled && tSec >= durationSec - END_CARD_LEAD_SEC;

  applyHeadline(board, preset, tSec, inEndCard);
  applyFadeElement(board, '.cc-lockup', LOCKUP_WINDOW, tSec, preset, inEndCard, null);
  applyFadeElement(board, '.cc-body', BODY_WINDOW, tSec, preset, inEndCard, FADE_UP_PX);
  applyFadeElement(board, '.cc-cta', CTA_WINDOW, tSec, preset, inEndCard, FADE_UP_PX);

  const background = backgroundMotionState(motion, tSec, durationSec);
  applyBackgroundMotion(board, motion, tSec, durationSec);

  return { preset, durationSec, inEndCard, background };
}

/** Three representative moments for a `previewOnly` MOTION key-moments sheet:
 * an early beat (past the lockup/headline intro), the midpoint, and a late
 * beat inside the end-card hold. `motion.durationSec` defaults to 8 like
 * everywhere else. */
export function motionKeyTimes(motion) {
  const durationSec = (motion && motion.durationSec) || 8;
  const early = 0.6;
  const mid = round2(durationSec / 2);
  const late = round2(Math.max(early, durationSec - 0.5));
  return [early, mid, late];
}

/* ── clip background video: shared by mount.js's live-preview seek() and the
 * headless frame.js/job frame-stepped capture, so a clip background is
 * frame-accurate in both. No-ops (resolves immediately) when `board` has no
 * `.cc-bg-video` (a photo background, or a STILL board). */
export function seekBackgroundVideo(board, tSec, clipStartSec) {
  const video = board && board.querySelector && board.querySelector('.cc-bg-video');
  if (!video) return Promise.resolve();
  const target = Math.max(0, (clipStartSec || 0) + (tSec || 0));
  return new Promise((resolve) => {
    let settled = false;
    const done = () => {
      if (settled) return;
      settled = true;
      video.removeEventListener('seeked', onSeeked);
      resolve();
    };
    const onSeeked = () => done();
    video.addEventListener('seeked', onSeeked);
    // A video that never loads (a bad URL, an unreachable fixture in a test) must not hang the
    // caller forever — a MOTION render with an unreachable clip should fail loudly downstream (a
    // black/frozen frame trips the end-card assertions or an obviously wrong ffmpeg duration), not
    // hang the job or the editor.
    setTimeout(done, 4000);
    try {
      video.currentTime = target;
    } catch {
      done();
    }
  });
}


/* The API describes a clip background under `motion.background` ({source:'clip', clipUrl, clipStartSec});
 * the engine reads `backgroundVideoUrl`/`clipStartSec` on the creative itself (the editor fills those
 * directly). This bridges the two so a render spec from the API gets its clip background and clip sound. */
export function normalizeMotionCreative(creative) {
  if (!creative || !creative.motion || !creative.motion.background) return creative;
  const bg = creative.motion.background;
  if (bg.source !== 'clip' || !bg.clipUrl) return creative;
  return {
    ...creative,
    backgroundVideoUrl: creative.backgroundVideoUrl || bg.clipUrl,
    clipStartSec: creative.clipStartSec != null ? creative.clipStartSec : (bg.clipStartSec || 0),
  };
}
