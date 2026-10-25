// Shared callback-protocol helpers for the self-reporting entrypoints
// (conductor-claude-entrypoint.mjs and conductor-step-entrypoint.mjs). Installed next to each
// entrypoint in /usr/local/bin and imported as './runner-protocol.mjs'.
//
// Zero npm dependencies: only Node built-ins.

export const LOG_FLUSH_INTERVAL_MS = 2000;
export const COMPLETE_POST_RETRIES = 3;
export const LOG_POST_RETRIES = 1;

export function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Local stdout logger with a prefix; callers must scrub secrets first. */
export function makeLocalLog(prefix) {
  return (...args) => console.log(prefix, ...args);
}

/**
 * Values scrubbed from every log line before it is posted or printed. `names` are env var names
 * always scrubbed; with `pattern: true`, any env value longer than 16 chars whose name matches
 * /KEY|TOKEN|SECRET|PASSWORD/i is scrubbed too. Longest first so a secret containing another is
 * redacted whole.
 */
export function buildSecrets(env, { names = [], pattern = false } = {}) {
  const secrets = new Set();
  for (const name of names) {
    if (typeof env[name] === 'string' && env[name].length > 0) secrets.add(env[name]);
  }
  if (pattern) {
    for (const [name, value] of Object.entries(env)) {
      if (/KEY|TOKEN|SECRET|PASSWORD/i.test(name) && typeof value === 'string' && value.length > 16) {
        secrets.add(value);
      }
    }
  }
  return [...secrets].sort((a, b) => b.length - a.length);
}

export function scrub(line, secrets) {
  let out = line;
  for (const secret of secrets) out = out.split(secret).join('[REDACTED]');
  return out;
}

/** POSTs JSON with the run token as bearer; retries with linear backoff. Resolves true on 2xx. */
export async function postJson(url, token, body, { retries = 0, log = makeLocalLog('[runner-protocol]') } = {}) {
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return true;
    } catch (err) {
      lastErr = err;
      if (attempt < retries) await sleep(500 * (attempt + 1));
    }
  }
  log('post failed:', url, String(lastErr));
  return false;
}

/**
 * Splits a stream into lines: write(chunk) emits each complete non-blank line via onLine, keeping
 * a trailing partial line buffered; end() hands the remaining partial line (if non-blank) to onLine.
 */
export function createLineSplitter(onLine) {
  let buffer = '';
  return {
    write(chunk) {
      buffer += chunk.toString('utf8');
      const lines = buffer.split('\n');
      buffer = lines.pop();
      for (const line of lines) if (line.trim() !== '') onLine(line);
    },
    end() {
      if (buffer.trim()) onLine(buffer);
      buffer = '';
    },
  };
}

/**
 * Queues log lines and posts them to the log-chunk URL in ~2 s batches (scrubbed). `workerJobId`
 * is omitted from the body when undefined. Call stop() when the child has ended, then flush().
 */
export function createLogBatcher({ url, token, workerJobId, secrets, log, initialLines = [] }) {
  let pending = [...initialLines];
  let dropped = 0;
  const flush = async () => {
    if (pending.length === 0) return;
    const lines = pending;
    pending = [];
    const ok = await postJson(
      url,
      token,
      { workerJobId, lines: lines.map((l) => scrub(l, secrets)) },
      { retries: LOG_POST_RETRIES, log },
    );
    if (!ok) dropped += lines.length;
  };
  const timer = setInterval(() => flush().catch(() => {}), LOG_FLUSH_INTERVAL_MS);
  return {
    push: (...lines) => void pending.push(...lines),
    flush,
    stop: () => clearInterval(timer),
    get dropped() {
      return dropped;
    },
  };
}

/**
 * After `minutes`, calls onTimeout() then kill('SIGTERM'); kill('SIGKILL') follows after graceMs.
 * Returns clear() which cancels both timers.
 */
export function armTimeout({ minutes, graceMs, kill, onTimeout }) {
  let killTimer;
  const hardTimer = setTimeout(() => {
    if (onTimeout) onTimeout();
    kill('SIGTERM');
    killTimer = setTimeout(() => {
      try {
        kill('SIGKILL');
      } catch {
        // already exited
      }
    }, graceMs);
  }, minutes * 60_000);
  return {
    clear() {
      clearTimeout(hardTimer);
      clearTimeout(killTimer);
    },
  };
}
