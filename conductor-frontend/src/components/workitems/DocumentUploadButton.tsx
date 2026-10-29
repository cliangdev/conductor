'use client'

import { useRef, useState } from 'react'
import { Upload } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { ConfirmModal } from '@/components/ui/confirm-modal'
import {
  DOCUMENT_UPLOAD_ACCEPT,
  contentTypeForFilename,
  readFileText,
  uploadProblem,
  type DocumentContentType,
} from './documentUpload'

interface Props {
  /** Filenames already attached to the Work Item — uploading one of them replaces it, after a confirmation. */
  existingFilenames: string[]
  /** Saves the document. A rejection is the caller's to report (toast); this component only stops on it. */
  onUpload: (filename: string, content: string, contentType: DocumentContentType) => Promise<void>
}

interface Pending {
  filename: string
  content: string
  contentType: DocumentContentType
}

/** "Upload document" for the Work Item tab bar: a .html, .htm or .md file up to 5 MB. */
export function DocumentUploadButton({ existingFilenames, onUpload }: Props) {
  const inputRef = useRef<HTMLInputElement>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [overwrite, setOverwrite] = useState<Pending | null>(null)

  async function save(doc: Pending) {
    setBusy(true)
    try {
      await onUpload(doc.filename, doc.content, doc.contentType)
    } catch {
      // The caller has already told the user why.
    } finally {
      setBusy(false)
    }
  }

  async function handleFile(file: File) {
    const message = uploadProblem(file)
    setProblem(message)
    if (message) return
    const contentType = contentTypeForFilename(file.name)!
    let content: string
    try {
      content = await readFileText(file)
    } catch {
      setProblem('Could not read that file.')
      return
    }
    const doc = { filename: file.name, content, contentType }
    if (existingFilenames.includes(file.name)) setOverwrite(doc)
    else await save(doc)
  }

  return (
    <div className="flex items-center gap-2 shrink-0">
      {problem && (
        <p role="alert" className="text-xs text-destructive">
          {problem}
        </p>
      )}
      <input
        ref={inputRef}
        type="file"
        accept={DOCUMENT_UPLOAD_ACCEPT}
        className="sr-only"
        tabIndex={-1}
        aria-label="Document file"
        data-testid="document-upload-input"
        onChange={(e) => {
          const file = e.target.files?.[0]
          e.target.value = '' // picking the same file again must still fire a change
          if (file) void handleFile(file)
        }}
      />
      <Button
        variant="outline"
        size="sm"
        className="h-8"
        disabled={busy}
        onClick={() => inputRef.current?.click()}
      >
        <Upload className="h-3.5 w-3.5 mr-1.5" />
        {busy ? 'Uploading…' : 'Upload document'}
      </Button>

      <ConfirmModal
        open={overwrite !== null}
        title={`Replace ${overwrite?.filename ?? 'document'}?`}
        description="A document with this name is already attached. Uploading replaces its contents."
        confirmLabel="Replace"
        busyLabel="Replacing…"
        busy={busy}
        onCancel={() => setOverwrite(null)}
        onConfirm={() => {
          const doc = overwrite
          setOverwrite(null)
          if (doc) void save(doc)
        }}
      >
        <p className="text-sm text-muted-foreground">
          Existing comments stay on the document.
        </p>
      </ConfirmModal>
    </div>
  )
}
