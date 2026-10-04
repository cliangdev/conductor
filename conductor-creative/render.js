/* render.js — builds one artboard from a creative + placement, and fits its
 * type. Brand-agnostic: every color, logo and CTA string comes from a `brand`
 * object handed to renderBoard, never a constant in this file.
 *
 * A creative needs only four things to render: a photo, a headline, a body
 * and a layout. Everything else has a default, and every default can be
 * overridden per creative.
 *
 *   { layout: 'stacked' | 'bleed' | 'card' | 'split', theme: 'dark' | 'light',
 *     photoUrl, headline: 'Plan the week in *one sentence*.', body: '…' }
 *
 * `*asterisks*` mark the accent italic phrase: exactly one per headline,
 * never two. That is a structural convention of this render engine, not a
 * brand value — the color it takes (`--cc-accent`) IS the brand value.
 *
 * A brand is:
 *   {
 *     tokens?: { accent, accent2, darkBg, darkInk, lightBg, lightCard, ink, ink2 },
 *     fontFamily?: string,
 *     fontUrl?: string,               // loaded by mount.js's loadFont(), not here
 *     logos?: { mark?, wordmarkDark?, wordmarkLight?, badge? },  // URLs
 *     ctaClaim?: string,
 *   }
 * Every key is optional. A brand with no logos renders no lockup and no
 * badge; a brand with no ctaClaim renders no claim text; a brand with no
 * tokens renders with tokens.css's neutral fallbacks. See applyBrandTokens().
 *
 * Placements (artboard sizes: 9x16, 4x5, 1x1, ...) live in placements.json /
 * placements.js. Layouts (visual structures: stacked, bleed, card, split)
 * live in layouts/<name>/layout.json + layout.css. Neither is hardcoded here:
 * this file reads whichever registries the caller hands it, so a new
 * placement or layout is a data change, not a code change.
 *
 * NOTHING structural may be position:absolute. A DOM-screenshotting exporter
 * (the future headless render job) can drop absolutely positioned layers, so
 * photos are background-image on the artboard or a band, scrims are
 * background-image on the text panel, and everything else stays in normal
 * flow.
 *
 * This module touches `document` only inside functions that build or measure
 * DOM (renderBoard, fitBoard, fitAll, and their private helpers). The pure
 * functions (resolveAd, resolveSequence, layoutFor, enabledPlacements,
 * leadingFor, trackingFor, tokensToCssVars) never do, so they run in plain
 * Node for tests. It fetches nothing at import time.
 */

const FOCAL_DEFAULT = '50% 50%';

/* Leading and tracking follow the size. Both rules are read off hand-tuned
 * reference frames, where tracking is consistently about -0.03 x size. */
export function leadingFor(size) {
  return size >= 100 ? 0.96 : size >= 70 ? 1.0 : 1.06;
}
export function trackingFor(size) {
  return Math.round(-0.03 * size * 10) / 10;
}

/* ── Brand tokens → CSS custom properties ─────────────────────────────────── */

/* Maps a brand's token keys to the CSS custom properties tokens.css and
 * frame.css read. Only the keys a brand actually sets are touched, so a kit
 * missing a token falls back to tokens.css's neutral default rather than an
 * empty string. */
const TOKEN_CSS_VARS = {
  accent: '--cc-accent',
  accent2: '--cc-accent-2',
  darkBg: '--cc-d-bg',
  darkInk: '--cc-d-ink',
  lightBg: '--cc-l-bg',
  lightCard: '--cc-l-card',
  ink: '--cc-ink',
  ink2: '--cc-ink-2',
};

/* Pure: brand.tokens -> { '--cc-accent': '#...', ... }, only for keys present.
 * Used by tests and by anything that wants the mapping without touching the
 * DOM (e.g. server-side rendering of the CSS for a contact sheet). */
export function tokensToCssVars(tokens) {
  const out = {};
  if (!tokens) return out;
  for (const [key, cssVar] of Object.entries(TOKEN_CSS_VARS)) {
    if (tokens[key] != null) out[cssVar] = tokens[key];
  }
  return out;
}

/* Applies a brand's tokens (and font family) as inline custom properties on
 * `el` — scoped to that element, never `:root`, so two different brand kits
 * can render on the same page without one overwriting the other's colors. */
export function applyBrandTokens(el, brand) {
  const vars = tokensToCssVars(brand && brand.tokens);
  for (const [cssVar, value] of Object.entries(vars)) el.style.setProperty(cssVar, value);
  if (brand && brand.fontFamily) {
    el.style.setProperty('--cc-font', `'${brand.fontFamily}', system-ui, -apple-system, sans-serif`);
  }
}

/* ── Resolving a creative into a renderable ad ────────────────────────────── */

/* Fills in every default so the rest of the code can assume a complete ad.
 * `raw` mirrors the backend creative shape (camelCase): layout, theme,
 * headline, body, caption, photoUrl, focal (per-placement "x% y%" carried on
 * the photo), focalOverride (per-creative override, wins over focal),
 * layoutOverrides ({band?, padBottom?}, each per-placement pixel overrides),
 * lockup ('plain' | 'chip'), typeOverrides. `placements` and `layouts` are
 * the registries. */
export function resolveAd(raw, placements, layouts) {
  raw = raw || {};
  const layout = raw.layout || 'stacked';
  const focal = {};
  for (const key of Object.keys(placements)) {
    focal[key] =
      (raw.focalOverride && raw.focalOverride[key]) ||
      (raw.focal && raw.focal[key]) ||
      FOCAL_DEFAULT;
  }
  const layoutBand = (layouts[layout] && layouts[layout].band) || {};
  const padBottomDefault = {};
  for (const key of Object.keys(placements)) padBottomDefault[key] = placements[key].safe.bottom;
  const layoutOverrides = raw.layoutOverrides || {};
  return {
    layout,
    theme: raw.theme || 'dark',
    lockup: raw.lockup || 'plain',
    photoUrl: raw.photoUrl || null,
    // A MOTION creative's clip background (see motion.js/README): renderBoard renders a muted
    // <video class="cc-bg-video"> over the photo area when this is set, alongside (in front of, as a
    // fallback while it loads) whatever photoUrl is also set.
    backgroundVideoUrl: raw.backgroundVideoUrl || null,
    headline: raw.headline || '',
    body: raw.body || '',
    caption: raw.caption || '',
    focal,
    band: Object.assign({}, layoutBand, layoutOverrides.band),
    padBottom: Object.assign({}, padBottomDefault, layoutOverrides.padBottom),
    type: raw.typeOverrides || null, // null means auto-fit
    showCta: true,
  };
}

/* A story or carousel is a sequence of frames a viewer taps or swipes
 * through, not one frame doing every job. Each frame inherits the creative
 * (photo, layout, theme) and overrides only what changes, so a three-beat
 * story is three short headlines rather than three whole creatives.
 *
 * The CTA row (logo lockup badge + claim) lands on the LAST frame only by
 * default: most badge/claim artwork is meant to appear once per surface, and
 * a story's real call to action is the platform's own link sticker, so a
 * frame that makes no offer should not carry one. A frame can force it either
 * way with `cta: true | false`.
 *
 * `raw.sequenceKind` is `'story' | 'carousel'` (or unset/null for no
 * sequence); `raw.sequence` is the beat/card array. Both a story and a
 * carousel use this same mechanism — carousel just renders at a feed ratio
 * with every card sharing one aspect. */
export function resolveSequence(raw, placements, layouts) {
  raw = raw || {};
  const seq = raw.sequence;
  if (!raw.sequenceKind || !Array.isArray(seq) || !seq.length) return [];
  const last = seq.length - 1;
  return seq.map((frame, i) => {
    // A beat inherits the creative for anything it leaves out, and the API sends an unset beat field as
    // null: a null must not overwrite the creative's own value (a beat with `photoUrl: null` rendered
    // with no photo at all).
    const own = Object.fromEntries(Object.entries(frame).filter(([, v]) => v != null));
    const merged = resolveAd({ ...raw, ...own, sequence: undefined, sequenceKind: undefined }, placements, layouts);
    // null and undefined both mean "not set" (the API sends null): the CTA then lands on the last frame only.
    merged.showCta = frame.cta != null ? frame.cta : i === last;
    // Body is per-frame opt-in: a hook frame can be a headline and nothing else.
    merged.body = frame.body !== undefined ? frame.body || '' : '';
    merged.isSequence = true;
    merged.isStory = raw.sequenceKind === 'story';
    merged.isCarousel = raw.sequenceKind === 'carousel';
    merged.seqIndex = i + 1;
    merged.seqTotal = seq.length;
    return merged;
  });
}

/* Which layout structure a creative + placement uses. A layout's own
 * perPlacement map can swap in a different layout (and force a panel
 * variant) for one placement key, which is how "every square is bleed"
 * (stacked and bleed both carry a 1x1 override) works without ratio-specific
 * branches in code. */
export function layoutFor(ad, placementKey, layouts) {
  const manifest = layouts[ad.layout];
  const override = manifest.perPlacement && manifest.perPlacement[placementKey];
  const kind = (override && override.layout) || ad.layout;
  const panel = (override && override.panel) || layouts[kind].panel;
  return { kind, panel };
}

/* The default set of placements a creative renders at: a brand kit's
 * `enabledPlacements` (falling back to placements.json's `default: true`
 * keys when the kit does not specify any) plus whatever extra placements the
 * creative itself opts into via `creative.placements`. */
export function enabledPlacements(creative, brandEnabledPlacements, placements) {
  const base =
    Array.isArray(brandEnabledPlacements) && brandEnabledPlacements.length
      ? brandEnabledPlacements
      : Object.keys(placements).filter((k) => placements[k].default);
  const extra = Array.isArray(creative && creative.placements) ? creative.placements : [];
  return [...new Set([...base, ...extra])];
}

/* ── DOM building (browser or a DOM shim such as linkedom) ────────────────── */

function el(tag, className, parent) {
  const n = document.createElement(tag);
  if (className) n.className = className;
  if (parent) parent.appendChild(n);
  return n;
}

/* Renders only when the brand has a mark and/or a wordmark image. A brand
 * with neither renders no lockup at all — there is nothing brand-neutral to
 * fall back to, and a placeholder logo would be worse than none.
 *
 * `lockup: 'chip'` puts the lockup on a white pill (frame.css's `.cc-lockup--chip`),
 * for busy photography — ported from nexus's schema.json/render.js. A chip
 * sits on light ground regardless of the artboard's own theme, so it needs
 * the dark-ink wordmark just like a light theme does. */
function buildLockup(brand, theme, lockup, parent, reserveSpace = false) {
  const logos = (brand && brand.logos) || {};
  const hasMark = Boolean(logos.mark);
  const onLight = theme === 'light' || lockup === 'chip';
  // Dark artboards need the light-ink wordmark; a light artboard (or a chip,
  // which is always light ground) needs the dark-ink one. Either falls back
  // to the other when only one is supplied.
  const wordmarkSrc = onLight
    ? logos.wordmarkDark || logos.wordmarkLight
    : logos.wordmarkLight || logos.wordmarkDark;
  if (!hasMark && !wordmarkSrc) {
    // No logo: on a placement whose top edge is covered by platform UI (9:16, stories), keep the
    // lockup's footprint as an invisible spacer so the headline stays where it would sit under a logo,
    // clear of that zone, instead of rising into it.
    if (!reserveSpace) return null;
    const spacer = el('div', 'cc-lockup cc-lockup--spacer', parent);
    spacer.setAttribute('aria-hidden', 'true');
    el('div', 'cc-lockup__icon', spacer);
    return spacer;
  }

  const wrap = el('div', 'cc-lockup' + (lockup === 'chip' ? ' cc-lockup--chip' : ''), parent);
  if (hasMark) {
    const icon = el('img', 'cc-lockup__icon', wrap);
    icon.src = logos.mark;
    icon.alt = (brand && brand.name) || '';
  }
  if (wordmarkSrc) {
    const mark = el('img', 'cc-lockup__mark', wrap);
    mark.src = wordmarkSrc;
    mark.alt = (brand && brand.name) || '';
  }
  return wrap;
}

/* 'Plan the week in *one sentence*.' -> text, accent <em>, text */
function buildHeadline(ad, parent) {
  const h = el('h1', 'cc-headline', parent);
  const m = /^(.*?)\*(.+?)\*(.*)$/s.exec(ad.headline || '');
  if (!m) {
    h.textContent = ad.headline || '';
    return h;
  }
  if (m[1]) h.appendChild(document.createTextNode(m[1]));
  const em = el('em', null, h);
  em.textContent = m[2];
  // Italic correction: a slanted accent leans into the upright word before it (and its last letter
  // overhangs the word after), so a plain word space reads as no space at all ("slightdelay").
  // Widen only the gaps that are real spaces, never one against glued punctuation.
  if (/\s$/.test(m[1])) em.classList.add('cc-accent-after-space');
  if (/^\s/.test(m[3])) em.classList.add('cc-accent-before-space');
  if (m[3]) h.appendChild(document.createTextNode(m[3]));
  return h;
}

/* Renders only when the brand has a badge image and/or a ctaClaim string. */
function buildCta(brand, parent, placement) {
  const hasBadge = Boolean(brand && brand.logos && brand.logos.badge);
  const hasClaim = Boolean(brand && brand.ctaClaim);
  if (!hasBadge && !hasClaim) return null;

  const row = el('div', 'cc-cta', parent);
  if (hasBadge) {
    const badge = el('img', 'cc-badge', row);
    badge.src = brand.logos.badge;
    // Decorative: the badge conveys nothing a screen reader needs beyond
    // what the surrounding claim text (or the destination itself) already says.
    badge.alt = '';
    const h = placement.badgeHeight;
    badge.style.height = h + 'px';
    // Width is left to the image's own intrinsic aspect ratio rather than a
    // fixed constant: a brand's badge artwork is not assumed to be any one
    // store's badge. Clear space of one quarter the badge's height is a
    // reasonable default for badge-style artwork in general.
    badge.style.margin = Math.ceil(h / 4) + 'px';
  }
  if (hasClaim) el('span', 'cc-cta__note', row).textContent = brand.ctaClaim;
  return row;
}

/* A MOTION creative's clip background: a muted, inline video layered over the photo area, honoring
 * the same focal/object-fit every layout already gives its photo (`.cc-bg-video`'s CSS lives in
 * frame.css — one generic rule for every layout — plus a small addition to each layout's own photo
 * rule for the pan/zoom transform; see motion.js). Placed AFTER the photo element/background (which
 * still renders as a same-frame fallback while the video loads) and BEFORE any scrim/panel, so it
 * layers on top of the photo and under the copy without any explicit z-index. Muted: this element is
 * never the audio source a viewer hears — the render job mixes audio into the exported MP4 itself
 * (job/render.mjs); the browser preview never plays clip/track audio (see README). */
function buildBgVideo(parent, videoUrl) {
  const video = el('video', 'cc-bg-video', parent);
  video.src = videoUrl;
  video.muted = true;
  video.setAttribute('muted', '');
  video.playsInline = true;
  video.setAttribute('playsinline', '');
  video.preload = 'auto';
  video.setAttribute('preload', 'auto');
  return video;
}

/* Applies a headline size plus its derived leading and tracking. */
function applyType(board, size, leading, tracking) {
  board.style.setProperty('--cc-h-size', size + 'px');
  board.style.setProperty('--cc-h-leading', leading != null ? leading : leadingFor(size));
  board.style.setProperty('--cc-h-track', (tracking != null ? tracking : trackingFor(size)) + 'px');
}

/* True when nothing in the panel crosses the artboard's edges or intrudes on
 * the placement's bottom safe zone. These are the same tests a headless
 * exporter would run before writing a PNG, so fitting and that safety net
 * cannot disagree. */
function fits(board) {
  const br = board.getBoundingClientRect();
  const safeBottom = Number(board.dataset.safeBottom) || 0;
  for (const n of board.querySelectorAll('.cc-headline, .cc-body, .cc-cta, .cc-lockup')) {
    const r = n.getBoundingClientRect();
    if (r.top < br.top - 1 || r.right > br.right + 1) return false;
    if (r.bottom > br.bottom - safeBottom + 1) return false;
  }
  return true;
}

/* Steps the headline down from the placement's max until the board fits.
 * Must run AFTER the board is in the document (a detached element measures
 * zero) and AFTER document.fonts.ready (or it measures the fallback face).
 * Steps rather than binary-searches because `text-wrap: balance` makes height
 * non-monotonic in size, so a midpoint that fits does not prove the ones
 * below it do. */
export function fitBoard(board, placements) {
  if (board.dataset.autofit !== 'true') return;
  const { max, min, lines } = placements[board.dataset.ratio].fit;
  const headline = board.querySelector('.cc-headline');
  for (let size = max; size >= min; size -= 2) {
    applyType(board, size);
    const used = Math.round(headline.getBoundingClientRect().height / (size * leadingFor(size)));
    if (used <= lines && fits(board)) return size;
  }
  applyType(board, min);
  return min;
}

/* Fits every board under `root` once fonts are settled. */
export async function fitAll(root, placements) {
  if (typeof document !== 'undefined' && document.fonts && document.fonts.ready) await document.fonts.ready;
  (root || document).querySelectorAll('.cc-board').forEach((b) => fitBoard(b, placements));
}

/* Sets one CSS custom property per placement.scale entry, so frame.css and
 * the layout stylesheets read `var(--cc-foo)` instead of a per-ratio
 * selector. `px: true` entries are bare numbers that need a unit; the rest
 * (colors, the unitless line-height) are used as written. */
const SCALE_PROPS = {
  lockupGap: { prop: '--cc-lockup-gap', px: true },
  iconSize: { prop: '--cc-icon-size', px: true },
  iconRadius: { prop: '--cc-icon-radius', px: true },
  markHeight: { prop: '--cc-mark-height', px: true },
  chipPadding: { prop: '--cc-chip-pad' },
  chipGap: { prop: '--cc-chip-gap', px: true },
  chipIcon: { prop: '--cc-chip-icon', px: true },
  chipIconRadius: { prop: '--cc-chip-icon-radius', px: true },
  chipMark: { prop: '--cc-chip-mark', px: true },
  bodySize: { prop: '--cc-body-size', px: true },
  bodyLineHeight: { prop: '--cc-body-lh' },
  bodyMaxWidth: { prop: '--cc-body-max-w', px: true },
  bodyColor: { prop: '--cc-body-color' },
  lightBodySize: { prop: '--cc-light-body-size', px: true },
  ctaGap: { prop: '--cc-cta-gap', px: true },
  ctaMarginTop: { prop: '--cc-cta-margin-top', px: true },
  noteSize: { prop: '--cc-note-size', px: true },
  noteColor: { prop: '--cc-note-color' },
  cardPad: { prop: '--cc-card-pad' },
  cardGap: { prop: '--cc-card-gap', px: true },
};
function applyScale(board, scale) {
  for (const [key, { prop, px }] of Object.entries(SCALE_PROPS)) {
    if (scale[key] === undefined) continue;
    board.style.setProperty(prop, scale[key] + (px ? 'px' : ''));
  }
}

/* Returns a finished .cc-board element for one ad at one placement, painted
 * with `brand`'s tokens, logos and CTA claim. `opts.safe` draws the
 * 9x16/story safe-zone guide, a review aid that must never reach an export. */
export function renderBoard(ad, placementKey, placements, layouts, brand, opts) {
  opts = opts || {};
  const placement = placements[placementKey];
  const { kind, panel: panelVariant } = layoutFor(ad, placementKey, layouts);
  const board = el('div',
    'cc-board cc-r-' + placementKey + ' cc-board--' + kind +
    (ad.theme === 'light' ? ' cc-board--light' : '') +
    // A sequence is tapped or swiped through, so the copy has to sit in the
    // same place on every frame. Distributing it would make it jump.
    (ad.isSequence ? ' cc-board--seq' : '') +
    (ad.isStory ? ' cc-board--story' : ''));
  board.dataset.ratio = placementKey;
  board.dataset.layout = kind;

  applyBrandTokens(board, brand);
  board.style.setProperty('--cc-board-w', placement.w + 'px');
  board.style.setProperty('--cc-board-h', placement.h + 'px');
  board.style.setProperty('--cc-focal', ad.focal[placementKey]);
  applyScale(board, placement.scale);
  if (ad.type && ad.type[placementKey]) {
    const [size, leading, tracking] = ad.type[placementKey];
    applyType(board, size, leading, tracking);
  } else {
    // Provisional size; fitBoard() replaces it once the board is measurable.
    board.dataset.autofit = 'true';
    applyType(board, placement.fit.max);
  }
  const safe = placement.safe;
  board.dataset.safeTop = safe.top;
  board.dataset.safeBottom = safe.bottom;
  board.dataset.safeRight = safe.right;
  board.style.setProperty('--cc-safe-top', safe.top + 'px');
  board.style.setProperty('--cc-safe-bottom', safe.bottom + 'px');
  board.style.setProperty('--cc-safe-right', safe.right + 'px');
  if (ad.band && ad.band[placementKey]) board.style.setProperty('--cc-band-h', ad.band[placementKey] + 'px');
  if (ad.padBottom && ad.padBottom[placementKey]) {
    board.style.setProperty('--cc-pad-b', ad.padBottom[placementKey] + 'px');
  }

  const photoType = layouts[kind].photo;
  let panel;
  if (photoType === 'background') {
    board.style.setProperty('--cc-photo', ad.photoUrl ? 'url("' + ad.photoUrl + '")' : 'none');
    // The photo is its own layer, not the board's background: a MOTION creative's Ken Burns zoom/pan is a
    // transform on this layer, which always covers the board (cover, no-repeat) whatever the zoom. A
    // background-size percentage on the board did not: it was relative to the photo's WIDTH, so a
    // landscape photo on a tall board ended up shorter than the board and tiled (see motion.js).
    if (ad.photoUrl) el('div', 'cc-bg-photo', board).setAttribute('aria-hidden', 'true');
    // The video (when present) must land BEFORE the panel, so the panel's own
    // stacking (position:relative, later in the DOM) renders above it.
    if (ad.backgroundVideoUrl) buildBgVideo(board, ad.backgroundVideoUrl);
    // The bottom panel variant hugs the bottom; the fill variant spreads its
    // copy over a full-artboard scrim.
    panel = el('div', 'cc-board__panel cc-board__panel--' + panelVariant, board);
  } else if (photoType === 'card') {
    buildLockup(brand, ad.theme, ad.lockup, board, placement.safe.top > 0);
    const card = el('div', 'cc-board__card', board);
    if (ad.photoUrl) {
      const cimg = el('img', null, card);
      cimg.src = ad.photoUrl;
      cimg.alt = '';
    }
    if (ad.backgroundVideoUrl) buildBgVideo(card, ad.backgroundVideoUrl);
    panel = el('div', 'cc-board__panel', board);
  } else {
    const band = el('div', 'cc-board__band', board);
    if (ad.photoUrl) {
      const img = el('img', null, band);
      img.src = ad.photoUrl;
      img.alt = '';
    }
    if (ad.backgroundVideoUrl) buildBgVideo(band, ad.backgroundVideoUrl);
    if (layouts[kind].bandScrim !== false) {
      el('div', 'cc-board__scrim', band);
      el('div', 'cc-board__glow', board);
    }
    panel = el('div', 'cc-board__panel', board);
  }

  // A layout with its lockup at the top of the page (card) sets
  // `lockupInPanel: false` and builds it above, before the photo.
  if (layouts[kind].lockupInPanel !== false) buildLockup(brand, ad.theme, ad.lockup, panel, placement.safe.top > 0);
  buildHeadline(ad, panel);
  const dropBody = (layouts[kind].dropBody || []).includes(placementKey);
  if (ad.body && !dropBody) {
    el('p', 'cc-body', panel).textContent = ad.body;
  }
  // A layout may not have room for a body line at some placements (stacked at 4x5): keep that behaviour,
  // but record it on the board so runAssertions can warn that supplied copy is not on the artwork.
  if (ad.body && dropBody) board.dataset.bodyDropped = 'true';
  if (ad.showCta !== false) buildCta(brand, panel, placement);

  if (opts.safe && placement.safe.bottom) {
    const guide = el('div', 'cc-safe', board);
    el('div', 'cc-safe__top', guide);
    el('div', 'cc-safe__bottom', guide);
    el('div', 'cc-safe__right', guide);
  }
  return board;
}
