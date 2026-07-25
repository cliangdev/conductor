'use client';

import cronstrue from 'cronstrue';
import { Badge } from '@/components/ui/badge';
import { parseWorkflowYaml, isManualTrigger, type TriggerKind, type WorkflowTrigger } from '@/lib/workflowAutomation';
import { WorkflowTriggerTypeIcon } from '@/components/workflow/WorkflowStepTypeIcon';

interface TriggerBadgesProps {
  yaml: string;
}

const TRIGGER_LABEL: Record<TriggerKind, string> = {
  workflow_dispatch: 'manual',
  webhook: 'webhook',
  work_item_status_changed: 'work item',
  github_pull_request: 'github PR',
  schedule: 'schedule',
};

/** Lowercase trigger label, shared with the workflow detail header's breadcrumb description. */
export function triggerLabel(trigger: WorkflowTrigger): string {
  if (trigger.kind === 'workflow_dispatch' && !isManualTrigger(trigger)) return 'system-triggered';
  return TRIGGER_LABEL[trigger.kind];
}

export function TriggerBadges({ yaml }: TriggerBadgesProps) {
  let triggers: WorkflowTrigger[] = [];
  try {
    triggers = parseWorkflowYaml(yaml).triggers;
  } catch {
    // Invalid YAML — render no badges rather than throwing during a keystroke-driven preview.
    triggers = [];
  }

  return (
    <div className="flex gap-1 flex-wrap">
      {triggers.map(t => {
        let nextRun: string | undefined;
        if (t.kind === 'schedule' && t.cron) {
          nextRun = t.cron;
          try {
            nextRun = cronstrue.toString(t.cron, { throwExceptionOnParseError: false });
          } catch {}
        }
        return (
          <Badge key={t.kind} variant="secondary" className="text-xs gap-1">
            <WorkflowTriggerTypeIcon kind={t.kind} className="h-3 w-3" />
            {triggerLabel(t)}{nextRun ? ` · ${nextRun}` : ''}
          </Badge>
        );
      })}
    </div>
  );
}
