'use client';

import cronstrue from 'cronstrue';
import { Badge } from '@/components/ui/badge';

interface TriggerBadgesProps {
  yaml: string;
}

export function TriggerBadges({ yaml }: TriggerBadgesProps) {
  const triggers: Array<{ label: string; nextRun?: string }> = [];

  if (yaml.includes('conductor.issue.status_changed')) triggers.push({ label: 'issue' });
  if (yaml.includes('webhook:')) triggers.push({ label: 'webhook' });
  if (yaml.includes('workflow_dispatch')) triggers.push({ label: 'manual' });

  const scheduleMatch = yaml.match(/schedule:\s*\n\s*cron:\s*['"]?([^\n'"]+)['"]?/);
  if (scheduleMatch) {
    const cron = scheduleMatch[1].trim();
    let humanReadable = cron;
    try {
      humanReadable = cronstrue.toString(cron, { throwExceptionOnParseError: false });
    } catch {}
    triggers.push({ label: 'schedule', nextRun: humanReadable });
  }

  return (
    <div className="flex gap-1 flex-wrap">
      {triggers.map(t => (
        <Badge key={t.label} variant="secondary" className="text-xs">
          {t.label}{t.nextRun ? ` · ${t.nextRun}` : ''}
        </Badge>
      ))}
    </div>
  );
}
