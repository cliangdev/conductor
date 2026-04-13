import { spawn } from 'child_process';
import axios from 'axios';

const FLUSH_INTERVAL_MS = 500;
const FLUSH_LINE_COUNT = 10;

export function startLogStreamer(
  containerName: string,
  workerJobId: string,
  logCallbackUrl: string,
  ephemeralToken: string
): () => void {
  const buffer: string[] = [];
  let flushTimer: ReturnType<typeof setInterval> | null = null;
  let stopped = false;

  async function flush(): Promise<void> {
    if (buffer.length === 0) return;
    const lines = buffer.splice(0, buffer.length);
    try {
      await axios.post(
        logCallbackUrl,
        {
          workerJobId,
          lines,
          timestamp: new Date().toISOString(),
        },
        {
          headers: { Authorization: `Bearer ${ephemeralToken}` },
          timeout: 5000,
        }
      );
    } catch {
      // Log callback failures are non-fatal; continue streaming
    }
  }

  flushTimer = setInterval(() => {
    flush().catch(() => {});
  }, FLUSH_INTERVAL_MS);

  const proc = spawn('docker', ['logs', '-f', containerName], {
    stdio: ['ignore', 'pipe', 'pipe'],
  });

  function handleLine(line: string): void {
    if (stopped) return;
    buffer.push(line);
    if (buffer.length >= FLUSH_LINE_COUNT) {
      flush().catch(() => {});
    }
  }

  let stdoutRemainder = '';
  proc.stdout.on('data', (chunk: Buffer) => {
    const text = stdoutRemainder + chunk.toString();
    const lines = text.split('\n');
    stdoutRemainder = lines.pop() ?? '';
    for (const line of lines) {
      handleLine(line);
    }
  });

  let stderrRemainder = '';
  proc.stderr.on('data', (chunk: Buffer) => {
    const text = stderrRemainder + chunk.toString();
    const lines = text.split('\n');
    stderrRemainder = lines.pop() ?? '';
    for (const line of lines) {
      handleLine(line);
    }
  });

  proc.on('close', () => {
    if (stdoutRemainder) handleLine(stdoutRemainder);
    if (stderrRemainder) handleLine(stderrRemainder);
    flush().catch(() => {});
  });

  return function stop(): void {
    stopped = true;
    if (flushTimer) {
      clearInterval(flushTimer);
      flushTimer = null;
    }
    proc.kill();
    flush().catch(() => {});
  };
}
