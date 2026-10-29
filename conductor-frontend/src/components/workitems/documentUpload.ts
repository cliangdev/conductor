/** Largest document the web app will upload. */
export const MAX_DOCUMENT_UPLOAD_BYTES = 5 * 1024 * 1024

export const DOCUMENT_UPLOAD_ACCEPT = '.html,.htm,.md'

export type DocumentContentType = 'text/html' | 'text/markdown'

/** The content type a document is stored with, from its filename; null for an unsupported extension. */
export function contentTypeForFilename(filename: string): DocumentContentType | null {
  const name = filename.toLowerCase()
  if (name.endsWith('.html') || name.endsWith('.htm')) return 'text/html'
  if (name.endsWith('.md')) return 'text/markdown'
  return null
}

/** A message for why `file` cannot be uploaded, or null when it can. */
export function uploadProblem(file: { name: string; size: number }): string | null {
  if (!contentTypeForFilename(file.name)) return 'Only .html, .htm and .md files can be uploaded.'
  if (file.size > MAX_DOCUMENT_UPLOAD_BYTES) return 'That file is over 5 MB. Upload a smaller one.'
  return null
}

export function readFileText(file: Blob): Promise<string> {
  if (typeof file.text === 'function') return file.text()
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result ?? ''))
    reader.onerror = () => reject(reader.error ?? new Error('Could not read the file'))
    reader.readAsText(file)
  })
}
