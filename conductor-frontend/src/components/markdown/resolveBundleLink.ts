// Any URI scheme (http:, https:, mailto:, doc:, tel:, ...) or a same-page fragment link.
const EXCLUDED_PREFIX = /^(?:[a-z][a-z0-9+.-]*:|#)/i

/**
 * Resolves a markdown link href into a bundle-relative page path, for content whose links point at
 * other pages by path (e.g. "/engineering/architecture.md", "../people/jane.md", "jane.md").
 * `baseDir` is the directory of the page currently being rendered (empty string for the root).
 * Returns null for anything that isn't a bundle-relative link to a markdown page — external/scheme
 * URLs, fragment-only links, and non-".md" targets all fall through to normal anchor behavior.
 */
export function resolveBundleLink(href: string | undefined, baseDir = ''): string | null {
  if (!href) return null
  if (EXCLUDED_PREFIX.test(href)) return null

  const [pathPart] = href.split('#')
  if (!pathPart) return null

  const raw = pathPart.startsWith('/')
    ? pathPart.slice(1)
    : baseDir
      ? `${baseDir}/${pathPart}`
      : pathPart

  const stack: string[] = []
  for (const segment of raw.split('/')) {
    if (segment === '' || segment === '.') continue
    if (segment === '..') {
      stack.pop()
      continue
    }
    stack.push(segment)
  }

  const resolved = stack.join('/')
  return resolved.toLowerCase().endsWith('.md') ? resolved : null
}
