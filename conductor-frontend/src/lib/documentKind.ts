/**
 * Which kind of document a Work Item document is. The HTML rule mirrors the backend's
 * `DocumentContent.isHtml` exactly, so the frontend and backend never disagree about whether a
 * document is HTML (and so whether its comments carry an anchor or a line number).
 */

interface DocumentLike {
  filename: string
  contentType?: string | null
}

/** The media type of a Content-Type value: parameters dropped, trimmed, lower-cased. */
function mediaType(contentType: string | null | undefined): string {
  return (contentType ?? '').split(';')[0].trim().toLowerCase()
}

/** HTML when the media type is text/html OR the filename ends in .html/.htm (case-insensitive). */
export function isHtmlDocument(doc: DocumentLike): boolean {
  const name = doc.filename.toLowerCase()
  return mediaType(doc.contentType) === 'text/html' || name.endsWith('.html') || name.endsWith('.htm')
}

/** Markdown only when it is not HTML: HTML is decided first, so `notes.md` stored as text/html is HTML. */
export function isMarkdownDocument(doc: DocumentLike): boolean {
  if (isHtmlDocument(doc)) return false
  const type = mediaType(doc.contentType)
  return type === 'text/markdown' || type === 'text/plain' || doc.filename.toLowerCase().endsWith('.md')
}
