import { describe, it, expect } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { StepRow } from './StepRow';
import { WorkflowStepRunDto } from '@/types/workflow';

function makeStep(overrides: Partial<WorkflowStepRunDto> = {}): WorkflowStepRunDto {
  return {
    id: 'step-1',
    stepId: 'fetch',
    stepName: 'Fetch data',
    stepType: 'http',
    status: 'SUCCESS',
    ...overrides,
  };
}

describe('StepRow', () => {
  // ── Outputs panel ──────────────────────────────────────────────────────────

  it('renders an outputs table from outputJson', () => {
    const step = makeStep({
      log: 'done',
      outputJson: JSON.stringify({ status: 'ready', count: 3 }),
    });
    render(<StepRow step={step} />);

    // Panel auto-expands only while RUNNING; click to expand a SUCCESS step.
    fireEvent.click(screen.getByRole('button'));

    expect(screen.getByText('Outputs')).toBeInTheDocument();
    expect(screen.getByText('status')).toBeInTheDocument();
    expect(screen.getByText('ready')).toBeInTheDocument();
    expect(screen.getByText('count')).toBeInTheDocument();
    expect(screen.getByText('3')).toBeInTheDocument();
  });

  it('truncates long output values behind a details/summary expand affordance', () => {
    const longValue = 'x'.repeat(500);
    const step = makeStep({
      outputJson: JSON.stringify({ body: longValue }),
    });
    render(<StepRow step={step} />);
    fireEvent.click(screen.getByRole('button'));

    // Truncated preview (400 chars + ellipsis) is shown in the summary.
    expect(screen.getByText(`${'x'.repeat(400)}…`)).toBeInTheDocument();
    // Full value is present in the DOM (inside <details>), even if collapsed.
    expect(screen.getByText(longValue)).toBeInTheDocument();
  });

  it('renders nothing for malformed outputJson without crashing', () => {
    const step = makeStep({ log: 'some log', outputJson: '{not valid json' });
    render(<StepRow step={step} />);
    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText('Outputs')).not.toBeInTheDocument();
  });

  it('renders nothing for empty outputJson object', () => {
    const step = makeStep({ log: 'some log', outputJson: '{}' });
    render(<StepRow step={step} />);
    fireEvent.click(screen.getByRole('button'));

    expect(screen.queryByText('Outputs')).not.toBeInTheDocument();
  });

  // ── Name fallback ─────────────────────────────────────────────────────────

  it('shows stepName when present', () => {
    render(<StepRow step={makeStep({ stepName: 'Fetch data' })} />);
    expect(screen.getByText('Fetch data')).toBeInTheDocument();
  });

  it('falls back to stepId when stepName is missing', () => {
    const step = makeStep({ stepName: undefined as unknown as string, stepId: 'fetch-step' });
    render(<StepRow step={step} />);
    expect(screen.getByText('fetch-step')).toBeInTheDocument();
  });

  it('falls back to stepId when stepName is empty', () => {
    const step = makeStep({ stepName: '', stepId: 'fetch-step' });
    render(<StepRow step={step} />);
    expect(screen.getByText('fetch-step')).toBeInTheDocument();
  });

  it('falls back to stepId when stepName is the backend default "unnamed"', () => {
    const step = makeStep({ stepName: 'unnamed', stepId: 'fetch-step' });
    render(<StepRow step={step} />);
    expect(screen.getByText('fetch-step')).toBeInTheDocument();
    expect(screen.queryByText('unnamed')).not.toBeInTheDocument();
  });

  it('falls back to stepType when both stepName and stepId are missing', () => {
    const step = makeStep({
      stepName: 'unnamed',
      stepId: undefined,
      stepType: 'docker',
    });
    render(<StepRow step={step} />);
    // "docker" legitimately appears twice: the name fallback and the type badge.
    expect(screen.getAllByText('docker')).toHaveLength(2);
  });

  // ── Live log visibility ──────────────────────────────────────────────────

  it('auto-expands the log panel by default while RUNNING with log content', () => {
    const step = makeStep({ status: 'RUNNING', log: 'line 1\nline 2' });
    render(<StepRow step={step} />);

    expect(screen.getByText(/line 1/)).toBeInTheDocument();
  });

  it('does not auto-expand a completed step by default', () => {
    const step = makeStep({ status: 'SUCCESS', log: 'line 1\nline 2' });
    render(<StepRow step={step} />);

    expect(screen.queryByText(/line 1/)).not.toBeInTheDocument();
  });

  it('respects a manual collapse even while RUNNING', () => {
    const step = makeStep({ status: 'RUNNING', log: 'line 1' });
    render(<StepRow step={step} />);

    expect(screen.getByText(/line 1/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button'));
    expect(screen.queryByText(/line 1/)).not.toBeInTheDocument();
  });
});
