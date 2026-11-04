// GENERATED from conductor-tools/src/lib/slideshow/css.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
import { type ResolvedSlideshowStyle } from './style.js';
/**
 * Open Font License families bundled beside TikTok Sans (assets/fonts) for the slide types that imitate a system
 * UI. The Linux render image has none of the Apple fonts those slides used to ask for, so they ship their own:
 * the output is the same on every machine. `file` is under the fonts folder; each family is a list of weights.
 */
export declare const BUNDLED_FONTS: {
    readonly Inter: {
        readonly generic: "sans-serif";
        readonly faces: readonly [readonly [400, "inter-400.woff2"], readonly [600, "inter-600.woff2"], readonly [700, "inter-700.woff2"]];
    };
    readonly 'Source Serif 4': {
        readonly generic: "serif";
        readonly faces: readonly [readonly [400, "source-serif-4-400.woff2"], readonly [700, "source-serif-4-700.woff2"]];
    };
    readonly 'Courier Prime': {
        readonly generic: "monospace";
        readonly faces: readonly [readonly [400, "courier-prime-400.ttf"], readonly [700, "courier-prime-700.ttf"]];
    };
};
export type BundledFamily = keyof typeof BUNDLED_FONTS;
export declare const BUNDLED_FAMILIES: BundledFamily[];
/**
 * Slide stylesheet. `assetBase` is the URL (ending in "/") of the folder holding the bundled fonts;
 * `style` restyles the native text and callouts (default: the native look). Slide types that imitate a system
 * UI (notes, grid badges, week, receipt, placard, messages) use the bundled Inter, Source Serif 4 and Courier
 * Prime instead of Apple system fonts; `bundled` names the ones this slide uses, and only those are declared
 * (so a photo slide's stylesheet carries no font it never loads).
 */
export declare function slideCss(assetBase: string, style?: ResolvedSlideshowStyle, bundled?: readonly BundledFamily[]): string;
