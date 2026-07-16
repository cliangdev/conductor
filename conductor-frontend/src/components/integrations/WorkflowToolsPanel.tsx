'use client';

import { useEffect, useState } from 'react';
import { useAuth } from '@/contexts/AuthContext';
import { listIntegrationTools, type IntegrationToolItem, type IntegrationToolOperation } from '@/lib/api';
import { Bot, Copy, Check } from 'lucide-react';

function CopyButton({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);
  const handleCopy = async () => {
    await navigator.clipboard.writeText(text);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <button
      onClick={handleCopy}
      className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-medium text-muted-foreground hover:text-foreground hover:bg-muted transition-colors"
    >
      {copied ? <Check className="h-3 w-3 text-status-done" /> : <Copy className="h-3 w-3" />}
      {copied ? 'Copied' : 'Copy'}
    </button>
  );
}

function OperationCard({ op, connectorId }: { op: IntegrationToolOperation; connectorId: string }) {
  const yaml = `uses: integration\nwith:\n  connector: ${connectorId}\n  operation: ${op.id}`;
  const hasParams = Object.keys(op.params ?? {}).length > 0;

  return (
    <div className="bg-card border border-border rounded-lg p-5 space-y-4">
      {/* Operation ID + description */}
      <div>
        <span className="inline-block font-mono text-xs font-semibold bg-muted px-2 py-0.5 rounded text-foreground mb-2">
          {op.id}
        </span>
        <p className="text-sm text-foreground">{op.description}</p>
      </div>

      {/* Parameters */}
      <div>
        <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1">Parameters</p>
        {hasParams ? (
          <table className="w-full text-xs">
            <tbody>
              {Object.entries(op.params).map(([key, desc]) => (
                <tr key={key} className="border-b border-border/50 last:border-0">
                  <td className="py-1.5 pr-4 font-mono font-medium text-foreground w-40">{key}</td>
                  <td className="py-1.5 text-muted-foreground">{desc}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="text-xs text-muted-foreground">none</p>
        )}
      </div>

      {/* Output keys */}
      {op.outputKeys && op.outputKeys.length > 0 && (
        <div>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1.5">Output keys</p>
          <div className="flex flex-wrap gap-1.5">
            {op.outputKeys.map((key) => (
              <span
                key={key}
                className="font-mono text-xs bg-muted px-2 py-0.5 rounded text-foreground"
              >
                {key}
              </span>
            ))}
          </div>
        </div>
      )}

      {/* Output shape */}
      {op.outputShape && (
        <div>
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1.5">Output shape</p>
          <pre className="text-xs font-mono bg-muted/60 rounded p-3 overflow-x-auto text-foreground">
            {JSON.stringify(op.outputShape, null, 2)}
          </pre>
        </div>
      )}

      {/* Workflow YAML */}
      <div>
        <div className="flex items-center justify-between mb-1.5">
          <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide">Workflow YAML</p>
          <CopyButton text={yaml} />
        </div>
        <pre className="text-xs font-mono bg-muted/60 rounded p-3 overflow-x-auto text-foreground">
          {yaml}
        </pre>
      </div>
    </div>
  );
}

export default function WorkflowToolsPanel({
  projectId,
  connectorId,
}: {
  projectId: string;
  connectorId: string;
}) {
  const { accessToken } = useAuth();
  const [tool, setTool] = useState<IntegrationToolItem | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!accessToken) return;
    listIntegrationTools(projectId, accessToken)
      .then((items) => {
        setTool(items.find((i) => i.connectorId === connectorId) ?? null);
      })
      .catch(console.error)
      .finally(() => setLoading(false));
  }, [projectId, connectorId, accessToken]);

  if (loading) {
    return (
      <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
        <div className="animate-pulse space-y-4">
          <div className="h-5 bg-muted rounded w-64" />
          <div className="h-40 bg-muted rounded-lg" />
        </div>
      </div>
    );
  }

  const meta = tool?.toolMetadata;
  const operations = meta?.operations ?? [];

  return (
    <div className="max-w-6xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-6">
      {/* Header */}
      <div className="flex items-start gap-3">
        <div className="flex-shrink-0 h-8 w-8 rounded-md bg-muted flex items-center justify-center">
          <Bot className="h-4 w-4 text-muted-foreground" />
        </div>
        <div>
          <p className="text-sm font-medium text-foreground">How AI agents use this integration</p>
          <p className="text-xs text-muted-foreground mt-0.5">
            Returned by <span className="font-mono">list_integration_tools</span> before designing a workflow step.
          </p>
        </div>
      </div>

      {!meta ? (
        <div className="bg-card border border-border rounded-lg p-8 text-center">
          <p className="text-sm font-medium text-foreground mb-1">No workflow tools available</p>
          <p className="text-xs text-muted-foreground">
            This integration is not currently active or does not support workflow steps.
          </p>
        </div>
      ) : (
        <>
          {/* Description */}
          <div>
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-1.5">Description</p>
            <p className="text-sm text-foreground">{meta.description}</p>
          </div>

          {/* Operations */}
          <div>
            <p className="text-xs font-medium text-muted-foreground uppercase tracking-wide mb-3">
              Operations{operations.length > 0 ? ` (${operations.length})` : ''}
            </p>
            {operations.length > 0 ? (
              <div className="space-y-4">
                {operations.map((op) => (
                  <OperationCard key={op.id} op={op} connectorId={connectorId} />
                ))}
              </div>
            ) : (
              <p className="text-sm text-muted-foreground">No operations defined for this connector.</p>
            )}
          </div>

        </>
      )}
    </div>
  );
}
