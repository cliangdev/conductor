'use client'

import { useEffect, useState } from 'react'
import { apiDelete, apiGet, apiPost } from '@/lib/api'
import type { UserApiKey, CreateApiKeyResponse } from '@/types'

function CodeBlock({ code }: { code: string }) {
  const [copied, setCopied] = useState(false)

  function handleCopy() {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <div className="relative group">
      <pre className="bg-muted rounded-md px-4 py-3 text-sm font-mono overflow-x-auto">
        <code>{code}</code>
      </pre>
      <button
        onClick={handleCopy}
        className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity p-1.5 rounded bg-background border text-xs"
        aria-label="Copy"
      >
        {copied ? 'Copied!' : 'Copy'}
      </button>
    </div>
  )
}

export function ApiKeySection({ accessToken }: { accessToken: string | null }) {
  const [keys, setKeys] = useState<UserApiKey[]>([])
  const [loading, setLoading] = useState(true)
  const [generating, setGenerating] = useState(false)
  const [newKey, setNewKey] = useState<string | null>(null)

  useEffect(() => {
    if (!accessToken) return
    apiGet<UserApiKey[]>('/api/v1/api-keys', accessToken)
      .then(setKeys)
      .catch(console.error)
      .finally(() => setLoading(false))
  }, [accessToken])

  async function handleGenerate() {
    if (!accessToken) return
    setGenerating(true)
    try {
      const result = await apiPost<CreateApiKeyResponse>('/api/v1/api-keys', {}, accessToken)
      setNewKey(result.key)
      setKeys(prev => [...prev, { id: result.id, maskedKey: result.maskedKey, label: result.label, createdAt: result.createdAt }])
    } catch (err) {
      console.error(err)
    } finally {
      setGenerating(false)
    }
  }

  async function handleDelete(keyId: string) {
    if (!accessToken) return
    await apiDelete(`/api/v1/api-keys/${keyId}`, accessToken)
    setKeys(prev => prev.filter(k => k.id !== keyId))
    if (newKey) setNewKey(null)
  }

  if (loading) return <p className="text-sm text-muted-foreground">Loading...</p>

  return (
    <div className="space-y-3">
      {newKey && (
        <div className="rounded-md border border-yellow-500/50 bg-yellow-50 dark:bg-yellow-950/20 p-3 space-y-2">
          <p className="text-sm font-medium text-yellow-800 dark:text-yellow-200">
            Copy this key &mdash; it won&apos;t be shown again
          </p>
          <CodeBlock code={newKey} />
        </div>
      )}

      {keys.length === 0 && !newKey ? (
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">No API keys yet.</p>
          <button
            onClick={handleGenerate}
            disabled={generating}
            className="text-sm px-3 py-1.5 rounded-md bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50"
          >
            {generating ? 'Generating...' : 'Generate API key'}
          </button>
        </div>
      ) : (
        <div className="space-y-2">
          {keys.map(key => (
            <div key={key.id} className="flex items-center justify-between rounded-md border px-3 py-2">
              <div>
                <span className="text-sm font-mono">{key.maskedKey}</span>
                {key.label && <span className="text-xs text-muted-foreground ml-2">{key.label}</span>}
              </div>
              <button
                onClick={() => handleDelete(key.id)}
                className="text-xs text-destructive hover:underline ml-4"
              >
                Revoke
              </button>
            </div>
          ))}
          <button
            onClick={handleGenerate}
            disabled={generating}
            className="text-sm px-3 py-1.5 rounded-md border border-border hover:bg-muted disabled:opacity-50"
          >
            {generating ? 'Generating...' : 'Generate new key'}
          </button>
        </div>
      )}
    </div>
  )
}
