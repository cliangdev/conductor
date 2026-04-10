'use client'

import { useState } from 'react'

interface Props {
  onSubmit: (content: string) => Promise<void>
  onCancel?: () => void
  placeholder?: string
  submitLabel?: string
}

export function NewCommentForm({
  onSubmit,
  onCancel,
  placeholder = 'Add a comment...',
  submitLabel = 'Comment',
}: Props) {
  const [content, setContent] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const trimmed = content.trim()
    if (!trimmed) return
    setSubmitting(true)
    setError(null)
    try {
      await onSubmit(trimmed)
      setContent('')
    } catch {
      setError('Failed to submit comment. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-2">
      <textarea
        value={content}
        onChange={(e) => setContent(e.target.value)}
        placeholder={placeholder}
        rows={3}
        className="w-full text-sm border border-gray-300 rounded px-2 py-1.5 resize-none focus:outline-none focus:ring-1 focus:ring-blue-500"
        disabled={submitting}
      />
      {error && <p className="text-xs text-red-500">{error}</p>}
      <div className="flex items-center gap-2 justify-end">
        {onCancel && (
          <button
            type="button"
            onClick={onCancel}
            className="text-xs text-gray-500 hover:text-gray-700 px-2 py-1"
            disabled={submitting}
          >
            Cancel
          </button>
        )}
        <button
          type="submit"
          disabled={submitting || !content.trim()}
          className="text-xs bg-blue-600 text-white px-3 py-1 rounded hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {submitting ? 'Submitting...' : submitLabel}
        </button>
      </div>
    </form>
  )
}
