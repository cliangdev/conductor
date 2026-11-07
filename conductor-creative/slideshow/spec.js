// GENERATED from conductor-tools/src/lib/slideshow/spec.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
/**
 * Slideshow spec: the shape a native-looking organic slideshow (TikTok / Reels style) is described in.
 * Ported from a content run's hand-built compositor, so its specs render unchanged.
 */
import { validateSlideshowStyle } from './style.js';
export const SLIDE_TYPES = ['photo', 'screen', 'notes', 'grid', 'stack', 'tier', 'week', 'receipt', 'placard', 'messages'];
export const TEXT_STYLES = ['box', 'dark', 'stroke', 'sub'];
export const SLIDE_RATIOS = ['9x16', '4x5'];
export const MAX_SLIDES = 35;
/** The current layout generation: tier/week/receipt default below the 290 px top band, text clear of the 162 px
 * right band. A spec stored without it was composed before, and is re-rendered with the old layout. */
export const SLIDESHOW_LAYOUT = 2;
export const isLegacyLayout = (spec) => !(typeof spec?.layout === 'number' && spec.layout >= SLIDESHOW_LAYOUT);
export class SlideshowSpecError extends Error {
    constructor(errors) {
        super(`Invalid slideshow spec:\n${errors.map((e) => `  - ${e}`).join('\n')}`);
        this.errors = errors;
        this.name = 'SlideshowSpecError';
    }
}
const isObj = (v) => typeof v === 'object' && v !== null && !Array.isArray(v);
const isStr = (v) => typeof v === 'string';
const isNum = (v) => typeof v === 'number' && Number.isFinite(v);
const SAFE_COLOR = /^[#a-zA-Z0-9(),.%\s-]{1,64}$/;
const SAFE_POS = /^[0-9a-z.%\s-]{1,40}$/i;
/** Returns every problem found (empty = valid). Never throws. */
export function validateSlideshowSpec(spec) {
    const errors = [];
    if (!isObj(spec))
        return ['spec must be an object with { id, slides }'];
    if (!isStr(spec.id) || !/^[A-Za-z0-9][A-Za-z0-9._-]{0,60}$/.test(spec.id)) {
        errors.push('id is required: letters, digits, ".", "_" or "-" only (it names the output files)');
    }
    if (spec.style !== undefined && spec.style !== null)
        errors.push(...validateSlideshowStyle(spec.style, 'spec.style'));
    if (spec.layout !== undefined && !(Number.isInteger(spec.layout) && spec.layout >= 1))
        errors.push('layout must be a positive whole number when given');
    if (!Array.isArray(spec.slides) || spec.slides.length === 0) {
        errors.push('slides must be a non-empty array');
        return errors;
    }
    if (spec.slides.length > MAX_SLIDES)
        errors.push(`slides has ${spec.slides.length} entries; the maximum is ${MAX_SLIDES}`);
    spec.slides.slice(0, MAX_SLIDES).forEach((raw, i) => {
        const at = `slides[${i}]`;
        if (!isObj(raw)) {
            errors.push(`${at} must be an object`);
            return;
        }
        const type = raw.type ?? 'photo';
        if (!isStr(type) || !SLIDE_TYPES.includes(type)) {
            errors.push(`${at}.type "${String(raw.type)}" is unknown; use one of ${SLIDE_TYPES.join(', ')}`);
            return;
        }
        const need = (cond, msg) => {
            if (!cond)
                errors.push(`${at} (${type}): ${msg}`);
        };
        const nonEmptyArray = (v) => Array.isArray(v) && v.length > 0;
        for (const key of ['bg'])
            if (raw[key] !== undefined && !isStr(raw[key]))
                errors.push(`${at}.${key} must be a string path`);
        if (raw.bgColor !== undefined && !(isStr(raw.bgColor) && SAFE_COLOR.test(raw.bgColor)))
            errors.push(`${at}.bgColor must be a plain CSS colour such as #fff0e0`);
        if (raw.pos !== undefined && !(isStr(raw.pos) && SAFE_POS.test(raw.pos)))
            errors.push(`${at}.pos must look like "50% 30%"`);
        if (raw.dim !== undefined && typeof raw.dim !== 'boolean' && !(isNum(raw.dim) && raw.dim >= 0 && raw.dim <= 1))
            errors.push(`${at}.dim must be true or a number from 0 to 1`);
        for (const key of ['top', 'top4', 'phoneY', 'y']) {
            const v = raw[key];
            if (v !== undefined && !(isNum(v) && (key === 'top4' || key === 'top' ? true : v >= 0 && v <= 100))) {
                errors.push(`${at}.${key} must be a number${key === 'top' || key === 'top4' ? ' (pixels)' : ' from 0 to 100 (percent of the frame height)'}`);
            }
        }
        if (raw.texts !== undefined) {
            if (!Array.isArray(raw.texts))
                errors.push(`${at}.texts must be an array`);
            else
                raw.texts.forEach((t, j) => {
                    const tat = `${at}.texts[${j}]`;
                    if (!isObj(t) || !isStr(t.t) || t.t.trim() === '')
                        return void errors.push(`${tat}.t is required (the text)`);
                    if (t.y !== undefined && !(isNum(t.y) && t.y >= 0 && t.y <= 100))
                        errors.push(`${tat}.y must be a number from 0 to 100 (percent of the 9:16 frame), got ${JSON.stringify(t.y)}`);
                    if (j === 0 && t.y === undefined)
                        errors.push(`${tat}.y is required on the first text (later texts without y join the previous one's stack)`);
                    if (t.style !== undefined && !TEXT_STYLES.includes(t.style))
                        errors.push(`${tat}.style "${String(t.style)}" is unknown; use ${TEXT_STYLES.join(', ')}`);
                    for (const k of ['fs', 'ss'])
                        if (t[k] !== undefined && !(isNum(t[k]) && t[k] > 0 && t[k] <= 300))
                            errors.push(`${tat}.${k} must be a font size in pixels`);
                });
        }
        switch (type) {
            case 'screen':
                need(isStr(raw.shot) && raw.shot !== '', 'shot (the screenshot path) is required');
                if (raw.callouts !== undefined) {
                    if (!Array.isArray(raw.callouts))
                        errors.push(`${at}.callouts must be an array`);
                    else
                        raw.callouts.forEach((c, j) => {
                            const cat = `${at}.callouts[${j}]`;
                            if (!isObj(c) || !isStr(c.label) || c.label.trim() === '')
                                return void errors.push(`${cat}.label is required`);
                            for (const k of ['x', 'y'])
                                if (!(isNum(c[k]) && c[k] >= 0 && c[k] <= 100))
                                    errors.push(`${cat}.${k} must be a number from 0 to 100 (percent of the screenshot)`);
                        });
                }
                break;
            case 'grid':
            case 'stack':
                need(nonEmptyArray(raw.photos), 'photos (a non-empty array of {src}) is required');
                if (Array.isArray(raw.photos))
                    raw.photos.forEach((p, j) => { if (!isObj(p) || !isStr(p.src) || p.src === '')
                        errors.push(`${at}.photos[${j}].src is required`); });
                break;
            case 'tier':
                need(nonEmptyArray(raw.rows), 'rows (a non-empty array of {k, c, items}) is required');
                if (Array.isArray(raw.rows))
                    raw.rows.forEach((r, j) => {
                        if (!isObj(r) || !isStr(r.k) || !Array.isArray(r.items))
                            return void errors.push(`${at}.rows[${j}] needs k (label), c (colour) and items (array)`);
                        if (!(isStr(r.c) && SAFE_COLOR.test(r.c)))
                            errors.push(`${at}.rows[${j}].c must be a plain CSS colour`);
                    });
                break;
            case 'week':
                need(nonEmptyArray(raw.days), 'days (a non-empty array of {d, t}) is required');
                if (Array.isArray(raw.days))
                    raw.days.forEach((d, j) => { if (!isObj(d) || !isStr(d.d) || !isStr(d.t))
                        errors.push(`${at}.days[${j}] needs d (day label) and t (text)`); });
                break;
            case 'receipt':
                need(isStr(raw.head) && raw.head.trim() !== '', 'head (the receipt heading) is required');
                need(nonEmptyArray(raw.sections), 'sections (a non-empty array of {h, items}) is required');
                if (Array.isArray(raw.sections))
                    raw.sections.forEach((s, j) => { if (!isObj(s) || !isStr(s.h) || !Array.isArray(s.items))
                        errors.push(`${at}.sections[${j}] needs h (heading) and items (array)`); });
                break;
            case 'placard':
                need(isStr(raw.title) && raw.title.trim() !== '', 'title is required');
                if (raw.lines !== undefined) {
                    if (!Array.isArray(raw.lines))
                        errors.push(`${at}.lines must be an array of {k?, v}`);
                    else
                        raw.lines.forEach((l, j) => { if (!isObj(l) || !isStr(l.v))
                            errors.push(`${at}.lines[${j}].v is required`); });
                }
                break;
            case 'messages':
                need(nonEmptyArray(raw.bubbles), 'bubbles (a non-empty array of {from, t}) is required');
                if (Array.isArray(raw.bubbles))
                    raw.bubbles.forEach((b, j) => {
                        if (!isObj(b) || !isStr(b.t) || b.t.trim() === '')
                            return void errors.push(`${at}.bubbles[${j}].t is required`);
                        if (b.from !== 'me' && b.from !== 'them')
                            errors.push(`${at}.bubbles[${j}].from must be "me" or "them"`);
                    });
                break;
            case 'notes':
                if (raw.items !== undefined && !Array.isArray(raw.items))
                    errors.push(`${at}.items must be an array`);
                break;
            default:
                break;
        }
    });
    return errors;
}
export function assertValidSlideshowSpec(spec) {
    const errors = validateSlideshowSpec(spec);
    if (errors.length > 0)
        throw new SlideshowSpecError(errors);
}
