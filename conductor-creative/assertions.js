/* assertions.js — the in-page checks that refuse a wrong render, ported from
 * nexus-marketing/social/export-png.mjs and made brand-agnostic: the accent
 * check reads brand.tokens.accent (via the board's own resolved --cc-accent
 * custom property) instead of a hardcoded Rexipe coral, and the font check
 * reads brand.fontFamily instead of a hardcoded "DM Sans". A brand kit with
 * neither set still gets a meaningful check — the board's actually-applied
 * value is what is verified, not a specific brand's value.
 *
 * Split in two for testability:
 *   - pure math (relativeLuminance, parseRgb, contrastRatio, spillDetect,
 *     safeZoneIntrusion): plain data in, plain data out, no DOM. Exercised
 *     directly with fixture rects in test/assertions.test.mjs.
 *   - runAssertions(board, placement, brand): reads the live DOM
 *     (getBoundingClientRect, getComputedStyle, document.fonts, Image
 *     loading) and calls the pure functions above. Only runs in a real
 *     browser (Playwright); this is what frame.js calls after fitBoard.
 *
 * These are the same checks fitBoard()'s own `fits()` helper in render.js
 * uses to decide whether type fits (spill + the bottom safe zone) — this
 * module reports the same two conditions as errors with a human-readable
 * message, plus checks fits() has no opinion on: fonts loaded, images
 * loaded (including a bleed layout's CSS background photo, invisible to a
 * plain <img> check), headline contrast, the accent colour, and the
 * rendered box size.
 *
 * Presence checks (`missingPhoto`, `missingLockup`, `missingHeadline`): every check above judges what
 * IS on the board, so a board that silently lost its photo or logo used to pass them all. The caller
 * says what the spec requires (`expect`, see expectedElements) and a required element that is absent,
 * collapsed or hidden fails. `bodyDropped` is the one warning in this family: a layout that has no room
 * for the body line at a placement drops it by design, but the author supplied that copy.
 */

/* ── pure math ─────────────────────────────────────────────────────────── */

/** WCAG relative luminance of an [r, g, b] (0-255) triplet. */
export function relativeLuminance([r, g, b]) {
  const [R, G, B] = [r, g, b].map((v) => {
    v /= 255;
    return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * R + 0.7152 * G + 0.0722 * B;
}

/** "rgb(1, 2, 3)" / "rgba(1, 2, 3, 0.5)" -> [1, 2, 3]; anything else -> null. */
export function parseRgb(str) {
  // Anchored, bounded and limited to digits, dots, commas and spaces, so no input can make it backtrack.
  const s = String(str || '').trim();
  if (s.length > 64) return null;
  const m = /^rgba?\(([0-9., ]+)\)$/.exec(s);
  if (!m) return null;
  const parts = m[1].split(',').map((s) => parseFloat(s));
  return parts.length >= 3 && parts.slice(0, 3).every((n) => !Number.isNaN(n)) ? parts.slice(0, 3) : null;
}

/** WCAG contrast ratio between two [r, g, b] triplets, rounded to 1dp. 3:1 is
 * the floor for large text, and every headline this engine renders is large. */
export function contrastRatio(fgRgb, bgRgb) {
  const l1 = relativeLuminance(fgRgb);
  const l2 = relativeLuminance(bgRgb);
  const ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  return Math.round(ratio * 10) / 10;
}

/** Nodes whose box crosses the artboard's own edges — a wrapped headline or a
 * too-tall CTA row bleeding off the visible artboard. `nodes` is
 * `[{ name, rect: {top,bottom,right} }]`; `boardRect` is the artboard's own
 * `{top,bottom,right}`. Returns human-readable messages, empty when clean. */
export function spillDetect(boardRect, nodes) {
  const issues = [];
  for (const { name, rect } of nodes) {
    if (rect.top < boardRect.top - 1) issues.push(`${name} over top by ${Math.round(boardRect.top - rect.top)}px`);
    if (rect.bottom > boardRect.bottom + 1) issues.push(`${name} past bottom by ${Math.round(rect.bottom - boardRect.bottom)}px`);
    if (rect.right > boardRect.right + 1) issues.push(`${name} past right by ${Math.round(rect.right - boardRect.right)}px`);
  }
  return issues;
}

/** Copy intruding on the placement's reserved bottom safe zone (where a
 * platform's own UI — Instagram's story CTA, TikTok's caption — sits).
 * Mirrors render.js's own `fits()` bottom check exactly, so fitting and this
 * safety net can never disagree. Only the bottom zone is enforced: the top
 * reserve varies far more per platform and is documented, not asserted, the
 * same call nexus-marketing's export-png.mjs made. */
export function safeZoneIntrusion(boardRect, nodes, safe) {
  const issues = [];
  const bottom = (safe && safe.bottom) || 0;
  if (!bottom) return issues;
  for (const { name, rect } of nodes) {
    const over = rect.bottom - (boardRect.bottom - bottom);
    if (over > 1) issues.push(`${name} intrudes ${Math.round(over)}px into the bottom safe zone (reserves ${bottom}px)`);
  }
  return issues;
}

/** Pure: what a render requires on the board, from the resolved ad and the brand kit it renders with —
 * `{ photo, lockup, headline }`. A photo is required when the ad has a photo URL (a clip-only background
 * does not need one); a lockup when the kit has a mark or a wordmark (a kit without logos renders none,
 * by design); a headline when the ad has one. Pass the result as `runAssertions`'s `expect`. */
export function expectedElements(ad, brand) {
  const logos = (brand && brand.logos) || {};
  return {
    photo: Boolean(ad && ad.photoUrl),
    lockup: Boolean(logos.mark || logos.wordmarkLight || logos.wordmarkDark),
    headline: Boolean(ad && ad.headline && String(ad.headline).trim()),
  };
}

/* ── DOM-dependent (real browser only) ────────────────────────────────────── */

const CHECKED_SELECTOR = '.cc-headline, .cc-body, .cc-cta, .cc-lockup';

function rectOf(node) {
  const r = node.getBoundingClientRect();
  return { top: r.top, bottom: r.bottom, right: r.right };
}

/* Resolves any valid CSS color string (hex, rgb(), a named color, or a
 * var() the browser has already inlined) to an [r,g,b] triplet by letting
 * the browser's own parser do the work via a throwaway element — the only
 * reliable way to normalize an arbitrary brand token value. */
function resolveCssColor(value) {
  const probe = document.createElement('span');
  probe.style.display = 'none';
  probe.style.color = value;
  document.body.appendChild(probe);
  const rgb = parseRgb(getComputedStyle(probe).color);
  probe.remove();
  return rgb;
}

function sameRgb(a, b) {
  return Boolean(a && b && a[0] === b[0] && a[1] === b[1] && a[2] === b[2]);
}

/* Runs every check against a live, fitted `.cc-board` and returns
 * `{ errors, warnings, width, height }`. `placement` is this placement's own
 * registry entry (pixel size, safe zone); `brand` is the same object handed
 * to renderBoard; `expect` (optional, see expectedElements) names the elements the spec requires, so a
 * missing one fails. An empty `errors` array means the frame is safe to ship; `warnings` are non-fatal
 * (e.g. a soft, upscaled photo, or a body line the layout drops at this placement). */
export async function runAssertions(board, placement, brand, { outputScale = 2, expect = null } = {}) {
  const errors = [];
  const warnings = [];
  // The same findings with a stable `rule` id each, for callers (the draft preview) that report them
  // structured. `errors` / `warnings` stay plain message arrays, exactly as before.
  const checks = [];
  const fail = (rule, message) => {
    errors.push(message);
    checks.push({ rule, severity: 'error', message });
  };
  const warn = (rule, message, extra) => {
    warnings.push(message);
    checks.push({ rule, severity: 'warning', message, ...extra });
  };
  const br = rectOf(board);

  const nodes = [...board.querySelectorAll(CHECKED_SELECTOR)].map((n) => ({ name: n.className, rect: rectOf(n) }));

  const spill = spillDetect(br, nodes);
  if (spill.length) fail('spill', `text spill: ${spill.join('; ')}`);

  const safe = {
    top: Number(board.dataset.safeTop) || 0,
    bottom: Number(board.dataset.safeBottom) || 0,
    right: Number(board.dataset.safeRight) || 0,
  };
  const intrusion = safeZoneIntrusion(br, nodes, safe);
  if (intrusion.length) fail('safeZone', `safe-zone intrusion: ${intrusion.join('; ')}`);

  if (brand && brand.fontFamily) {
    const headlineEl = board.querySelector('.cc-headline');
    const size = Math.round(parseFloat(getComputedStyle(headlineEl).fontSize)) || 16;
    if (!document.fonts.check(`800 ${size}px "${brand.fontFamily}"`)) {
      fail('font', `font "${brand.fontFamily}" did not load (would export in a fallback face)`);
    }
  }

  // ── required elements: present, sized and visible (a spec that names them must get them) ──
  if (expect) {
    const shown = (n) => {
      if (!n) return false;
      const r = n.getBoundingClientRect();
      const cs = getComputedStyle(n);
      return r.width > 0 && r.height > 0 && cs.display !== 'none' && cs.visibility !== 'hidden';
    };
    if (expect.headline) {
      const h = board.querySelector('.cc-headline');
      if (!h || !h.textContent.trim() || !shown(h)) fail('missingHeadline', 'the headline is missing from the rendered frame');
    }
    if (expect.lockup && !shown(board.querySelector('.cc-lockup:not(.cc-lockup--spacer)'))) {
      fail('missingLockup', 'the brand logo lockup is missing from the rendered frame (the brand kit has a logo)');
    }
    if (expect.photo) {
      const layer = board.querySelector('.cc-bg-photo') || board.querySelector('.cc-board__band img, .cc-board__card img');
      const hasImage = layer && (layer.tagName === 'IMG'
        ? Boolean(layer.getAttribute('src')) && layer.complete && layer.naturalWidth > 0
        : /url\(/.test(getComputedStyle(layer).backgroundImage || ''));
      if (!shown(layer) || !hasImage) fail('missingPhoto', 'the photo is missing from the rendered frame (the spec has a photo URL)');
    }
  }
  if (board.dataset.bodyDropped === 'true') {
    const layout = board.dataset.layout || 'this layout';
    const key = board.dataset.ratio;
    warn('bodyDropped', `the body line is not shown: the ${layout} layout drops the body at ${key}. Put the words in the headline, or pick another layout or placement.`, { placementKey: key });
  }

  const imgs = [...board.querySelectorAll('img')];
  const broken = imgs.filter((i) => !i.complete || i.naturalWidth === 0).map((i) => i.getAttribute('src'));
  if (broken.length) fail('image', `image(s) failed to load: ${broken.join(', ')}`);

  // A bleed layout paints its photo as a CSS background (the `.cc-bg-photo` layer), invisible to
  // the <img> check above — probe it directly, the same way a typo'd path
  // would otherwise export a flat, photo-less board and pass everything else.
  const photoLayer = board.querySelector('.cc-bg-photo');
  const bgImage = getComputedStyle(photoLayer || board).backgroundImage;
  const bgUrlMatch = /url\("?(.+?)"?\)/.exec(bgImage || '');
  if (bgUrlMatch && bgUrlMatch[1]) {
    const probe = await new Promise((done) => {
      const img = new Image();
      img.onload = () => done({ ok: true, w: img.naturalWidth, h: img.naturalHeight });
      img.onerror = () => done({ ok: false });
      img.src = bgUrlMatch[1];
    });
    if (!probe.ok) {
      fail('image', `background photo failed to load: ${bgUrlMatch[1]}`);
    } else if (placement && probe.w < placement.w * outputScale && probe.h < placement.h * outputScale) {
      // Soft photography check: a source smaller than the output frame (2x for images, 1x for video) is
      // upscaled, so the photo goes soft while the text stays crisp.
      warn('photoResolution', `photo is ${probe.w}x${probe.h}, upscaled to fit ${placement.w * outputScale}x${placement.h * outputScale}`);
    }
  }

  const headlineEl = board.querySelector('.cc-headline');
  const em = headlineEl && headlineEl.querySelector('em');
  if (em) {
    const emRgb = parseRgb(getComputedStyle(em).color);
    const expectedRaw = getComputedStyle(board).getPropertyValue('--cc-accent').trim();
    const expectedRgb = expectedRaw ? resolveCssColor(expectedRaw) : null;
    if (!emRgb) {
      fail('accent', 'accent colour did not resolve on the headline');
    } else if (expectedRgb && !sameRgb(emRgb, expectedRgb)) {
      fail('accent', `accent colour unresolved: headline em is ${getComputedStyle(em).color}, brand accent resolves to ${expectedRaw}`);
    }
  }

  let contrast = null;
  if (headlineEl) {
    const fgRgb = parseRgb(getComputedStyle(headlineEl).color);
    let bgRgb = null;
    if (fgRgb) {
      for (let n = headlineEl; n && n !== document.body; n = n.parentElement) {
        const cs = getComputedStyle(n);
        if (cs.backgroundImage && cs.backgroundImage !== 'none') {
          const stops = (cs.backgroundImage.match(/rgba?\([^)]+\)/g) || []).map(parseRgb).filter(Boolean);
          if (stops.length) {
            // A scrim gradient: its darkest declared stop is the worst case.
            bgRgb = stops.reduce((worst, c) => (relativeLuminance(c) < relativeLuminance(worst) ? c : worst));
            break;
          }
        }
        const col = cs.backgroundColor;
        if (col && !/rgba?\(0,\s*0,\s*0,\s*0\)|transparent/.test(col)) {
          const rgb = parseRgb(col);
          if (rgb) { bgRgb = rgb; break; }
        }
      }
    }
    if (fgRgb && bgRgb) contrast = contrastRatio(fgRgb, bgRgb);
  }
  if (contrast !== null && contrast < 3) {
    fail('contrast', `headline contrast is ${contrast}:1 against its background, below the 3:1 floor for large text`);
  }

  const boxRect = board.getBoundingClientRect();
  const width = Math.round(boxRect.width);
  const height = Math.round(boxRect.height);
  if (placement && (width !== placement.w || height !== placement.h)) {
    fail('size', `artboard is ${width}x${height}, expected ${placement.w}x${placement.h}`);
  }

  return { errors, warnings, checks, contrast, width, height };
}
