'use client';

import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select } from '@/components/ui/select';
import type { PostHogFormValues, PostHogRegion } from '@/lib/posthogSettings';

/** Project ID, Region, Event filter and Conversion event: shared by the connect form and the edit dialog. */
export function PostHogSettingsFields({
  values,
  onChange,
  idPrefix = 'posthog',
}: {
  values: PostHogFormValues;
  onChange: (next: PostHogFormValues) => void;
  idPrefix?: string;
}) {
  const set = <K extends keyof PostHogFormValues>(k: K, v: PostHogFormValues[K]) => onChange({ ...values, [k]: v });
  return (
    <>
      <div>
        <Label htmlFor={`${idPrefix}-project`}>Project ID</Label>
        <Input
          id={`${idPrefix}-project`}
          value={values.projectId}
          onChange={(e) => set('projectId', e.target.value)}
          placeholder="12345"
          required
        />
      </div>
      <div>
        <Label htmlFor={`${idPrefix}-region`}>Region</Label>
        <Select
          id={`${idPrefix}-region`}
          value={values.region}
          onChange={(e) => set('region', e.target.value as PostHogRegion)}
        >
          <option value="us">US cloud</option>
          <option value="eu">EU cloud</option>
          <option value="custom">Self-hosted</option>
        </Select>
      </div>
      {values.region === 'custom' && (
        <div>
          <Label htmlFor={`${idPrefix}-host`}>Self-hosted URL</Label>
          <Input
            id={`${idPrefix}-host`}
            value={values.customHost}
            onChange={(e) => set('customHost', e.target.value)}
            placeholder="https://posthog.example.com"
          />
        </div>
      )}
      <div>
        <Label htmlFor={`${idPrefix}-filter`}>Event filter (optional)</Label>
        <Input
          id={`${idPrefix}-filter`}
          value={values.eventFilter}
          onChange={(e) => set('eventFilter', e.target.value)}
          placeholder="$host = 'rexipe.io'"
        />
        <p className="mt-1 text-xs text-muted-foreground">Only count events matching property = &apos;value&apos;.</p>
      </div>
      <div>
        <Label htmlFor={`${idPrefix}-conversion`}>Conversion event (optional)</Label>
        <Input
          id={`${idPrefix}-conversion`}
          value={values.conversionEvent}
          onChange={(e) => set('conversionEvent', e.target.value)}
          placeholder="signup_completed"
        />
      </div>
      <div>
        <Label htmlFor={`${idPrefix}-cohort`}>Internal users cohort ID (optional)</Label>
        <Input
          id={`${idPrefix}-cohort`}
          inputMode="numeric"
          value={values.internalCohortId}
          onChange={(e) => set('internalCohortId', e.target.value)}
          placeholder="339420"
        />
        <p className="mt-1 text-xs text-muted-foreground">
          Leaves your team out of every web number. Digits only: the number in the cohort&apos;s PostHog URL.
        </p>
      </div>
      <div>
        <Label htmlFor={`${idPrefix}-exclude`}>Excluded page prefixes (optional)</Label>
        <Input
          id={`${idPrefix}-exclude`}
          value={values.excludePathPrefixes}
          onChange={(e) => set('excludePathPrefixes', e.target.value)}
          placeholder="/share, /s/"
        />
        <p className="mt-1 text-xs text-muted-foreground">
          Comma-separated paths, each starting with /. Pages under them (such as share pages) are not counted.
        </p>
      </div>
    </>
  );
}
