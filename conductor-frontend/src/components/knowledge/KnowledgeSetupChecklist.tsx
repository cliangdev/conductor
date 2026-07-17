'use client'

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { CheckCircle2Icon, CircleIcon } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { getProviderCredentialStatus, listProjectApiKeys } from '@/lib/api'
import { cn } from '@/lib/utils'

/** `null` while the check is loading. */
type PrereqState = boolean | null

function ChecklistRow({
  label,
  satisfied,
  hint,
}: {
  label: string
  satisfied: PrereqState
  hint: React.ReactNode
}) {
  return (
    <div className="flex items-center gap-2.5 px-4 py-3">
      {satisfied === null ? (
        <Skeleton className="h-4 w-4 rounded-full shrink-0" />
      ) : satisfied ? (
        <CheckCircle2Icon className="h-4 w-4 shrink-0 text-primary" />
      ) : (
        <CircleIcon className="h-4 w-4 shrink-0 text-foreground-subtle" />
      )}
      <span className={cn('text-[13px]', satisfied ? 'text-foreground' : 'text-foreground-subtle')}>{label}</span>
      {satisfied === false && <span className="ml-auto text-[13px]">{hint}</span>}
    </div>
  )
}

/**
 * Admin-only setup guidance shown in the Knowledge empty state. Prereqs are guidance, not hard
 * gates — the pipeline self-heals (see KnowledgeWorkflowProvisioner / LibrarianDispatchService on the
 * backend) — so Enable Knowledge stays enabled regardless of row state.
 */
export function KnowledgeSetupChecklist({
  projectId,
  token,
  onEnable,
  enabling,
}: {
  projectId: string
  token: string
  onEnable: () => void
  enabling: boolean
}) {
  const [credentialOk, setCredentialOk] = useState<PrereqState>(null)
  const [apiKeyOk, setApiKeyOk] = useState<PrereqState>(null)

  useEffect(() => {
    let cancelled = false

    Promise.all([
      getProviderCredentialStatus(projectId, 'claude-code', token).catch(() => ({ configured: false })),
      getProviderCredentialStatus(projectId, 'claude', token).catch(() => ({ configured: false })),
    ]).then(([claudeCode, claude]) => {
      if (!cancelled) setCredentialOk(claudeCode.configured || claude.configured)
    })

    listProjectApiKeys(projectId, token)
      .then((keys) => {
        if (!cancelled) setApiKeyOk(keys.length > 0)
      })
      .catch(() => {
        if (!cancelled) setApiKeyOk(false)
      })

    return () => {
      cancelled = true
    }
  }, [projectId, token])

  return (
    <Card className="w-full max-w-sm text-left">
      <CardContent>
        <ChecklistRow
          label="Claude credential"
          satisfied={credentialOk}
          hint={
            <Link href={`/app/projects/${projectId}/agents`} className="text-primary hover:underline">
              Add in Agents
            </Link>
          }
        />
        <ChecklistRow
          label="Project API key"
          satisfied={apiKeyOk}
          hint={
            <Link href={`/app/projects/${projectId}/settings/api-keys`} className="text-primary hover:underline">
              Add in Settings
            </Link>
          }
        />
        <div className="px-4 py-3">
          <Button size="sm" onClick={onEnable} disabled={enabling}>
            {enabling ? 'Enabling…' : 'Enable Knowledge'}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
