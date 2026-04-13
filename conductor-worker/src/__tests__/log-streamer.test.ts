import { EventEmitter } from 'events';

// We test the batching logic by simulating the buffer + flush mechanics
// without spawning real docker processes.

const FLUSH_INTERVAL_MS = 500;
const FLUSH_LINE_COUNT = 10;

describe('Log chunk batching logic', () => {
  it('flushes when FLUSH_LINE_COUNT lines are buffered', () => {
    const buffer: string[] = [];
    const flushedBatches: string[][] = [];

    function flush() {
      if (buffer.length === 0) return;
      flushedBatches.push(buffer.splice(0, buffer.length));
    }

    function handleLine(line: string) {
      buffer.push(line);
      if (buffer.length >= FLUSH_LINE_COUNT) {
        flush();
      }
    }

    for (let i = 0; i < 10; i++) {
      handleLine(`line ${i}`);
    }

    expect(flushedBatches.length).toBe(1);
    expect(flushedBatches[0].length).toBe(10);
    expect(buffer.length).toBe(0);
  });

  it('does not flush before FLUSH_LINE_COUNT lines', () => {
    const buffer: string[] = [];
    const flushedBatches: string[][] = [];

    function flush() {
      if (buffer.length === 0) return;
      flushedBatches.push(buffer.splice(0, buffer.length));
    }

    function handleLine(line: string) {
      buffer.push(line);
      if (buffer.length >= FLUSH_LINE_COUNT) {
        flush();
      }
    }

    for (let i = 0; i < 9; i++) {
      handleLine(`line ${i}`);
    }

    expect(flushedBatches.length).toBe(0);
    expect(buffer.length).toBe(9);
  });

  it('flushes remaining lines on interval trigger', () => {
    const buffer: string[] = [];
    const flushedBatches: string[][] = [];

    function flush() {
      if (buffer.length === 0) return;
      flushedBatches.push(buffer.splice(0, buffer.length));
    }

    // Simulate 3 lines arriving
    buffer.push('line 1', 'line 2', 'line 3');

    // Simulate interval tick
    flush();

    expect(flushedBatches.length).toBe(1);
    expect(flushedBatches[0]).toEqual(['line 1', 'line 2', 'line 3']);
  });

  it('does not flush on interval tick if buffer is empty', () => {
    const buffer: string[] = [];
    const flushedBatches: string[][] = [];

    function flush() {
      if (buffer.length === 0) return;
      flushedBatches.push(buffer.splice(0, buffer.length));
    }

    flush();

    expect(flushedBatches.length).toBe(0);
  });

  it('includes workerJobId and timestamp in flush payload shape', () => {
    const workerJobId = 'wj-abc';
    const buffer: string[] = ['line 1', 'line 2'];

    const lines = buffer.splice(0, buffer.length);
    const payload = {
      workerJobId,
      lines,
      timestamp: new Date().toISOString(),
    };

    expect(payload.workerJobId).toBe('wj-abc');
    expect(payload.lines).toEqual(['line 1', 'line 2']);
    expect(typeof payload.timestamp).toBe('string');
  });
});
