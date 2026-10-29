/** Map a document filename to the MIME type Conductor stores it under (case-insensitive extension match). */
export function contentTypeFor(filename: string): string {
  const dot = filename.lastIndexOf('.')
  const ext = dot >= 0 ? filename.slice(dot).toLowerCase() : ''
  switch (ext) {
    case '.html':
    case '.htm':
      return 'text/html'
    case '.md':
      return 'text/markdown'
    case '.json':
      return 'application/json'
    default:
      return 'text/plain'
  }
}
