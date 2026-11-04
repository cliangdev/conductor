// GENERATED from conductor-tools/src/lib/slideshow/text-fields.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
/**
 * The editable text of a slideshow spec. The registry (which fields, how long) is shared with the web editor
 * and the backend through conductor-creative/slideshow-text-fields.json; slideshow-text-fields-parity.test.ts
 * keeps the constants here equal to that file.
 *
 * A pattern is a dotted path from one slide with `[]` for "any index" (`rows[].items[]`). A concrete path
 * (`rows[1].items[0]`) is editable when replacing each index with `[]` gives a pattern AND the value at that
 * path is already a string. Edits replace existing strings only: they never add or remove array entries or
 * create absent fields. Slides are 1-based everywhere in this module.
 */
import type { SlideshowSpec } from './spec.js';
export declare const TEXT_FIELD_PATTERNS: readonly string[];
export declare const MAX_TEXT_LENGTH = 500;
export interface TextField {
    /** 1-based slide number. */
    slide: number;
    /** Concrete path from the slide, e.g. `texts[0].t` or `rows[1].items[0]`. */
    path: string;
    text: string;
}
export interface TextEdit {
    slide: number;
    path: string;
    text: string;
}
/** One changed field between two specs; `old`/`new` are null when the field is absent on that side. */
export interface TextDiffEntry {
    slide: number;
    path: string;
    old: string | null;
    new: string | null;
}
/** `rows[1].items[0]` -> `rows[].items[]`. */
export declare const patternOf: (path: string) => string;
/** True when the path is a well-formed concrete path whose pattern is in the registry (the value is not looked at). */
export declare function isEditablePath(path: string): boolean;
/** The editable text fields of a spec, slide by slide (key order within a slide). */
export declare function listTextFields(spec: SlideshowSpec | unknown): TextField[];
/**
 * Returns a copy of the spec with each edit applied. Throws (and applies nothing) when an edit names a slide
 * that does not exist, a path outside the registry, a path that is absent or not a string, or a text longer
 * than {@link MAX_TEXT_LENGTH}. Later edits to the same field win.
 */
export declare function applyTextEdits<T extends SlideshowSpec>(spec: T, edits: readonly TextEdit[]): T;
/**
 * One entry per editable field whose string differs between two specs, over the slides both have (a slide
 * that exists in only one of them gets no entries). A field present on one side only has null on the other.
 */
export declare function diffText(a: SlideshowSpec | unknown, b: SlideshowSpec | unknown): TextDiffEntry[];
