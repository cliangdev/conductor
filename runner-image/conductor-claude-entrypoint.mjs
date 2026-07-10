#!/usr/bin/env node
// Self-reporting entrypoint for the `claude-code` workflow step. Runs `claude -p` headlessly
// inside the conductor-runner image, streams logs and posts the final result back to the
// Conductor backend via the internal callback URLs supplied in the env contract. Any launcher
// (self-hosted daemon or Cloud Run Job) just runs this image with that env contract — this
// script owns everything Claude-specific. See docs/workflows.md and the design plan's
// "Container env contract" table for the full variable list and exit taxonomy.
//
// Zero npm dependencies: only Node built-ins (process, fs, child_process, global fetch).

import { spawn } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';

// Root of the container's Conductor filesystem contract. Always /conductor in production —
// overridable only so entrypoint.selftest.mjs can run outside a container, on a real filesystem.
const RUNNER_ROOT = process.env.CONDUCTOR_RUNNER_ROOT || '/conductor';
const INPUTS_DIR = path.join(RUNNER_ROOT, 'inputs');
const WORKSPACE_DIR = path.join(RUNNER_ROOT, 'workspace');
const MCP_CONFIG_PATH = path.join(RUNNER_ROOT, 'mcp-config.json');
const OUTPUT_SCHEMA_PATH = path.join(RUNNER_ROOT, 'output-schema.json');
const LOG_FLUSH_INTERVAL_MS = 2000;
const COMPLETE_POST_RETRIES = 3;
const LOG_POST_RETRIES = 1;
const KILL_GRACE_MS = 10_000;

const EXIT = {
  SUCCESS: 0,
  AGENT_ERROR: 10,
  AUTH_ERROR: 11,
  RATE_LIMITED: 12,
  TIMEOUT: 13,
  CONFIG_ERROR: 20,
};

/** Thrown for pre-spawn problems (bad env/inputs) — always maps to exit 20 / CLAUDE_CONFIG_ERROR. */
class ConfigError extends Error {}

const REQUIRED_VARS = [
  'CONDUCTOR_STEP_PROMPT',
  'CONDUCTOR_MCP_ENABLED',
  'CONDUCTOR_API_URL',
  'CONDUCTOR_PROJECT_ID',
  'CONDUCTOR_WORKFLOW_RUN_ID',
  'CONDUCTOR_JOB_ID',
  'CONDUCTOR_WORKER_JOB_ID',
  'CONDUCTOR_RUN_TOKEN',
  'CONDUCTOR_LOG_CHUNK_URL',
  'CONDUCTOR_STEP_COMPLETE_URL',
];

/** Values scrubbed from every log line before it's posted or printed. Never log these raw. */
function buildSecrets(env) {
  return [env.CONDUCTOR_RUN_TOKEN, env.CONDUCTOR_API_KEY, env.ANTHROPIC_API_KEY, env.CLAUDE_CODE_OAUTH_TOKEN]
    .filter((v) => typeof v === 'string' && v.length > 0);
}

function scrub(line, secrets) {
  let out = line;
  for (const secret of secrets) {
    out = out.split(secret).join('[REDACTED]');
  }
  return out;
}

function localLog(...args) {
  // Local stdout only — never a secret-bearing value; callers must scrub first.
  console.log('[conductor-claude-entrypoint]', ...args);
}

async function postJson(url, token, body, { retries = 0 } = {}) {
  let lastErr;
  for (let attempt = 0; attempt <= retries; attempt++) {
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: {
          Authorization: `Bearer ${token}`,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify(body),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return true;
    } catch (err) {
      lastErr = err;
      if (attempt < retries) await sleep(500 * (attempt + 1));
    }
  }
  localLog('post failed:', url, String(lastErr));
  return false;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Best-effort FAILED report used when we can't even get to spawning `claude` (e.g. missing
 * required env). Only fires if we have enough of the contract to reach the backend at all.
 */
async function reportEarlyFailure(env, exitCode, errorReason, message) {
  localLog(errorReason, message);
  if (!env.CONDUCTOR_STEP_COMPLETE_URL || !env.CONDUCTOR_RUN_TOKEN) return;
  await postJson(env.CONDUCTOR_STEP_COMPLETE_URL, env.CONDUCTOR_RUN_TOKEN, {
    status: 'FAILED',
    exitCode,
    errorReason,
    outputs: {},
  });
}

function validateEnv(env) {
  const missing = REQUIRED_VARS.filter((name) => !env[name]);
  return missing;
}

/** Reject path-escaping filenames; inputs are always flat files directly under INPUTS_DIR. */
function isSafeFilename(name) {
  return typeof name === 'string' && name.length > 0 && !name.includes('/') && !name.includes('..');
}

function materializeInputs(env) {
  const raw = env.CONDUCTOR_STEP_INPUTS_JSON;
  if (!raw) return;
  let inputs;
  try {
    inputs = JSON.parse(raw);
  } catch (err) {
    throw new ConfigError(`CONDUCTOR_STEP_INPUTS_JSON is not valid JSON: ${err.message}`);
  }
  mkdirSync(INPUTS_DIR, { recursive: true });
  for (const [filename, content] of Object.entries(inputs)) {
    if (!isSafeFilename(filename)) {
      throw new ConfigError(`Rejected unsafe input filename: ${filename}`);
    }
    writeFileSync(path.join(INPUTS_DIR, filename), String(content));
  }
}

function writeMcpConfig(env) {
  if (!env.CONDUCTOR_API_KEY) {
    throw new ConfigError('CONDUCTOR_API_KEY is required when CONDUCTOR_MCP_ENABLED is true');
  }
  const config = {
    mcpServers: {
      conductor: {
        command: 'npx',
        args: ['-y', '@cliangdev/conductor', 'mcp'],
        env: {
          CONDUCTOR_API_KEY: env.CONDUCTOR_API_KEY,
          CONDUCTOR_API_URL: env.CONDUCTOR_API_URL,
          CONDUCTOR_PROJECT_ID: env.CONDUCTOR_PROJECT_ID,
        },
      },
    },
  };
  writeFileSync(MCP_CONFIG_PATH, JSON.stringify(config));
}

/**
 * Builds the child env and argv for `claude -p`. Auth hygiene: an OAuth token wins over an API
 * key — in `-p` mode an ANTHROPIC_API_KEY silently overrides subscription auth, so we delete it
 * whenever CLAUDE_CODE_OAUTH_TOKEN is present. `--bare` is never used (it doesn't read the OAuth
 * token at all).
 */
function buildClaudeInvocation(env) {
  const childEnv = { ...env };
  if (childEnv.CLAUDE_CODE_OAUTH_TOKEN) {
    delete childEnv.ANTHROPIC_API_KEY;
  }
  // The wrapper owns all callback posting; the claude child (including any Bash it is allowed
  // to run) never needs the run token.
  delete childEnv.CONDUCTOR_RUN_TOKEN;

  const args = ['-p', env.CONDUCTOR_STEP_PROMPT, '--output-format', 'stream-json', '--verbose'];

  if (env.CONDUCTOR_ALLOWED_TOOLS) {
    args.push('--allowedTools', env.CONDUCTOR_ALLOWED_TOOLS);
  }
  if (env.CONDUCTOR_MAX_TURNS) {
    args.push('--max-turns', env.CONDUCTOR_MAX_TURNS);
  }
  if (env.CONDUCTOR_OUTPUT_SCHEMA_JSON) {
    writeFileSync(OUTPUT_SCHEMA_PATH, env.CONDUCTOR_OUTPUT_SCHEMA_JSON);
    // The CLI accepts the schema as an inline JSON string (verified against the installed
    // CLI); passing the raw string here works whether or not a future version also accepts a
    // file path.
    args.push('--json-schema', env.CONDUCTOR_OUTPUT_SCHEMA_JSON);
  }
  if (env.CONDUCTOR_MCP_ENABLED === 'true') {
    writeMcpConfig(env);
    args.push('--mcp-config', MCP_CONFIG_PATH);
  }

  return { childEnv, args };
}

/**
 * Runs `claude -p` as a child process, streaming stdout/stderr to the log-chunk endpoint in
 * ~2s batches and enforcing the wrapper timeout (SIGTERM, 10s grace, then SIGKILL).
 */
function runClaude({ childEnv, args }, env, secrets) {
  return new Promise((resolve) => {
    const child = spawn('claude', args, {
      cwd: WORKSPACE_DIR,
      env: childEnv,
      // args passed as an array, never shell-interpolated — the prompt can contain anything.
      shell: false,
    });

    // Line buffers per stream — stdout carries the stream-json events (we watch for `result`),
    // stderr is forwarded as-is. Both feed the same pending-lines queue for log posting.
    const lineBuffers = { stdout: '', stderr: '' };
    let pendingLines = [];
    let droppedLogCount = 0;
    let resultEvent = null;
    let timedOut = false;

    const flushLogs = async () => {
      if (pendingLines.length === 0) return;
      const lines = pendingLines;
      pendingLines = [];
      const ok = await postJson(
        env.CONDUCTOR_LOG_CHUNK_URL,
        env.CONDUCTOR_RUN_TOKEN,
        { lines: lines.map((l) => scrub(l, secrets)) },
        { retries: LOG_POST_RETRIES },
      );
      if (!ok) droppedLogCount += lines.length;
    };
    const flushTimer = setInterval(() => flushLogs().catch(() => {}), LOG_FLUSH_INTERVAL_MS);

    const onData = (streamName) => (chunk) => {
      lineBuffers[streamName] += chunk.toString('utf8');
      const lines = lineBuffers[streamName].split('\n');
      lineBuffers[streamName] = lines.pop(); // trailing partial line stays buffered
      for (const line of lines) {
        if (line.trim() === '') continue;
        pendingLines.push(line);
        if (streamName === 'stdout') {
          const event = tryParseEvent(line);
          if (event && event.type === 'result') resultEvent = event;
        }
      }
    };
    child.stdout.on('data', onData('stdout'));
    child.stderr.on('data', onData('stderr'));

    const timeoutMinutes = Number(env.CONDUCTOR_TIMEOUT_MINUTES) > 0 ? Number(env.CONDUCTOR_TIMEOUT_MINUTES) : 30;
    const hardTimer = setTimeout(() => {
      timedOut = true;
      child.kill('SIGTERM');
      setTimeout(() => {
        try {
          child.kill('SIGKILL');
        } catch {
          // already exited
        }
      }, KILL_GRACE_MS);
    }, timeoutMinutes * 60_000);

    child.on('close', async (code, signal) => {
      clearInterval(flushTimer);
      clearTimeout(hardTimer);
      // Flush any trailing partial line plus whatever's still pending.
      if (lineBuffers.stdout.trim()) pendingLines.push(lineBuffers.stdout);
      if (lineBuffers.stderr.trim()) pendingLines.push(lineBuffers.stderr);
      await flushLogs();
      if (droppedLogCount > 0) {
        localLog(`dropped ${droppedLogCount} log lines after repeated post failures`);
      }
      resolve({ code, signal, resultEvent, timedOut });
    });

    child.on('error', async (err) => {
      clearInterval(flushTimer);
      clearTimeout(hardTimer);
      pendingLines.push(`[spawn error] ${err.message}`);
      await flushLogs();
      resolve({ code: null, signal: null, resultEvent: null, timedOut: false, spawnError: err });
    });
  });
}

function tryParseEvent(line) {
  try {
    return JSON.parse(line);
  } catch {
    return null;
  }
}

/**
 * Maps a finished (or timed-out) run to an exit code + errorReason + outputs. Kept as one small
 * function since the CLI's error shapes (subtypes, status codes, message text) may drift across
 * versions — this is the single place to update detection heuristics.
 */
function classifyResult({ code, timedOut, resultEvent, spawnError }, hasSchema) {
  if (spawnError) {
    return { exitCode: EXIT.CONFIG_ERROR, errorReason: 'CLAUDE_CONFIG_ERROR', outputs: {} };
  }
  if (timedOut) {
    return { exitCode: EXIT.TIMEOUT, errorReason: 'CLAUDE_TIMEOUT', outputs: {} };
  }
  if (!resultEvent) {
    // Process exited without ever emitting a `result` event on stdout — treat as a config/launch
    // failure (e.g. bad flags, crash before first turn).
    return { exitCode: EXIT.CONFIG_ERROR, errorReason: 'CLAUDE_CONFIG_ERROR', outputs: {} };
  }

  const outputs = buildOutputs(resultEvent, hasSchema);

  if (resultEvent.is_error) {
    const status = resultEvent.api_error_status;
    const subtype = String(resultEvent.subtype || '').toLowerCase();
    const text = String(resultEvent.result || '').toLowerCase();
    const looksLikeAuth =
      status === 401 ||
      subtype.includes('auth') ||
      text.includes('authentication') ||
      text.includes('oauth') ||
      text.includes('invalid api key');
    const looksLikeRateLimit =
      status === 429 ||
      subtype.includes('rate_limit') ||
      subtype.includes('usage_limit') ||
      text.includes('rate limit') ||
      text.includes('usage limit');

    if (looksLikeAuth) {
      return { exitCode: EXIT.AUTH_ERROR, errorReason: 'CLAUDE_AUTH_ERROR', outputs };
    }
    if (looksLikeRateLimit) {
      return { exitCode: EXIT.RATE_LIMITED, errorReason: 'CLAUDE_RATE_LIMITED', outputs };
    }
    return { exitCode: EXIT.AGENT_ERROR, errorReason: 'CLAUDE_AGENT_ERROR', outputs };
  }

  if (code !== 0) {
    return { exitCode: EXIT.AGENT_ERROR, errorReason: 'CLAUDE_AGENT_ERROR', outputs };
  }

  return { exitCode: EXIT.SUCCESS, errorReason: null, outputs };
}

/**
 * Mirrors AgentStepExecutor's output mapping: `text` = final result text, and if structured
 * output is present, `data` = its JSON string plus each top-level field flattened to its own
 * output key. Also carries num_turns/session_id when available.
 */
function buildOutputs(resultEvent, hasSchema) {
  const outputs = {};

  let structured = resultEvent.structured_output;
  if (structured === undefined && hasSchema && typeof resultEvent.result === 'string') {
    // Older/alternate CLI shapes may only put the structured JSON in `result` itself. Only
    // attempted when a schema was requested — a plain-text answer that happens to be JSON must
    // not flatten into outputs.
    const parsed = tryParseEvent(resultEvent.result);
    if (parsed && typeof parsed === 'object') structured = parsed;
  }
  if (structured && typeof structured === 'object') {
    for (const [key, value] of Object.entries(structured)) {
      outputs[key] = typeof value === 'string' ? value : JSON.stringify(value);
    }
    outputs.data = JSON.stringify(structured);
  }

  // Reserved keys are set last so a structured field with the same name can't clobber them.
  outputs.text = typeof resultEvent.result === 'string' ? resultEvent.result : '';
  if (resultEvent.num_turns !== undefined) outputs.num_turns = String(resultEvent.num_turns);
  if (resultEvent.session_id) outputs.session_id = String(resultEvent.session_id);

  return outputs;
}

async function main() {
  const env = process.env;
  const secrets = buildSecrets(env);

  const missing = validateEnv(env);
  if (missing.length > 0) {
    await reportEarlyFailure(
      env,
      EXIT.CONFIG_ERROR,
      'CLAUDE_CONFIG_ERROR',
      `Missing required env vars: ${missing.join(', ')}`,
    );
    process.exit(EXIT.CONFIG_ERROR);
  }

  let invocation;
  try {
    mkdirSync(WORKSPACE_DIR, { recursive: true });
    materializeInputs(env);
    invocation = buildClaudeInvocation(env);
  } catch (err) {
    const message = scrub(err.message || String(err), secrets);
    await reportEarlyFailure(env, EXIT.CONFIG_ERROR, 'CLAUDE_CONFIG_ERROR', message);
    process.exit(EXIT.CONFIG_ERROR);
  }

  const runResult = await runClaude(invocation, env, secrets);
  const { exitCode, errorReason, outputs } = classifyResult(runResult, Boolean(env.CONDUCTOR_OUTPUT_SCHEMA_JSON));

  localLog(`claude exited code=${runResult.code} signal=${runResult.signal} timedOut=${runResult.timedOut} -> ${errorReason || 'SUCCESS'}`);

  await postJson(
    env.CONDUCTOR_STEP_COMPLETE_URL,
    env.CONDUCTOR_RUN_TOKEN,
    {
      status: exitCode === EXIT.SUCCESS ? 'SUCCESS' : 'FAILED',
      exitCode,
      errorReason,
      outputs,
    },
    { retries: COMPLETE_POST_RETRIES },
  );

  process.exit(exitCode);
}

main().catch(async (err) => {
  // Last-resort catch-all: something in main() itself threw outside the handled paths above.
  const env = process.env;
  await reportEarlyFailure(env, EXIT.CONFIG_ERROR, 'CLAUDE_CONFIG_ERROR', `Unhandled entrypoint error: ${err && err.message}`);
  process.exit(EXIT.CONFIG_ERROR);
});
