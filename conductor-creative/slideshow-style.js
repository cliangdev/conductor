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
