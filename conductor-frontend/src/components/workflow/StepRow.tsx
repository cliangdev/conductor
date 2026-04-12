'use client';

import { useState } from 'react';
import { WorkflowStepRunDto } from '@/types/workflow';

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'text-green-600',
  FAILED: 'text-red-600',
  RUNNING: 'text-yellow-600',
  SKIPPED: 'text-gray-400',
  PENDING: 'text-gray-400',
};

const MAX_LOG_DISPLAY = 10_000;

export function StepRow({ step }: { step: WorkflowStepRunDto }) {
  const [expanded, setExpanded] = useState(false);

  const log = step.log ?? '';
  const isTruncated = log.length > MAX_LOG_DISPLAY;
  const displayLog = isTruncated ? log.slice(-MAX_LOG_DISPLAY) : log;

  let outputs: Record<string, string> = {};
  try {
    if (step.outputJson) outputs = JSON.parse(step.outputJson);
  } catch {}

  const hasOutputs = Object.keys(outputs).length > 0;

  return (
    <div className="px-4 py-3">
      <button
        className="w-full flex items-center gap-3 text-left"
        onClick={() => setExpanded(e => !e)}
      >
        <span className={`text-sm font-medium ${STATUS_COLORS[step.status] ?? ''}`}>
          {step.status}
        </span>
        <span className="text-sm flex-1">{step.stepName}</span>
        <span className="text-xs text-muted-foreground">{step.stepType}</span>
        {(log || hasOutputs) && (
          <span className="text-xs text-muted-foreground">{expanded ? '▲' : '▼'}</span>
        )}
      </button>

      {step.errorReason && (
        <p className="mt-1 text-xs text-red-600">{step.errorReason}</p>
      )}

      {expanded && (
        <div className="mt-3 space-y-3">
          {log && (
            <div>
              {isTruncated && (
                <p className="text-xs text-amber-600 mb-1">
                  [truncated — showing last 10,000 characters]
                </p>
              )}
              <pre className="text-xs bg-black/90 text-green-300 p-3 rounded overflow-x-auto max-h-96 overflow-y-auto whitespace-pre-wrap">
                {displayLog}
              </pre>
            </div>
          )}

          {hasOutputs && (
            <div>
              <p className="text-xs font-medium text-muted-foreground mb-1">Outputs</p>
              <table className="w-full text-xs border rounded overflow-hidden">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="text-left p-2 font-medium">Key</th>
                    <th className="text-left p-2 font-medium">Value</th>
                  </tr>
                </thead>
                <tbody>
                  {Object.entries(outputs).map(([key, value]) => (
                    <tr key={key} className="border-t">
                      <td className="p-2 font-mono">{key}</td>
                      <td className="p-2 font-mono break-all">{value}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
