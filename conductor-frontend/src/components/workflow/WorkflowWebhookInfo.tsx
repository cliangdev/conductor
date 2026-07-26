'use client';

// The webhook URL an external system needs to trigger this workflow. `webhookToken` is returned by
// the backend on every WorkflowDefinitionDto but was never surfaced in the UI — without this, a
// workflow.manage user had no way to get the URL short of reading the API response directly.

import { useState } from 'react';
import { CopyIcon, CheckIcon } from 'lucide-react';
import { Card } from '@/components/ui/card';
import { toastSuccess } from '@/components/ui/toast';
import { parseWorkflowYaml } from '@/lib/workflowAutomation';
import type { WorkflowDefinitionDto } from '@/types/workflow';

export function WorkflowWebhookInfo({ workflow }: { workflow: WorkflowDefinitionDto }) {
  const [copied, setCopied] = useState(false);

  let hasWebhookTrigger = false;
  try {
    hasWebhookTrigger = parseWorkflowYaml(workflow.yaml ?? '').triggers.some(t => t.kind === 'webhook');
  } catch {
    // Invalid YAML — nothing to show rather than throwing during a keystroke-driven preview.
  }

  if (!hasWebhookTrigger || !workflow.webhookToken) return null;

  const url = `${process.env.NEXT_PUBLIC_API_URL}/api/v1/workflows/webhook/${workflow.webhookToken}`;

  const handleCopy = () => {
    navigator.clipboard.writeText(url).then(() => {
      setCopied(true);
      toastSuccess('Copied to clipboard');
      setTimeout(() => setCopied(false), 1500);
    });
  };

  return (
    <Card className="p-4 space-y-2">
      <div>
        <h2 className="text-sm font-semibold text-foreground">Webhook URL</h2>
        <p className="text-xs text-muted-foreground">Send a POST request here to trigger this workflow.</p>
      </div>
      <div className="flex items-center gap-2">
        <code className="flex-1 min-w-0 truncate rounded border border-border bg-muted px-2 py-1.5 font-mono text-xs text-foreground">
          {url}
        </code>
        <button
          type="button"
          onClick={handleCopy}
          aria-label="Copy webhook URL"
          className="inline-flex shrink-0 items-center gap-1.5 rounded border border-border px-2 py-1.5 text-xs font-medium text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
        >
          {copied ? <CheckIcon className="h-3.5 w-3.5 text-status-done" /> : <CopyIcon className="h-3.5 w-3.5" />}
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
    </Card>
  );
}
