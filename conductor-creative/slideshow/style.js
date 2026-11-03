// GENERATED from conductor-tools/src/lib/slideshow/style.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
/**
 * Slideshow style: the look of the composer's native text and callouts. The native look (bundled TikTok
 * Sans, white/black boxes, #ff2d55 callouts) is the default; a Brand Kit's `slideshowStyle` and/or an inline
 * `style` override any of it. Mirrors the backend's `SlideshowStyle` schema. The rule constants below equal
 * `conductor-creative/slideshow-style.json` (asserted by slideshow-style-parity.test.ts).
 */
export const STYLE_TEXT_KINDS = ['box', 'dark', 'stroke'];
export const STYLE_COLOR_KEYS = ['boxBg', 'boxInk', 'darkBg', 'darkInk', 'strokeInk', 'outline', 'accent'];
export const STYLE_KEYS = ['fontFamily', 'fontUrl', ...STYLE_COLOR_KEYS, 'defaultText', 'textSize', 'subSize'];
export const NATIVE_STYLE = Object.freeze({
    fontFamily: null,
    fontUrl: null,
    boxBg: '#ffffff',
    boxInk: '#111111',
    darkBg: '#111111',
    darkInk: '#ffffff',
    strokeInk: '#ffffff',
    outline: '#000000',
    accent: '#ff2d55',
    defaultText: 'box',
    textSize: 60,
    subSize: 44,
});
export const HEX_PATTERN = /^#[0-9a-fA-F]{6}$/;
// eslint-disable-next-line no-control-regex
export const FAMILY_BAD = /[\u0000-\u001f\u007f-\u009f"';{}<>\\]/;
export const FONT_URL_PATTERN = /^https:\/\/[^\s"'<>\\]+$/;
export const MAX_FONT_FAMILY = 200;
export const MAX_FONT_URL = 2048;
export const STYLE_BOUNDS = { textSize: { min: 24, max: 120 }, subSize: { min: 16, max: 96 } };
export const GENERIC_FAMILIES = ['serif', 'sans-serif', 'monospace', 'cursive', 'fantasy', 'system-ui', 'ui-sans-serif', 'ui-serif', 'ui-monospace', 'ui-rounded', 'emoji', 'math', 'fangsong', '-apple-system', 'BlinkMacSystemFont'];
const GENERIC_CANONICAL = new Map(GENERIC_FAMILIES.map((g) => [g.toLowerCase(), g]));
/** True for a CSS generic or system-font keyword (case-insensitive), which is never a custom font to check or load. */
export const isGenericFamily = (name) => GENERIC_CANONICAL.has(name.toLowerCase());
/** The family names of a font-family list: split on commas, trimmed, blanks dropped. */
export function fontFamilyNames(family) {
    return family.split(',').map((f) => f.trim()).filter(Boolean);
}
/** A font-family list as CSS: each family quoted unless it is a generic/system keyword (emitted unquoted, canonical casing). */
export function fontFamilyCss(family) {
    return fontFamilyNames(family)
        .map((f) => GENERIC_CANONICAL.get(f.toLowerCase()) ?? `"${f}"`)
        .join(',');
}
/** The first family of each list, compared case-insensitively and trimmed. */
const sameFamily = (a, b) => (fontFamilyNames(a)[0] ?? '').toLowerCase() === (fontFamilyNames(b)[0] ?? '').toLowerCase();
const isObj = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
/**
 * Every problem with a (partial) style; empty = valid. Same rules as the backend (fontFamily: trimmed, blank =
 * unset, 1-200 chars, a comma-separated CSS family list without control characters or any of " ' ; { } < > and backslash;
 * fontUrl: blank = unset, https, <= 2048 chars, no whitespace or " ' < > \). Unknown keys are errors unless
 * `ignoreUnknown` (a style read from a Brand Kit).
 */
export function validateSlideshowStyle(style, at = 'style', ignoreUnknown = false) {
    if (!isObj(style))
        return [`${at} must be an object`];
    const errors = [];
    if (!ignoreUnknown) {
        for (const key of Object.keys(style)) {
            if (!STYLE_KEYS.includes(key))
                errors.push(`${at}.${key} is not a style field; use ${STYLE_KEYS.join(', ')}`);
        }
    }
    const v = (k) => style[k];
    if (v('fontFamily') != null) {
        const f = v('fontFamily');
        const t = typeof f === 'string' ? f.trim() : '';
        if (typeof f !== 'string' || (t !== '' && (t.length > MAX_FONT_FAMILY || FAMILY_BAD.test(t) || fontFamilyNames(t).length === 0))) {
            errors.push(`${at}.fontFamily must be a font name or comma-separated list up to 200 characters, without quotes or any of ; { } < > \\`);
        }
    }
    if (v('fontUrl') != null) {
        const url = v('fontUrl');
        const t = typeof url === 'string' ? url.trim() : '';
        const ok = typeof url === 'string' && (t === '' || (t.length <= MAX_FONT_URL && FONT_URL_PATTERN.test(t)));
        if (!ok)
            errors.push(`${at}.fontUrl must be an https URL to a CSS stylesheet (for example a Google Fonts link)`);
    }
    for (const k of STYLE_COLOR_KEYS) {
        if (v(k) != null && !(typeof v(k) === 'string' && HEX_PATTERN.test(v(k))))
            errors.push(`${at}.${k} must be a #RRGGBB hex colour, got ${JSON.stringify(v(k))}`);
    }
    if (v('defaultText') != null && !STYLE_TEXT_KINDS.includes(v('defaultText'))) {
        errors.push(`${at}.defaultText must be one of ${STYLE_TEXT_KINDS.join(', ')}`);
    }
    const int = (k, lo, hi) => {
        const n = v(k);
        if (n != null && !(typeof n === 'number' && Number.isInteger(n) && n >= lo && n <= hi))
            errors.push(`${at}.${k} must be a whole number of pixels from ${lo} to ${hi}`);
    };
    int('textSize', STYLE_BOUNDS.textSize.min, STYLE_BOUNDS.textSize.max);
    int('subSize', STYLE_BOUNDS.subSize.min, STYLE_BOUNDS.subSize.max);
    return errors;
}
export class SlideshowStyleError extends Error {
    constructor(errors, 
    /** Every layer that has a problem (empty when the caller did not track layers). */
    sources = []) {
        super(`Invalid slideshow style:\n${errors.map((e) => `  - ${e}`).join('\n')}`);
        this.errors = errors;
        this.sources = sources;
        this.name = 'SlideshowStyleError';
    }
}
/** Drops keys that are not style fields (a Brand Kit's style may carry fields a newer backend added). */
export function pickKnownStyleFields(style) {
    if (!isObj(style))
        return undefined;
    const out = {};
    for (const k of STYLE_KEYS)
        if (k in style)
            out[k] = style[k];
    return out;
}
const blank = (s) => (typeof s === 'string' && s.trim() !== '' ? s.trim() : null);
/**
 * Layers already-validated styles over the native look, later layers winning. In a layer an absent field (or a
 * blank font) inherits from the layer below; a field explicitly `null` resets that field to the native value
 * (`fontFamily: null` is TikTok Sans and also clears `fontUrl`). A layer that sets a *different* first
 * `fontFamily` (case-insensitive) and gives no `fontUrl` clears the inherited `fontUrl`.
 */
export function mergeSlideshowStyles(...layers) {
    const out = { ...NATIVE_STYLE };
    for (const layer of layers) {
        if (layer == null)
            continue;
        for (const k of STYLE_COLOR_KEYS) {
            if (layer[k] === null)
                out[k] = NATIVE_STYLE[k];
            else if (layer[k] !== undefined)
                out[k] = layer[k].toLowerCase();
        }
        for (const k of ['defaultText', 'textSize', 'subSize']) {
            if (layer[k] === null)
                out[k] = NATIVE_STYLE[k];
            else if (layer[k] !== undefined)
                out[k] = layer[k];
        }
        const family = blank(layer.fontFamily);
        const url = blank(layer.fontUrl);
        if (layer.fontFamily === null) {
            out.fontFamily = null;
            out.fontUrl = null;
        }
        else if (family != null) {
            if (out.fontFamily == null || !sameFamily(out.fontFamily, family))
                out.fontUrl = null;
            out.fontFamily = family;
        }
        if (layer.fontUrl === null)
            out.fontUrl = null;
        else if (url != null)
            out.fontUrl = url;
    }
    return out;
}
/** Validates then merges the layers (labelled style[i], or `style` for one); throws SlideshowStyleError. */
export function resolveSlideshowStyle(...layers) {
    const errors = layers.flatMap((layer, i) => (layer == null ? [] : validateSlideshowStyle(layer, layers.length > 1 ? `style[${i}]` : 'style')));
    if (errors.length > 0)
        throw new SlideshowStyleError(errors);
    return mergeSlideshowStyles(...layers);
}
/**
 * The one place a slideshow's style layers are resolved: native look < Brand Kit < the spec's own `style` <
 * inline `style`. The kit layer (`kitLabel` names it in errors) tolerates unknown fields and treats nulls as
 * "not set"; the inline layer rejects unknown fields. The spec's own `style` is validated with the rest of the
 * spec (assertValidSlideshowSpec), so it is only merged here. Throws SlideshowStyleError listing every problem.
 */
export function resolveStyleLayers(layers) {
    const found = [
        ['inline', layers.inline == null ? [] : validateSlideshowStyle(layers.inline, 'style')],
        ['kit', layers.kit == null ? [] : validateSlideshowStyle(layers.kit, layers.kitLabel ?? 'kit.style', true)],
        ['spec', layers.spec == null ? [] : validateSlideshowStyle(layers.spec, 'spec.style')],
    ];
    const failing = found.filter(([, errs]) => errs.length > 0);
    if (failing.length > 0)
        throw new SlideshowStyleError(failing.flatMap(([, errs]) => errs), failing.map(([source]) => source));
    return mergeSlideshowStyles(layers.kit == null ? undefined : kitStyleLayer(layers.kit), layers.spec, layers.inline);
}
/** A Brand Kit's style: unknown fields dropped, nulls removed (a kit's null means "not set by the kit"). */
export function kitStyleLayer(style) {
    const picked = pickKnownStyleFields(style);
    if (!picked)
        return undefined;
    return Object.fromEntries(Object.entries(picked).filter(([, v]) => v !== null));
}
/** True when a (kit) style sets at least one known field to a non-null value: otherwise it is "no style". */
export function hasAnyStyleField(style) {
    if (!isObj(style))
        return false;
    return STYLE_KEYS.some((k) => {
        const v = style[k];
        if (v === null || v === undefined)
            return false;
        // a blank font is unset, as the backend treats it
        return (k === 'fontFamily' || k === 'fontUrl') && typeof v === 'string' ? v.trim() !== '' : true;
    });
}
