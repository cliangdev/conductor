// GENERATED from conductor-tools/src/lib/slideshow/style.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
/**
 * Slideshow style: the look of the composer's native text and callouts. The native look (bundled TikTok
 * Sans, white/black boxes, #ff2d55 callouts) is the default; a Brand Kit's `slideshowStyle` and/or an inline
 * `style` override any of it. Mirrors the backend's `SlideshowStyle` schema. The rule constants below equal
 * `conductor-creative/slideshow-style.json` (asserted by slideshow-style-parity.test.ts).
 */
export declare const STYLE_TEXT_KINDS: readonly ["box", "dark", "stroke"];
export type StyleTextKind = (typeof STYLE_TEXT_KINDS)[number];
export declare const STYLE_COLOR_KEYS: readonly ["boxBg", "boxInk", "darkBg", "darkInk", "strokeInk", "outline", "accent"];
export declare const STYLE_KEYS: readonly ["fontFamily", "fontUrl", "boxBg", "boxInk", "darkBg", "darkInk", "strokeInk", "outline", "accent", "defaultText", "textSize", "subSize"];
/** A partial style as stored on a Brand Kit or Creative (null/absent = not set). */
export interface SlideshowStyle {
    fontFamily?: string | null;
    fontUrl?: string | null;
    boxBg?: string | null;
    boxInk?: string | null;
    darkBg?: string | null;
    darkInk?: string | null;
    strokeInk?: string | null;
    outline?: string | null;
    accent?: string | null;
    defaultText?: StyleTextKind | null;
    textSize?: number | null;
    subSize?: number | null;
}
/** Every colour/size set; only the font may be null (null family = bundled TikTok Sans). */
export interface ResolvedSlideshowStyle {
    fontFamily: string | null;
    fontUrl: string | null;
    boxBg: string;
    boxInk: string;
    darkBg: string;
    darkInk: string;
    strokeInk: string;
    outline: string;
    accent: string;
    defaultText: StyleTextKind;
    textSize: number;
    subSize: number;
}
export declare const NATIVE_STYLE: Readonly<ResolvedSlideshowStyle>;
export declare const HEX_PATTERN: RegExp;
export declare const FAMILY_BAD: RegExp;
export declare const FONT_URL_PATTERN: RegExp;
export declare const MAX_FONT_FAMILY = 200;
export declare const MAX_FONT_URL = 2048;
export declare const STYLE_BOUNDS: {
    readonly textSize: {
        readonly min: 24;
        readonly max: 120;
    };
    readonly subSize: {
        readonly min: 16;
        readonly max: 96;
    };
};
export declare const GENERIC_FAMILIES: readonly ["serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui", "ui-sans-serif", "ui-serif", "ui-monospace", "ui-rounded", "emoji", "math", "fangsong", "-apple-system", "BlinkMacSystemFont"];
/** True for a CSS generic or system-font keyword (case-insensitive), which is never a custom font to check or load. */
export declare const isGenericFamily: (name: string) => boolean;
/** The family names of a font-family list: split on commas, trimmed, blanks dropped. */
export declare function fontFamilyNames(family: string): string[];
/** A font-family list as CSS: each family quoted unless it is a generic/system keyword (emitted unquoted, canonical casing). */
export declare function fontFamilyCss(family: string): string;
/**
 * Every problem with a (partial) style; empty = valid. Same rules as the backend (fontFamily: trimmed, blank =
 * unset, 1-200 chars, a comma-separated CSS family list without control characters or any of " ' ; { } < > and backslash;
 * fontUrl: blank = unset, https, <= 2048 chars, no whitespace or " ' < > \). Unknown keys are errors unless
 * `ignoreUnknown` (a style read from a Brand Kit).
 */
export declare function validateSlideshowStyle(style: unknown, at?: string, ignoreUnknown?: boolean): string[];
/** Where a style layer came from, so an error names the right one without matching message text. */
export type StyleSource = 'kit' | 'spec' | 'inline';
export declare class SlideshowStyleError extends Error {
    readonly errors: string[];
    /** Every layer that has a problem (empty when the caller did not track layers). */
    readonly sources: StyleSource[];
    constructor(errors: string[], 
    /** Every layer that has a problem (empty when the caller did not track layers). */
    sources?: StyleSource[]);
}
/** Drops keys that are not style fields (a Brand Kit's style may carry fields a newer backend added). */
export declare function pickKnownStyleFields(style: unknown): SlideshowStyle | undefined;
/**
 * Layers already-validated styles over the native look, later layers winning. In a layer an absent field (or a
 * blank font) inherits from the layer below; a field explicitly `null` resets that field to the native value
 * (`fontFamily: null` is TikTok Sans and also clears `fontUrl`). A layer that sets a *different* first
 * `fontFamily` (case-insensitive) and gives no `fontUrl` clears the inherited `fontUrl`.
 */
export declare function mergeSlideshowStyles(...layers: Array<SlideshowStyle | null | undefined>): ResolvedSlideshowStyle;
/** Validates then merges the layers (labelled style[i], or `style` for one); throws SlideshowStyleError. */
export declare function resolveSlideshowStyle(...layers: Array<SlideshowStyle | null | undefined>): ResolvedSlideshowStyle;
/**
 * The one place a slideshow's style layers are resolved: native look < Brand Kit < the spec's own `style` <
 * inline `style`. The kit layer (`kitLabel` names it in errors) tolerates unknown fields and treats nulls as
 * "not set"; the inline layer rejects unknown fields. The spec's own `style` is validated with the rest of the
 * spec (assertValidSlideshowSpec), so it is only merged here. Throws SlideshowStyleError listing every problem.
 */
export declare function resolveStyleLayers(layers: {
    kit?: unknown;
    kitLabel?: string;
    spec?: SlideshowStyle | null;
    inline?: SlideshowStyle | null;
}): ResolvedSlideshowStyle;
/** A Brand Kit's style: unknown fields dropped, nulls removed (a kit's null means "not set by the kit"). */
export declare function kitStyleLayer(style: unknown): SlideshowStyle | undefined;
/** True when a (kit) style sets at least one known field to a non-null value: otherwise it is "no style". */
export declare function hasAnyStyleField(style: unknown): boolean;
