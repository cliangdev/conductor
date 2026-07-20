'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Modal } from '@/components/ui/modal'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { useToast } from '@/components/ui/toast'
import { listWorkflows, dispatchWorkflow } from '@/lib/workflows'
import { KNOWLEDGE_BOOTSTRAP_WORKFLOW } from '@/lib/knowledge-api'
import { apiErrorMessage } from '@/lib/api'

// "owner/repo" — mirrors the GitHub repo shape the knowledge-bootstrap workflow's `repo` input expects.
const REPO_RE = /^[\w.-]+\/[\w.-]+$/

/**
 * Dispatches the `knowledge-bootstrap` system workflow (see `knowledge-bootstrap.yaml`) with the
 * given repo as its `inputs.repo`. The workflow is provisioned alongside the librarian when Knowledge
 * is enabled (`KnowledgeWorkflowProvisioner`) — a project that enabled Knowledge before this workflow
 * existed, or that had it deleted, won't have it, so a missing-workflow 404-equivalent (empty list)
 * gets its own error message rather than a generic failure.
 */
export function KnowledgeBootstrapDialog({
  projectId,
  token,
  onClose,
}: {
  projectId: string
  token: string
  onClose: () => void
}) {
  const router = useRouter()
  const { showToast } = useToast()
  const [repo, setRepo] = useState('')
  const [submitting, setSubmitting] = useState(false)

  const trimmed = repo.trim()
  const valid = REPO_RE.test(trimmed)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!valid || submitting) return
    setSubmitting(true)
    try {
      const workflows = await listWorkflows(projectId, token)
      const bootstrap = workflows.find((w) => w.name === KNOWLEDGE_BOOTSTRAP_WORKFLOW)
      if (!bootstrap) {
        showToast(
          'Bootstrap workflow not found — try disabling and re-enabling Knowledge to finish provisioning.',
          'error',
        )
        return
      }
      await dispatchWorkflow(projectId, bootstrap.id, { repo: trimmed }, token)
      showToast('Bootstrap started — pages will appear as it works')
      onClose()
      router.push(`/app/projects/${projectId}/knowledge/activity?tab=runs`)
    } catch (err) {
      showToast(apiErrorMessage(err, 'Failed to start the bootstrap workflow'), 'error')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Modal
      open
      onOpenChange={(open) => {
        if (!open) onClose()
      }}
      title="Bootstrap from a repo"
      description="Seeds architecture and feature pages by reading an existing codebase."
      footer={
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={onClose} disabled={submitting} type="button">
            Cancel
          </Button>
          <Button
            onClick={(e) => handleSubmit(e as unknown as React.FormEvent)}
            disabled={submitting || !valid}
            type="button"
          >
            {submitting ? 'Starting…' : 'Start'}
          </Button>
        </div>
      }
    >
      <form onSubmit={handleSubmit} className="space-y-3">
        <div className="space-y-1">
          <Label htmlFor="bootstrap-repo">Repository (owner/repo)</Label>
          <Input
            id="bootstrap-repo"
            value={repo}
            onChange={(e) => setRepo(e.target.value)}
            autoFocus
            placeholder="cliangdev/conductor"
          />
          {repo.trim() && !valid && (
            <p className="text-sm text-destructive">Use the &quot;owner/repo&quot; shape, e.g. cliangdev/conductor.</p>
          )}
        </div>
      </form>
    </Modal>
  )
}
