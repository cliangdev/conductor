/** Slugs for draft folders and Brand Kits. Lives apart from the MCP tool modules so both can import it. */

/** Lowercase a-z / 0-9 / hyphens, at most `max` characters; `fallback` when nothing is left. */
export function slugify(text: string | undefined, max = 40, fallback = 'draft'): string {
  const slug = (text ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, max).replace(/-+$/, '')
  return slug || fallback
}

/** The longest slug a Brand Kit may have (the backend refuses more with a 422). */
export const KIT_SLUG_MAX = 64
export const KIT_SLUG_PATTERN = new RegExp(`^[a-z0-9-]{1,${KIT_SLUG_MAX}}$`)

/**
 * The slug a new Brand Kit's name derives to. Same rules as the web app's slugify in BrandKitForm.tsx: NFKD,
 * diacritics stripped, lowercase, every run of other characters a hyphen, edge hyphens trimmed, "kit" when
 * nothing is left. Capped at 60 so the -2 ... -9 retry suffix still fits.
 */
export function brandKitSlug(name: string): string {
  const slug = name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 60)
    .replace(/-+$/, '')
  return slug || 'kit'
}
