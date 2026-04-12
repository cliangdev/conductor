'use client'

export const dynamic = 'force-dynamic'

import { useState } from 'react'
import { useParams } from 'next/navigation'
import { useAuth } from '@/contexts/AuthContext'
import { ApiKeySection } from '@/components/api-keys/ApiKeySection'

function SetupStep({ number, title, children }: { number: number; title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-3">
        <span className="flex items-center justify-center w-6 h-6 rounded-full bg-primary text-primary-foreground text-xs font-bold shrink-0">
          {number}
        </span>
        <h2 className="font-medium text-base">{title}</h2>
      </div>
      <div className="ml-9">{children}</div>
    </div>
  )
}

function CodeBlock({ code, language = 'bash' }: { code: string; language?: string }) {
  const [copied, setCopied] = useState(false)

  function handleCopy() {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <div className="relative group">
      <pre className="bg-muted rounded-md px-4 py-3 text-sm font-mono overflow-x-auto" data-language={language}>
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


export default function SetupPage() {
  const params = useParams()
  const projectId = params.projectId as string
  const { accessToken } = useAuth()

  return (
    <div className="max-w-2xl mx-auto p-6 space-y-8">
      <div>
        <h1 className="text-2xl font-semibold">Setup</h1>
        <p className="text-muted-foreground mt-1">
          Get the Conductor CLI and MCP server running locally.
        </p>
      </div>

      <SetupStep number={1} title="Install">
        <CodeBlock code="npm install -g @conductor/cli" />
      </SetupStep>

      <SetupStep number={2} title="Login">
        <p className="text-sm text-muted-foreground mb-3">
          Opens your browser for Google OAuth. Saves credentials to ~/.conductor/config.json.
        </p>
        <CodeBlock code="conductor login" />
      </SetupStep>

      <SetupStep number={3} title="Initialize project">
        <p className="text-sm text-muted-foreground mb-3">
          Creates .conductor/issues/ directory and installs the /conductor:prd skill.
        </p>
        <CodeBlock code={`conductor init --project-id ${projectId}`} />
      </SetupStep>

      <SetupStep number={4} title="API Key">
        <ApiKeySection accessToken={accessToken} />
      </SetupStep>

      <SetupStep number={5} title="Verify">
        <CodeBlock code="conductor status" />
      </SetupStep>
    </div>
  )
}
