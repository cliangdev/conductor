#!/usr/bin/env node
// Generic self-reporting entrypoint for a `docker` workflow step that runs on a BYO Cloud Run
// runtime target (today: the system workflow `conductor-creative-render`). Sibling of
// conductor-claude-entrypoint.mjs and speaks the same callback protocol, but runs a plain shell
// script instead of Claude: `sh -c "$CONDUCTOR_STEP_RUN"`. The script's stdout/stderr stream to the
// log-chunk URL; on exit the result is posted to the step-complete URL.
//
// On a self-hosted daemon this file is NOT used: the daemon runs the step's `run:` itself with
// `--entrypoint sh` and reports the result on the step's behalf. Cloud Run Jobs have no such
// launcher, so the job's command is `conductor-step-entrypoint`.
//
// Exit codes: 0 success; 20 missing/invalid env (STEP_CONFIG_ERROR); 124 timeout (STEP_TIMEOUT);
// anything else is the script's own exit code (STEP_FAILED).
//
// Zero npm dependencies: only Node built-ins.

import { spawn } from 'node:child_process';

const LOG_FLUSH_INTERVAL_MS = 2000;
const COMPLETE_POST_RETRIES = 3;
const LOG_POST_RETRIES = 1;
const KILL_GRACE_MS = Number(process.env.CONDUCTOR_KILL_GRACE_MS) > 0 ? Number(process.env.CONDUCTOR_KILL_GRACE_MS) : 10_000;
const DEFAULT_TIMEOUT_MINUTES = 30;

const EXIT = { CONFIG_ERROR: 20, TIMEOUT: 124 };

const REQUIRED_VARS = [
  'CONDUCTOR_STEP_RUN',
  'CONDUCTOR_API_URL',
  'CONDUCTOR_PROJECT_ID',
  'CONDUCTOR_WORKFLOW_RUN_ID',
  'CONDUCTOR_JOB_ID',
  'CONDUCTOR_WORKER_JOB_ID',
  'CONDUCTOR_RUN_TOKEN',
  'CONDUCTOR_LOG_CHUNK_URL',
  'CONDUCTOR_STEP_COMPLETE_URL',
];

/** Values scrubbed from every log line before it is posted or printed. Never log these raw. */
function buildSecrets(env) {
  const secrets = new Set();
  for (const name of ['CONDUCTOR_RUN_TOKEN', 'CONDUCTOR_API_KEY']) {
    if (typeof env[name] === 'string' && env[name].length > 0) secrets.add(env[name]);
  }
  for (const [name, value] of Object.entries(env)) {
    if (/KEY|TOKEN|SECRET|PASSWORD/i.test(name) && typeof value === 'string' && value.length > 16) {
      secrets.add(value);
    }
  }
  // Longest first so a secret that contains another is redacted whole.
  return [...secrets].sort((a, b) => b.length - a.length);
}

function scrub(line, secrets) {
  let out = line;
  for (const secret of secrets) out = out.split(secret).join('[REDACTED]');
  return out;
}

function localLog(...args) {
  // Local stdout only; callers must scrub first.
  console.log('[conductor-step-entrypoint]', ...args);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function postJson(url, token, body, { retries = 0 } = {}) {
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
  localLog('post failed:', url, String(lastErr));
  return false;
}

async function reportComplete(env, exitCode, errorReason) {
  return postJson(
    env.CONDUCTOR_STEP_COMPLETE_URL,
    env.CONDUCTOR_RUN_TOKEN,
    { status: exitCode === 0 ? 'SUCCESS' : 'FAILED', exitCode, errorReason, outputs: {} },
    { retries: COMPLETE_POST_RETRIES },
  );
}

/** Runs the script; resolves with { code, timedOut } once the child and its log flush are done. */
function runScript(env, secrets) {
  return new Promise((resolve) => {
    let pendingLines = [];
    let droppedLogCount = 0;
    let timedOut = false;
    const lineBuffers = { stdout: '', stderr: '' };

    // Own process group so a timeout kills the whole tree (the script may fork browsers, ffmpeg...).
    const child = spawn('sh', ['-c', env.CONDUCTOR_STEP_RUN], {
      env,
      stdio: ['ignore', 'pipe', 'pipe'],
      detached: true,
    });

    const killGroup = (signal) => {
      try {
        process.kill(-child.pid, signal);
      } catch {
        try {
          child.kill(signal);
        } catch {
          // already exited
        }
      }
    };

    const flushLogs = async () => {
      if (pendingLines.length === 0) return;
      const lines = pendingLines;
      pendingLines = [];
      const ok = await postJson(
        env.CONDUCTOR_LOG_CHUNK_URL,
        env.CONDUCTOR_RUN_TOKEN,
        { workerJobId: env.CONDUCTOR_WORKER_JOB_ID, lines: lines.map((l) => scrub(l, secrets)) },
        { retries: LOG_POST_RETRIES },
      );
      if (!ok) droppedLogCount += lines.length;
    };
    const flushTimer = setInterval(() => flushLogs().catch(() => {}), LOG_FLUSH_INTERVAL_MS);

    const onData = (name) => (chunk) => {
      lineBuffers[name] += chunk.toString('utf8');
      const lines = lineBuffers[name].split('\n');
      lineBuffers[name] = lines.pop(); // trailing partial line stays buffered
      for (const line of lines) if (line.trim() !== '') pendingLines.push(line);
    };
    child.stdout.on('data', onData('stdout'));
    child.stderr.on('data', onData('stderr'));

    const minutes = Number(env.CONDUCTOR_TIMEOUT_MINUTES) > 0 ? Number(env.CONDUCTOR_TIMEOUT_MINUTES) : DEFAULT_TIMEOUT_MINUTES;
    let killTimer;
    const hardTimer = setTimeout(() => {
      timedOut = true;
      pendingLines.push(`Step timed out after ${minutes} minutes; stopping it.`);
      killGroup('SIGTERM');
      killTimer = setTimeout(() => killGroup('SIGKILL'), KILL_GRACE_MS);
    }, minutes * 60_000);

    child.on('error', (err) => pendingLines.push(`Could not start the step: ${err.message}`));

    child.on('close', async (code, signal) => {
      clearInterval(flushTimer);
      clearTimeout(hardTimer);
      clearTimeout(killTimer);
      for (const name of ['stdout', 'stderr']) if (lineBuffers[name].trim()) pendingLines.push(lineBuffers[name]);
      await flushLogs();
      if (droppedLogCount > 0) localLog(`dropped ${droppedLogCount} log lines after repeated post failures`);
      // Killed by a signal without our timeout: report 128+signal like a shell would.
      const exitCode = code ?? (signal ? 128 + ({ SIGTERM: 15, SIGKILL: 9, SIGINT: 2 }[signal] ?? 1) : 1);
      resolve({ code: exitCode, timedOut });
    });
  });
}

async function main() {
  const env = process.env;
  const secrets = buildSecrets(env);

  const missing = REQUIRED_VARS.filter((name) => !env[name]);
  if (missing.length > 0) {
    localLog(`missing required env: ${missing.join(', ')}`);
    // Best effort: only possible when the callback URL and token made it through.
    if (env.CONDUCTOR_STEP_COMPLETE_URL && env.CONDUCTOR_RUN_TOKEN) {
      await reportComplete(env, EXIT.CONFIG_ERROR, 'STEP_CONFIG_ERROR');
    }
    process.exit(EXIT.CONFIG_ERROR);
  }

  const result = await runScript(env, secrets);
  const exitCode = result.timedOut ? EXIT.TIMEOUT : result.code;
  const errorReason = exitCode === 0 ? undefined : result.timedOut ? 'STEP_TIMEOUT' : 'STEP_FAILED';
  localLog(`step exited code=${result.code} timedOut=${result.timedOut} -> ${errorReason || 'SUCCESS'}`);

  await reportComplete(env, exitCode, errorReason);
  process.exit(exitCode);
}

main().catch(async (err) => {
  const env = process.env;
  localLog('unhandled error:', scrub(String((err && err.message) || err), buildSecrets(env)));
  if (env.CONDUCTOR_STEP_COMPLETE_URL && env.CONDUCTOR_RUN_TOKEN) {
    await reportComplete(env, EXIT.CONFIG_ERROR, 'STEP_CONFIG_ERROR');
  }
  process.exit(EXIT.CONFIG_ERROR);
});
