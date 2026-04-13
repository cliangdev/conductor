import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act, waitFor } from '@testing-library/react';
import { WorkflowLogStream } from './WorkflowLogStream';

type EventListenerMap = Record<string, ((e: MessageEvent) => void)[]>;

class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  withCredentials: boolean;
  private listeners: EventListenerMap = {};
  onerror: ((e: Event) => void) | null = null;
  closed = false;

  constructor(url: string, init?: { withCredentials?: boolean }) {
    this.url = url;
    this.withCredentials = init?.withCredentials ?? false;
    MockEventSource.instances.push(this);
  }

  addEventListener(event: string, handler: (e: MessageEvent) => void) {
    if (!this.listeners[event]) this.listeners[event] = [];
    this.listeners[event].push(handler);
  }

  emit(event: string, data: string) {
    const handlers = this.listeners[event] ?? [];
    const e = new MessageEvent(event, { data });
    handlers.forEach(h => h(e));
  }

  close() {
    this.closed = true;
  }
}

describe('WorkflowLogStream', () => {
  beforeEach(() => {
    MockEventSource.instances = [];
    vi.stubGlobal('EventSource', MockEventSource);
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows waiting message when running with no lines yet', () => {
    render(<WorkflowLogStream runId="run-1" isRunning={true} />);
    expect(screen.getByText('Waiting for logs...')).toBeInTheDocument();
  });

  it('creates EventSource when isRunning is true', () => {
    render(<WorkflowLogStream runId="run-1" isRunning={true} />);
    expect(MockEventSource.instances).toHaveLength(1);
    expect(MockEventSource.instances[0].url).toContain('/api/v1/workflow-runs/run-1/logs/stream');
    expect(MockEventSource.instances[0].withCredentials).toBe(true);
  });

  it('does not create EventSource when isRunning is false', () => {
    render(<WorkflowLogStream runId="run-1" isRunning={false} />);
    expect(MockEventSource.instances).toHaveLength(0);
  });

  it('shows static log lines when isRunning is false', async () => {
    const staticLog = `line one\nline two`;
    render(<WorkflowLogStream runId="run-1" isRunning={false} staticLog={staticLog} />);
    await waitFor(() => {
      expect(screen.getByText('line one')).toBeInTheDocument();
      expect(screen.getByText('line two')).toBeInTheDocument();
    });
  });

  it('appends lines from log-chunk events', () => {
    render(<WorkflowLogStream runId="run-1" isRunning={true} />);
    const es = MockEventSource.instances[0];

    act(() => {
      es.emit('log-chunk', JSON.stringify({ lines: ['hello', 'world'], timestamp: '2026-04-12T00:00:00Z' }));
    });

    expect(screen.getByText('hello')).toBeInTheDocument();
    expect(screen.getByText('world')).toBeInTheDocument();
  });

  it('closes EventSource on run-complete event', () => {
    render(<WorkflowLogStream runId="run-1" isRunning={true} />);
    const es = MockEventSource.instances[0];

    act(() => {
      es.emit('run-complete', JSON.stringify({ status: 'SUCCESS' }));
    });

    expect(es.closed).toBe(true);
  });

  it('closes EventSource on component unmount', () => {
    const { unmount } = render(<WorkflowLogStream runId="run-1" isRunning={true} />);
    const es = MockEventSource.instances[0];
    expect(es.closed).toBe(false);

    unmount();

    expect(es.closed).toBe(true);
  });

  it('hides waiting message after run completes with no logs', () => {
    render(<WorkflowLogStream runId="run-1" isRunning={true} />);
    const es = MockEventSource.instances[0];

    act(() => {
      es.emit('run-complete', JSON.stringify({ status: 'FAILED' }));
    });

    expect(screen.queryByText('Waiting for logs...')).not.toBeInTheDocument();
  });

  it('closes EventSource on error', () => {
    render(<WorkflowLogStream runId="run-1" isRunning={true} />);
    const es = MockEventSource.instances[0];

    act(() => {
      if (es.onerror) es.onerror(new Event('error'));
    });

    expect(es.closed).toBe(true);
  });
});
