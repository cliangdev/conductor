/**
 * Map a document filename to the content type Conductor stores it under.
 * `.html`/`.htm` (case-insensitive) are `text/html`; everything else stays `text/markdown`,
 * which is what the CLI has always sent and what the web app can display.
 */
export function contentTypeFor(filename: string): string {
  return /\.html?$/i.test(filename) ? 'text/html' : 'text/markdown'
}
