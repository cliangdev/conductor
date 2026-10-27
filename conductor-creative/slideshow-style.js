// slideshow-style.js — font-family helpers for SlideshowStyle, shared by the web Brand Kit preview.
// The data (generic keywords, bounds, patterns, native values) lives in slideshow-style.json; the backend
// (SlideshowStylesTest) and the CLI (slideshow-style-parity.test.ts) assert their copies equal it. This
// module takes the keyword list as an argument so it stays free of JSON-import syntax.

/** The family names of a font-family list: split on commas, trimmed, blanks dropped. */
export function fontFamilyNames(family) {
  return String(family).split(',').map((f) => f.trim()).filter(Boolean);
}

/** A font-family list as CSS: generic/system keywords unquoted (canonical casing), every other name quoted. */
export function fontFamilyCss(family, genericFamilies) {
  const canonical = new Map(genericFamilies.map((g) => [g.toLowerCase(), g]));
  return fontFamilyNames(family)
    .map((f) => canonical.get(f.toLowerCase()) ?? `"${f}"`)
    .join(',');
}

/** True for a CSS generic or system-font keyword (case-insensitive), which is never a custom font to check or load. */
export function isGenericFamily(name, genericFamilies) {
  const n = String(name).toLowerCase();
  return genericFamilies.some((g) => g.toLowerCase() === n);
}

/**
 * Whether a font-family list is acceptable (blank counts as unset): at most `maxLength` characters once trimmed, at
 * least one name, and none of the control characters or `forbiddenChars` the shared rules reject.
 */
export function fontFamilyValid(family, forbiddenChars, maxLength) {
  const t = String(family).trim();
  if (t === '') return true;
  if (t.length > maxLength || fontFamilyNames(t).length === 0) return false;
  return ![...t].some((ch) => {
    const code = ch.charCodeAt(0);
    return code <= 0x1f || (code >= 0x7f && code <= 0x9f) || forbiddenChars.includes(ch);
  });
}

/**
 * The slug a Brand Kit's name derives to: NFKD, diacritics stripped, lowercase, every run of other characters a
 * hyphen, edge hyphens trimmed, capped at 60 (so a -2 ... -9 retry suffix fits the backend's 64), "kit" when nothing
 * is left. The CLI keeps a copy (brandKitSlug) and slideshow-style-parity.test.ts asserts they agree.
 */
export function brandKitSlug(name) {
  const slug = String(name)
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60)
    .replace(/-+$/, '');
  return slug || 'kit';
}
