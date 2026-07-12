'use client';

import { useEffect, useState } from 'react';
import { WorkflowStepRunDto } from '@/types/workflow';
import { WorkflowLogStream } from './WorkflowLogStream';

const STATUS_COLORS: Record<string, string> = {
  SUCCESS: 'text-green-600',
  FAILED:  'text-red-600',
  RUNNING: 'text-yellow-600',
  SKIPPED: 'text-gray-400',
  PENDING: 'text-gray-400',
};

const MAX_LOG_DISPLAY = 10_000;
const MAX_OUTPUT_VALUE_DISPLAY = 400;

// Backend fills stepName with the literal string "unnamed" when a step
// definition has no `name`. Prefer stepId, then stepType, in that case.
function displayStepName(step: WorkflowStepRunDto): string {
  const name = step.stepName?.trim();
  if (name && name !== 'unnamed') return name;
  return step.stepId || step.stepType;
}

function formatOutputValue(value: unknown): string {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

interface ConditionOutput {
  expression?: string;
  result?: boolean;
  branch?: string;
}

function ConditionDetail({ step }: { step: WorkflowStepRunDto }) {
  let conditionData: ConditionOutput = {};
  try {
    if (step.outputJson) conditionData = JSON.parse(step.outputJson) as ConditionOutput;
  } catch {}

  const expression = conditionData.expression ?? step.log ?? '—';
  const result = conditionData.result;
  const branch = conditionData.branch;

  return (
    <div className="mt-3 p-3 rounded bg-muted/30 text-xs space-y-1">
      <div>
        <span className="font-medium text-muted-foreground">Expression: </span>
        <code className="font-mono">{expression}</code>
      </div>
      {result !== undefined && (
        <div>
          <span className="font-medium text-muted-foreground">Result: </span>
          <span className={result ? 'text-green-600 font-semibold' : 'text-red-600 font-semibold'}>
            {result ? 'true' : 'false'}
          </span>
        </div>
      )}
      {branch && (
        <div>
          <span className="font-medium text-muted-foreground">Branch activated: </span>
          <span className="font-semibold">{branch}</span>
        </div>
      )}
    </div>
  );
}

interface StepRowProps {
  step: WorkflowStepRunDto;
  runId?: string;
}

export function StepRow({ step, runId }: StepRowProps) {
  const [expanded, setExpanded] = useState(false);
  const [userToggled, setUserToggled] = useState(false);

  const log = step.log ?? '';
  const isTruncated = log.length > MAX_LOG_DISPLAY;
  const displayLog = isTruncated ? log.slice(-MAX_LOG_DISPLAY) : log;

  const isDockerStep = step.stepType === 'docker';
  const isRunningDockerStep = isDockerStep && step.status === 'RUNNING';

  // Auto-expand while a step is actively running and has (or will stream) log
  // output, so live progress is visible without a manual click. Once the user
  // has toggled it themselves, respect their choice.
  useEffect(() => {
    if (userToggled) return;
    if (step.status === 'RUNNING' && (log || isRunningDockerStep)) {
      setExpanded(true);
    }
  }, [step.status, log, isRunningDockerStep, userToggled]);

  // Append container exit annotation for completed docker steps
  let finalLog = displayLog;
  if (isDockerStep && step.status === 'SUCCESS' && log) {
    finalLog = displayLog + '\n--- container exited 0 ---';
  }

  const isConditionStep = step.stepType === 'condition';

  let outputs: Record<string, unknown> = {};
  try {
    if (step.outputJson && !isConditionStep) outputs = JSON.parse(step.outputJson);
  } catch {}

  const hasOutputs = Object.keys(outputs).length > 0;
  const hasExpandableContent = log || hasOutputs || isRunningDockerStep || isConditionStep;

  return (
    <div className="px-4 py-3">
      <button
        className="w-full flex items-center gap-3 text-left"
        onClick={() => {
          setUserToggled(true);
          setExpanded(e => !e);
        }}
      >
        <span className={`text-sm font-medium ${STATUS_COLORS[step.status] ?? ''}`}>
          {step.status}
        </span>
        <span className="text-sm flex-1">{displayStepName(step)}</span>
        <span className="text-xs text-muted-foreground">{step.stepType}</span>
        {hasExpandableContent && (
          <span className="text-xs text-muted-foreground">{expanded ? '▲' : '▼'}</span>
        )}
      </button>

      {step.errorReason && (
        <p className="mt-1 text-xs text-red-600">{step.errorReason}</p>
      )}

      {expanded && (
        <div className="mt-3 space-y-3">
          {isConditionStep && (
            <ConditionDetail step={step} />
          )}

          {isRunningDockerStep && runId ? (
            <WorkflowLogStream runId={runId} isRunning={true} />
          ) : (
            finalLog && (
              <div>
                {isTruncated && (
                  <p className="text-xs text-amber-600 mb-1">
                    [truncated — showing last 10,000 characters]
                  </p>
                )}
                <pre className="text-xs bg-black/90 text-green-300 p-3 rounded overflow-x-auto max-h-96 overflow-y-auto whitespace-pre-wrap">
                  {finalLog}
                </pre>
              </div>
            )
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
                  {Object.entries(outputs).map(([key, value]) => {
                    const display = formatOutputValue(value);
                    const isLong = display.length > MAX_OUTPUT_VALUE_DISPLAY;
                    return (
                      <tr key={key} className="border-t">
                        <td className="p-2 font-mono">{key}</td>
                        <td className="p-2 font-mono break-all">
                          {isLong ? (
                            <details>
                              <summary className="cursor-pointer text-muted-foreground">
                                {display.slice(0, MAX_OUTPUT_VALUE_DISPLAY)}…
                              </summary>
                              <div className="mt-1 whitespace-pre-wrap">{display}</div>
                            </details>
                          ) : (
                            display
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
