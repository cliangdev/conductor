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
export const TEXT_FIELD_PATTERNS = [
    'texts[].t',
    'callouts[].label',
    'title',
    'date',
    'items[]',
    'items[].t',
    'paras[]',
    'photos[].badge',
    'photos[].label',
    'rows[].k',
    'rows[].items[]',
    'days[].d',
    'days[].t',
    'days[].icon',
    'head',
    'sections[].h',
    'sections[].items[]',
    'count',
    'ex',
    'lines[].k',
    'lines[].v',
    'bubbles[].t',
];
export const MAX_TEXT_LENGTH = 500;
const PATTERNS = new Set(TEXT_FIELD_PATTERNS);
const CONCRETE_PATH = /^[A-Za-z][A-Za-z0-9]*(\[\d+\])*(\.[A-Za-z][A-Za-z0-9]*(\[\d+\])*)*$/;
const isObj = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
/** `rows[1].items[0]` -> `rows[].items[]`. */
export const patternOf = (path) => path.replace(/\[\d+\]/g, '[]');
/** True when the path is a well-formed concrete path whose pattern is in the registry (the value is not looked at). */
export function isEditablePath(path) {
    return typeof path === 'string' && CONCRETE_PATH.test(path) && PATTERNS.has(patternOf(path));
}
/** `rows[1].items[0]` -> ['rows', 1, 'items', 0]. Assumes {@link CONCRETE_PATH} already matched. */
function segments(path) {
    const out = [];
    for (const part of path.split('.')) {
        const m = /^([A-Za-z][A-Za-z0-9]*)((?:\[\d+\])*)$/.exec(part);
        out.push(m[1]);
        for (const idx of m[2].matchAll(/\[(\d+)\]/g))
            out.push(Number(idx[1]));
    }
    return out;
}
const pathOf = (prefix, seg) => (typeof seg === 'number' ? `${prefix}[${seg}]` : prefix ? `${prefix}.${seg}` : seg);
/** Every editable string under one slide, in the slide's own key order. */
function walkSlide(value, prefix, out) {
    if (typeof value === 'string') {
        if (prefix && PATTERNS.has(patternOf(prefix)))
            out.push({ path: prefix, text: value });
        return;
    }
    if (Array.isArray(value))
        value.forEach((v, i) => walkSlide(v, pathOf(prefix, i), out));
    else if (isObj(value))
        for (const [k, v] of Object.entries(value))
            walkSlide(v, pathOf(prefix, k), out);
}
function slidesOf(spec) {
    return isObj(spec) && Array.isArray(spec['slides']) ? spec['slides'] : [];
}
/** The editable text fields of a spec, slide by slide (key order within a slide). */
export function listTextFields(spec) {
    const fields = [];
    slidesOf(spec).forEach((slide, i) => {
        const found = [];
        walkSlide(slide, '', found);
        for (const f of found)
            fields.push({ slide: i + 1, path: f.path, text: f.text });
    });
    return fields;
}
/**
 * Returns a copy of the spec with each edit applied. Throws (and applies nothing) when an edit names a slide
 * that does not exist, a path outside the registry, a path that is absent or not a string, or a text longer
 * than {@link MAX_TEXT_LENGTH}. Later edits to the same field win.
 */
export function applyTextEdits(spec, edits) {
    const copy = structuredClone(spec);
    const slides = slidesOf(copy);
    for (const edit of edits) {
        const where = `slide ${edit?.slide} ${edit?.path}`;
        if (!Number.isInteger(edit?.slide) || edit.slide < 1 || edit.slide > slides.length) {
            throw new Error(`${where}: slide ${edit?.slide} does not exist (the spec has ${slides.length} slides, numbered from 1)`);
        }
        if (!isEditablePath(edit.path))
            throw new Error(`${where}: "${edit.path}" is not an editable text field`);
        if (typeof edit.text !== 'string')
            throw new Error(`${where}: text must be a string`);
        if (edit.text.length > MAX_TEXT_LENGTH)
            throw new Error(`${where}: text is ${edit.text.length} characters; the maximum is ${MAX_TEXT_LENGTH}`);
        const segs = segments(edit.path);
        let node = slides[edit.slide - 1];
        for (const seg of segs.slice(0, -1)) {
            node = typeof seg === 'number' ? (Array.isArray(node) ? node[seg] : undefined) : isObj(node) ? node[seg] : undefined;
            if (node === undefined || node === null)
                throw new Error(`${where}: "${edit.path}" does not exist on this slide`);
        }
        const last = segs[segs.length - 1];
        const current = typeof last === 'number' ? (Array.isArray(node) ? node[last] : undefined) : isObj(node) ? node[last] : undefined;
        if (current === undefined)
            throw new Error(`${where}: "${edit.path}" does not exist on this slide`);
        if (typeof current !== 'string')
            throw new Error(`${where}: "${edit.path}" is not a text value`);
        if (typeof last === 'number')
            node[last] = edit.text;
        else
            node[last] = edit.text;
    }
    return copy;
}
/**
 * One entry per editable field whose string differs between two specs, over the slides both have (a slide
 * that exists in only one of them gets no entries). A field present on one side only has null on the other.
 */
export function diffText(a, b) {
    const before = new Map();
    const after = new Map();
    const order = [];
    const overlap = Math.min(slidesOf(a).length, slidesOf(b).length);
    const collect = (spec, into) => {
        for (const f of listTextFields(spec)) {
            if (f.slide > overlap)
                continue;
            const key = `${f.slide}\u0000${f.path}`;
            into.set(key, f.text);
            if (!order.includes(key))
                order.push(key);
        }
    };
    collect(a, before);
    collect(b, after);
    const out = [];
    for (const key of order) {
        const [slide, path] = key.split('\u0000');
        const o = before.get(key) ?? null;
        const n = after.get(key) ?? null;
        if (o !== n)
            out.push({ slide: Number(slide), path, old: o, new: n });
    }
    return out;
}
