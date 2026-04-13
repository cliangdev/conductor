import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';

// Mock cronstrue to return predictable human-readable text
vi.mock('cronstrue', () => ({
  default: {
    toString: (_cron: string, _opts?: unknown) => 'At 09:00 AM, only on Monday',
  },
}));

import { TriggerBadges } from './TriggerBadges';

const ISSUE_YAML = `
on:
  conductor.issue.status_changed:
    statuses: [SUBMITTED]
`;

const WEBHOOK_YAML = `
on:
  webhook:
    secret: abc123
`;

const MANUAL_YAML = `
on:
  workflow_dispatch:
`;

const SCHEDULE_YAML = `
on:
  schedule:
    cron: '0 9 * * 1'
`;

const MULTI_YAML = `
on:
  workflow_dispatch:
  schedule:
    cron: '0 9 * * *'
`;

describe('TriggerBadges', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders issue badge for conductor.issue.status_changed trigger', () => {
    render(<TriggerBadges yaml={ISSUE_YAML} />);
    expect(screen.getByText('issue')).toBeInTheDocument();
  });

  it('renders webhook badge for webhook trigger', () => {
    render(<TriggerBadges yaml={WEBHOOK_YAML} />);
    expect(screen.getByText('webhook')).toBeInTheDocument();
  });

  it('renders manual badge for workflow_dispatch trigger', () => {
    render(<TriggerBadges yaml={MANUAL_YAML} />);
    expect(screen.getByText('manual')).toBeInTheDocument();
  });

  it('renders schedule badge with human-readable cron for schedule trigger', () => {
    render(<TriggerBadges yaml={SCHEDULE_YAML} />);
    const badge = screen.getByText(/schedule/);
    expect(badge).toBeInTheDocument();
    expect(badge.textContent).toContain('schedule ·');
    expect(badge.textContent).toContain('At 09:00 AM, only on Monday');
  });

  it('renders multiple badges when yaml has multiple triggers', () => {
    render(<TriggerBadges yaml={MULTI_YAML} />);
    expect(screen.getByText('manual')).toBeInTheDocument();
    const scheduleBadge = screen.getByText(/schedule/);
    expect(scheduleBadge).toBeInTheDocument();
  });

  it('renders empty when yaml has no recognized triggers', () => {
    const { container } = render(<TriggerBadges yaml="on:\n  unknown_event:\n" />);
    const badges = container.querySelectorAll('[class*="badge"]');
    expect(badges).toHaveLength(0);
  });
});
