// GENERATED from conductor-tools/src/lib/slideshow/html.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
import { type BundledFamily } from './css.js';
import { type ResolvedSlideshowStyle } from './style.js';
import type { SlideRatio, SlideshowSpec, SlideSpec } from './spec.js';
export declare const FRAME_W = 1080;
export declare const FRAME_H: Record<SlideRatio, number>;
/** TikTok / Reels overlay bands (px) on a 9:16 frame. */
export declare const SAFE: {
    readonly top: 290;
    readonly bottom: 430;
    readonly right: 140;
};
export declare const esc: (s: unknown) => string;
/**
 * y values are % of the 9:16 frame; on 4:5 they are mapped into the centre 1350 band.
 */
export declare function yPx(y: number, ratio: SlideRatio): number;
/** The bundled families a slide's type draws with (see BUNDLED_FONTS); the native TikTok Sans text is not one. */
export declare function bundledFamiliesFor(sl: SlideSpec): BundledFamily[];
/**
 * The full HTML document for one slide. Pure: image sources are used as given (the composer resolves them
 * to file URLs first); `assetBase` is the URL, ending in "/", of the folder holding the bundled fonts.
 */
export declare function buildSlideHtml(spec: SlideshowSpec, index: number, ratio: SlideRatio, assetBase: string, style?: ResolvedSlideshowStyle): string;
