// GENERATED from conductor-tools/src/lib/slideshow/spec.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
/**
 * Slideshow spec: the shape a native-looking organic slideshow (TikTok / Reels style) is described in.
 * Ported from a content run's hand-built compositor, so its specs render unchanged.
 */
import { type SlideshowStyle } from './style.js';
export declare const SLIDE_TYPES: readonly ["photo", "screen", "notes", "grid", "stack", "tier", "week", "receipt", "placard", "messages"];
export type SlideType = (typeof SLIDE_TYPES)[number];
export declare const TEXT_STYLES: readonly ["box", "dark", "stroke", "sub"];
export type TextStyle = (typeof TEXT_STYLES)[number];
export type SlideRatio = '9x16' | '4x5';
export declare const SLIDE_RATIOS: readonly SlideRatio[];
export declare const MAX_SLIDES = 35;
/** The current layout generation: tier/week/receipt default below the 290 px top band, text clear of the 162 px
 * right band. A spec stored without it was composed before, and is re-rendered with the old layout. */
export declare const SLIDESHOW_LAYOUT = 2;
export declare const isLegacyLayout: (spec: {
    layout?: unknown;
} | null | undefined) => boolean;
export interface SlideText {
    t: string;
    /** % of the 9:16 frame height. Omitted: joins the previous text's stack. */
    y?: number;
    fs?: number;
    ss?: number;
    style?: TextStyle;
    full?: boolean;
}
export interface SlideSpec {
    type?: SlideType;
    bg?: string;
    pos?: string;
    blur?: boolean;
    grade?: boolean;
    grain?: boolean;
    dim?: boolean | number;
    bgColor?: string;
    texts?: SlideText[];
    top?: number;
    top4?: number;
    shot?: string;
    phoneY?: number;
    pw?: number;
    rot?: number;
    cropH?: number;
    shift?: number;
    callouts?: Array<{
        x: number;
        y: number;
        label: string;
    }>;
    title?: string;
    date?: string;
    items?: Array<string | {
        t: string;
        done?: boolean;
        strike?: boolean;
    }>;
    paras?: string[];
    ls?: number;
    gap?: number;
    cols?: number;
    gridTop?: number;
    photos?: Array<{
        src: string;
        pos?: string;
        zoom?: number;
        x?: boolean;
        ok?: boolean;
        restore?: boolean;
        cap?: boolean;
        badge?: string;
        label?: string;
    }>;
    rows?: Array<{
        k: string;
        c: string;
        items: string[];
    }>;
    ts?: number;
    days?: Array<{
        d: string;
        t: string;
        img?: string;
        icon?: string;
        fog?: boolean;
    }>;
    head?: string;
    sections?: Array<{
        h: string;
        items: string[];
    }>;
    count?: string;
    y?: number;
    ex?: string;
    lines?: Array<{
        k?: string;
        v: string;
    }>;
    bubbles?: Array<{
        from: 'me' | 'them';
        t: string;
    }>;
}
export interface SlideshowSpec {
    id: string;
    grain?: boolean;
    /** The layout generation the slides were composed with; absent = before the 290 px top / 162 px right bands. */
    layout?: number;
    /** The look of the composed text and callouts (partial or resolved); absent = the native look. */
    style?: SlideshowStyle | null;
    slides: SlideSpec[];
}
export declare class SlideshowSpecError extends Error {
    readonly errors: string[];
    constructor(errors: string[]);
}
/** Returns every problem found (empty = valid). Never throws. */
export declare function validateSlideshowSpec(spec: unknown): string[];
export declare function assertValidSlideshowSpec(spec: unknown): asserts spec is SlideshowSpec;
