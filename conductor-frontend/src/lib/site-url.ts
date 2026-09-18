/** Used when the environment gives us nothing usable. Matches the local dev server. */
const FALLBACK = 'http://localhost:3000'

/**
 * The canonical public origin of this frontend, as a string that is always safe to hand to
 * `new URL()`.
 *
 * `NEXT_PUBLIC_SITE_URL` is a Docker build arg fed from a GitHub repository variable. An unset
 * variable does not arrive as `undefined`: the workflow interpolates it into the `--build-arg` as
 * an **empty string**, which `??` happily accepts. `new URL('')` throws, and because
 * `metadataBase` is evaluated while Next collects page data, that throw failed the whole production
 * build with `ERR_INVALID_URL` on `/_not-found` rather than anything that named the real cause.
 *
 * So: treat blank as absent, and treat unparseable as absent too. A bad value degrades to
 * localhost-flavoured metadata, which is wrong but harmless, instead of taking the deploy down.
 */
export function resolveSiteUrl(raw: string | undefined = process.env.NEXT_PUBLIC_SITE_URL): string {
  const trimmed = raw?.trim()
  if (!trimmed) return FALLBACK

  try {
    const url = new URL(trimmed)
    // A protocol-relative or non-http value would parse but make nonsense of an absolute OG URL.
    if (url.protocol !== 'http:' && url.protocol !== 'https:') return FALLBACK
    // Canonical links read better without a trailing slash on the origin.
    return url.origin
  } catch {
    return FALLBACK
  }
}
