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
import { mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import path from 'node:path';

// Root of the container's Conductor filesystem contract. Always /conductor in production —
// overridable only so entrypoint.selftest.mjs can run outside a container, on a real filesystem.
const RUNNER_ROOT = process.env.CONDUCTOR_RUNNER_ROOT || '/conductor';
const INPUTS_DIR = path.join(RUNNER_ROOT, 'inputs');
const WORKSPACE_DIR = path.join(RUNNER_ROOT, 'workspace');
const MCP_CONFIG_PATH = path.join(RUNNER_ROOT, 'mcp-config.json');
// Populated from CONDUCTOR_ARTIFACTS_DIR when the job consumes artifacts — on a self-hosted daemon
// this is a bind mount the daemon already downloaded into (see job-runner.ts), but the entrypoint
// downloads into it here regardless (idempotent overwrite), since it's the only option at all on
// Cloud Run, which has no shared host volume.
const ARTIFACTS_DIR = process.env.CONDUCTOR_ARTIFACTS_DIR || path.join(RUNNER_ROOT, 'artifacts');
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
 * Extracts the {workerJobId} path segment from CONDUCTOR_STEP_COMPLETE_URL
 * (".../workflow-runs/{runId}/steps/{workerJobId}/complete"), so the log-chunk callback can tag
 * its lines with it. Never throws — an unexpected URL shape just means the field is omitted
 * (the backend treats it as optional), rather than guessed at.
 */
function extractWorkerJobId(stepCompleteUrl) {
  try {
    const segments = new URL(stepCompleteUrl).pathname.split('/').filter(Boolean);
    const stepsIndex = segments.lastIndexOf('steps');
    if (stepsIndex === -1 || stepsIndex + 1 >= segments.length) return undefined;
    return segments[stepsIndex + 1];
  } catch {
    return undefined;
  }
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

/**
 * Downloads every consumed artifact (from CONDUCTOR_CONSUMED_ARTIFACTS_JSON, a JSON array of
 * `{name, downloadUrl}`) into {@link ARTIFACTS_DIR}. No-op if the env var is absent (the job
 * consumes nothing). A download failure is a ConfigError — a consumer step that can't get the
 * files it declared `consumes:` on has nothing sensible to fall back to.
 */
async function materializeConsumedArtifacts(env) {
  const raw = env.CONDUCTOR_CONSUMED_ARTIFACTS_JSON;
  if (!raw) return;
  let artifacts;
  try {
    artifacts = JSON.parse(raw);
  } catch (err) {
    throw new ConfigError(`CONDUCTOR_CONSUMED_ARTIFACTS_JSON is not valid JSON: ${err.message}`);
  }
  mkdirSync(ARTIFACTS_DIR, { recursive: true });
  for (const { name, downloadUrl } of artifacts) {
    if (!isSafeFilename(name)) {
      throw new ConfigError(`Rejected unsafe consumed-artifact name: ${name}`);
    }
    let res;
    try {
      res = await fetch(downloadUrl);
    } catch (err) {
      throw new ConfigError(`Failed to download consumed artifact '${name}': ${err.message}`);
    }
    if (!res.ok) {
      throw new ConfigError(`Failed to download consumed artifact '${name}': HTTP ${res.status}`);
    }
    writeFileSync(path.join(ARTIFACTS_DIR, name), Buffer.from(await res.arrayBuffer()));
  }
}

/** POSTs JSON and returns the parsed response body, or null on any failure (non-2xx or network error). */
async function postJsonForResult(url, token, body) {
  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });
    if (!res.ok) return null;
    return await res.json();
  } catch {
    return null;
  }
}

/**
 * PUTs raw bytes to an artifact's upload URL. A signed GCS URL is self-contained and must NOT
 * carry an Authorization header; the local-profile passthrough URL is backend-relative (starts
 * with CONDUCTOR_API_URL) and DOES need the run-token bearer header — that's the only signal
 * available to tell the two apart (mirrors job-runner.ts's putArtifactContent).
 */
async function putArtifactBytes(uploadUrl, content, runToken, apiUrl) {
  const headers = {};
  if (uploadUrl.startsWith(apiUrl)) {
    headers.Authorization = `Bearer ${runToken}`;
  }
  try {
    const res = await fetch(uploadUrl, { method: 'PUT', headers, body: content });
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * Uploads every artifact declared in CONDUCTOR_STEP_ARTIFACTS_JSON (a JSON array of
 * `{name, path}`), reading each from {@link WORKSPACE_DIR}. No-op (returns ok) if the env var is
 * absent. Stops at the first problem: a missing declared file, or any create/PUT/complete HTTP
 * step failing.
 */
async function uploadDeclaredArtifacts(env) {
  const raw = env.CONDUCTOR_STEP_ARTIFACTS_JSON;
  if (!raw) return { ok: true };
  let artifacts;
  try {
    artifacts = JSON.parse(raw);
  } catch (err) {
    return { ok: false, errorReason: 'CLAUDE_ARTIFACT_UPLOAD_FAILED', message: `CONDUCTOR_STEP_ARTIFACTS_JSON is not valid JSON: ${err.message}` };
  }
  const artifactsUrl = env.CONDUCTOR_ARTIFACTS_URL;
  if (!artifactsUrl) {
    return { ok: false, errorReason: 'CLAUDE_ARTIFACT_UPLOAD_FAILED', message: 'CONDUCTOR_ARTIFACTS_URL is required when artifacts are declared' };
  }
  for (const { name, path: relPath } of artifacts) {
    let content;
    try {
      content = readFileSync(path.join(WORKSPACE_DIR, relPath));
    } catch {
      return { ok: false, errorReason: 'CLAUDE_ARTIFACT_MISSING', message: `declared artifact '${name}' not found at ${relPath}` };
    }
    const created = await postJsonForResult(artifactsUrl, env.CONDUCTOR_RUN_TOKEN, { jobId: env.CONDUCTOR_JOB_ID, name });
    if (!created) {
      return { ok: false, errorReason: 'CLAUDE_ARTIFACT_UPLOAD_FAILED', message: `failed to declare artifact '${name}'` };
    }
    const putOk = await putArtifactBytes(created.uploadUrl, content, env.CONDUCTOR_RUN_TOKEN, env.CONDUCTOR_API_URL);
    if (!putOk) {
      return { ok: false, errorReason: 'CLAUDE_ARTIFACT_UPLOAD_FAILED', message: `upload failed for artifact '${name}'` };
    }
    const completedResult = await postJson(`${artifactsUrl}/${created.artifactId}/complete`, env.CONDUCTOR_RUN_TOKEN, {});
    if (!completedResult) {
      return { ok: false, errorReason: 'CLAUDE_ARTIFACT_UPLOAD_FAILED', message: `complete failed for artifact '${name}'` };
    }
  }
  return { ok: true };
}

function writeMcpConfig(env) {
  if (!env.CONDUCTOR_API_KEY) {
    throw new ConfigError('CONDUCTOR_API_KEY is required when CONDUCTOR_MCP_ENABLED is true');
  }
  // CONDUCTOR_TOOLS_VERSION is baked into the dedicated claude-runner image (see
  // Dockerfile.claude-runner) so its pre-warmed npx cache and this invocation agree on the exact
  // same package spec — no network resolution at job start, no drift from what was pinned at
  // image-build time. Absent on the general-purpose image, which always resolves latest.
  const pkgSpec = env.CONDUCTOR_TOOLS_VERSION
    ? `@cliangdev/conductor@${env.CONDUCTOR_TOOLS_VERSION}`
    : '@cliangdev/conductor';
  const config = {
    mcpServers: {
      conductor: {
        command: 'npx',
        args: ['-y', pkgSpec, 'mcp'],
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
 * Unconditionally merged into --allowedTools — see buildClaudeInvocation. The double slash is
 * load-bearing: a single leading slash in a permission rule is relative to the working directory
 * (/conductor/workspace here), so `Read(/conductor/inputs/**)` silently never matches — an
 * absolute-path rule needs the `//` prefix. Seen live as claude being permission-denied on the
 * very inputs Conductor materialized for it.
 */
const ALWAYS_ALLOWED_TOOLS = 'Read(//conductor/inputs/**)';

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

  // --dangerously-skip-permissions is safe (and required) here: this entrypoint only ever runs
  // inside an isolated, single-use worker (a Cloud Run Job execution or equivalent), never
  // interactively. In headless `-p` mode there is no TTY to answer an interactive approval
  // prompt, so without this flag any tool call outside --allowedTools fails permanently with
  // "This command requires approval." The container itself (ephemeral, no shared filesystem/state
  // across executions) plus scoped, short-lived credentials are the real security boundary — see
  // docs/workflows.md.
  const args = [
    '-p',
    env.CONDUCTOR_STEP_PROMPT,
    '--output-format',
    'stream-json',
    '--verbose',
    '--dangerously-skip-permissions',
  ];

  // Always grant Read on the materialized inputs dir, regardless of the caller's allowedTools —
  // seen live: with a restrictive (or absent) allowlist, claude silently refused to Read
  // /conductor/inputs/* and fabricated an apologetic answer instead of erroring. allowedTools
  // grants are additive, so this only ever adds a permission, never narrows the caller's list.
  const allowedTools = env.CONDUCTOR_ALLOWED_TOOLS
    ? `${env.CONDUCTOR_ALLOWED_TOOLS},${ALWAYS_ALLOWED_TOOLS}`
    : ALWAYS_ALLOWED_TOOLS;
  args.push('--allowedTools', allowedTools);

  if (env.CONDUCTOR_MAX_TURNS) {
    args.push('--max-turns', env.CONDUCTOR_MAX_TURNS);
  }
  if (env.CONDUCTOR_OUTPUT_SCHEMA_JSON) {
    // The CLI accepts the schema as an inline JSON string (verified against the installed CLI).
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
 * ~2s batches and enforcing the wrapper timeout (SIGTERM, 10s grace, then SIGKILL). Stdout lines
 * are stream-json events; each is translated to a compact human-readable line for display (see
 * translateEvent) before being queued — this is purely cosmetic and never affects the resultEvent
 * captured for classifyResult. `initialLines` seeds the queue (e.g. the early "container started"
 * line) so it's flushed first, ahead of anything claude itself emits.
 */
function runClaude({ childEnv, args }, env, secrets, initialLines = []) {
  return new Promise((resolve) => {
    // Line buffers per stream — stdout carries the stream-json events (we watch for `result`),
    // stderr is forwarded as-is. Both feed the same pending-lines queue for log posting.
    const lineBuffers = { stdout: '', stderr: '' };
    const workerJobId = extractWorkerJobId(env.CONDUCTOR_STEP_COMPLETE_URL);
    let pendingLines = [...initialLines];
    let droppedLogCount = 0;
    let resultEvent = null;
    let timedOut = false;

    const pushStdoutLine = (line) => {
      const event = tryParseEvent(line);
      if (!event) {
        pendingLines.push(line); // malformed JSON — raw passthrough, nothing silently lost
        return;
      }
      if (event.type === 'result') resultEvent = event; // classification only, untouched by translation
      const translated = translateEvent(event);
      if (translated === null) {
        pendingLines.push(line); // recognized-but-unhandled shape — raw passthrough
      } else {
        pendingLines.push(...translated);
      }
    };

    const child = spawn('claude', args, {
      cwd: WORKSPACE_DIR,
      env: childEnv,
      // args passed as an array, never shell-interpolated — the prompt can contain anything.
      shell: false,
      // stdin ignored (prompt rides -p argv): otherwise claude waits 3s for piped stdin and
      // logs "Warning: no stdin data received" into every run.
      stdio: ['ignore', 'pipe', 'pipe'],
    });

    const flushLogs = async () => {
      if (pendingLines.length === 0) return;
      const lines = pendingLines;
      pendingLines = [];
      const ok = await postJson(
        env.CONDUCTOR_LOG_CHUNK_URL,
        env.CONDUCTOR_RUN_TOKEN,
        // workerJobId is omitted (not just falsy) when extraction fails — JSON.stringify drops
        // undefined-valued keys, and the backend treats a missing field as optional.
        { workerJobId, lines: lines.map((l) => scrub(l, secrets)) },
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
        if (streamName === 'stdout') {
          pushStdoutLine(line);
        } else {
          pendingLines.push(line);
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
      if (lineBuffers.stdout.trim()) pushStdoutLine(lineBuffers.stdout);
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

function truncate(str, maxLen) {
  return str.length > maxLen ? `${str.slice(0, maxLen)}…` : str;
}

/** Preferred arg keys to summarize a tool_use block by, roughly most-to-least informative. */
const TOOL_ARG_PRIORITY = ['file_path', 'path', 'command', 'pattern', 'query', 'url', 'prompt', 'description', 'notebook_path'];

function summarizeToolArgs(input) {
  if (!input || typeof input !== 'object') return '';
  const keys = Object.keys(input);
  if (keys.length === 0) return '';
  const key = TOOL_ARG_PRIORITY.find((k) => k in input) || keys[0];
  const raw = input[key];
  const value = typeof raw === 'string' ? raw : JSON.stringify(raw);
  return `${key}: ${truncate(value, 100)}`;
}

function summarizeToolResultContent(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) {
    return content
      .filter((b) => b && b.type === 'text' && typeof b.text === 'string')
      .map((b) => b.text)
      .join(' ');
  }
  if (content && typeof content === 'object') return JSON.stringify(content);
  return '';
}

/**
 * Translates one parsed stream-json event (from `claude -p --output-format stream-json`) into
 * zero or more compact human-readable log lines, for display in the workflow run UI. Returns
 * `null` for event shapes we don't recognize (or can't make sense of) — the caller falls back to
 * the raw (scrubbed) line so nothing is silently dropped. Returns `[]` when the event is
 * recognized but has nothing worth showing (e.g. an assistant turn with only empty text).
 * Purely a display concern — never consulted for exit-code classification (see classifyResult).
 */
function translateEvent(event) {
  if (!event || typeof event !== 'object') return null;

  switch (event.type) {
    // Housekeeping telemetry, not activity — an "allowed" rate-limit check on every session start
    // is pure noise in the step log. Anything not allowed still surfaces via the result event's
    // rate-limit classification, so dropping these display-only is safe.
    case 'rate_limit_event':
      return [];

    case 'system': {
      // Token-count telemetry streams continuously while the model thinks — drop it from the
      // display log (like rate_limit_event above); other unknown system subtypes still pass raw.
      if (event.subtype === 'thinking_tokens') return [];
      if (event.subtype !== 'init') return null;
      const model = typeof event.model === 'string' ? event.model : 'unknown';
      const session = typeof event.session_id === 'string' ? truncate(event.session_id, 8) : 'unknown';
      return [`→ claude session started (model: ${model}, session: ${session}…)`];
    }

    case 'assistant': {
      const blocks = event.message && Array.isArray(event.message.content) ? event.message.content : null;
      if (!blocks) return null;
      const lines = [];
      for (const block of blocks) {
        if (!block || typeof block !== 'object') continue;
        if (block.type === 'text' && typeof block.text === 'string') {
          const text = block.text.trim();
          if (text) lines.push(`💬 ${truncate(text, 160)}`);
        } else if (block.type === 'tool_use') {
          const name = typeof block.name === 'string' ? block.name : 'unknown';
          const argSummary = summarizeToolArgs(block.input);
          lines.push(`→ tool: ${name}${argSummary ? ` {${argSummary}}` : ''}`);
        }
      }
      return lines;
    }

    case 'user': {
      const blocks = event.message && Array.isArray(event.message.content) ? event.message.content : null;
      if (!blocks) return null;
      const lines = [];
      for (const block of blocks) {
        if (!block || typeof block !== 'object' || block.type !== 'tool_result') continue;
        const summary = truncate(summarizeToolResultContent(block.content).trim(), 160);
        const marker = block.is_error ? '✗ tool result (error)' : '← tool result';
        lines.push(summary ? `${marker}: ${summary}` : marker);
      }
      return lines;
    }

    case 'result': {
      if (event.is_error) {
        const message = typeof event.result === 'string' && event.result ? event.result : String(event.subtype || 'unknown error');
        return [`✗ error: ${truncate(message, 160)}`];
      }
      const turns = event.num_turns !== undefined ? event.num_turns : '?';
      return [`✓ done: ${turns} turns`];
    }

    default:
      return null;
  }
}

/** Cheap, non-blocking start-line text — never shells out (a `claude --version` subprocess costs
 * ~300-400ms, too slow for "first line out"). Only uses env vars that happen to be set, so every
 * run's own log states which pinned versions it's running without needing to exec into the
 * container or dig through image-build history. */
function buildStartLine(env) {
  const versions = [];
  if (env.CONDUCTOR_CLAUDE_VERSION) versions.push(`claude ${env.CONDUCTOR_CLAUDE_VERSION}`);
  if (env.CONDUCTOR_TOOLS_VERSION) versions.push(`conductor-tools ${env.CONDUCTOR_TOOLS_VERSION}`);
  if (versions.length === 0) {
    return '→ container started, launching claude';
  }
  return `→ container started (${versions.join(', ')})`;
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
    await materializeConsumedArtifacts(env);
    invocation = buildClaudeInvocation(env);
  } catch (err) {
    const message = scrub(err.message || String(err), secrets);
    await reportEarlyFailure(env, EXIT.CONFIG_ERROR, 'CLAUDE_CONFIG_ERROR', message);
    process.exit(EXIT.CONFIG_ERROR);
  }

  const runResult = await runClaude(invocation, env, secrets, [buildStartLine(env)]);
  let { exitCode, errorReason, outputs } = classifyResult(runResult, Boolean(env.CONDUCTOR_OUTPUT_SCHEMA_JSON));

  if (exitCode === EXIT.SUCCESS) {
    const artifactResult = await uploadDeclaredArtifacts(env);
    if (!artifactResult.ok) {
      exitCode = EXIT.CONFIG_ERROR;
      errorReason = artifactResult.errorReason;
      localLog(errorReason, scrub(artifactResult.message, secrets));
    }
  }

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
