'use client';

import cronstrue from 'cronstrue';
import { Badge } from '@/components/ui/badge';
import { parseWorkflowYaml, type TriggerKind } from '@/lib/workflowAutomation';
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

export function TriggerBadges({ yaml }: TriggerBadgesProps) {
  let triggers: Array<{ kind: TriggerKind; nextRun?: string }> = [];
  try {
    triggers = parseWorkflowYaml(yaml).triggers.map(t => {
      if (t.kind !== 'schedule' || !t.cron) return { kind: t.kind };
      let humanReadable = t.cron;
      try {
        humanReadable = cronstrue.toString(t.cron, { throwExceptionOnParseError: false });
      } catch {}
      return { kind: t.kind, nextRun: humanReadable };
    });
  } catch {
    // Invalid YAML — render no badges rather than throwing during a keystroke-driven preview.
    triggers = [];
  }

  return (
    <div className="flex gap-1 flex-wrap">
      {triggers.map(t => (
        <Badge key={t.kind} variant="secondary" className="text-xs gap-1">
          <WorkflowTriggerTypeIcon kind={t.kind} className="h-3 w-3" />
          {TRIGGER_LABEL[t.kind]}{t.nextRun ? ` · ${t.nextRun}` : ''}
        </Badge>
      ))}
    </div>
  );
}
