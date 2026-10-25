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
import {
  COMPLETE_POST_RETRIES,
  armTimeout,
  buildSecrets as sharedBuildSecrets,
  createLineSplitter,
  createLogBatcher,
  makeLocalLog,
  postJson as sharedPostJson,
  scrub,
} from './runner-protocol.mjs';

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

const localLog = makeLocalLog('[conductor-step-entrypoint]');
const buildSecrets = (env) => sharedBuildSecrets(env, { names: ['CONDUCTOR_RUN_TOKEN', 'CONDUCTOR_API_KEY'], pattern: true });
const postJson = (url, token, body, opts = {}) => sharedPostJson(url, token, body, { ...opts, log: localLog });

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
    let timedOut = false;

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

    const logs = createLogBatcher({
      url: env.CONDUCTOR_LOG_CHUNK_URL,
      token: env.CONDUCTOR_RUN_TOKEN,
      workerJobId: env.CONDUCTOR_WORKER_JOB_ID,
      secrets,
      log: localLog,
    });
    const stdout = createLineSplitter((line) => logs.push(line));
    const stderr = createLineSplitter((line) => logs.push(line));
    child.stdout.on('data', (chunk) => stdout.write(chunk));
    child.stderr.on('data', (chunk) => stderr.write(chunk));

    const minutes = Number(env.CONDUCTOR_TIMEOUT_MINUTES) > 0 ? Number(env.CONDUCTOR_TIMEOUT_MINUTES) : DEFAULT_TIMEOUT_MINUTES;
    const timeout = armTimeout({
      minutes,
      graceMs: KILL_GRACE_MS,
      kill: killGroup,
      onTimeout: () => {
        timedOut = true;
        logs.push(`Step timed out after ${minutes} minutes; stopping it.`);
      },
    });

    child.on('error', (err) => logs.push(`Could not start the step: ${err.message}`));

    child.on('close', async (code, signal) => {
      logs.stop();
      timeout.clear();
      stdout.end();
      stderr.end();
      await logs.flush();
      if (logs.dropped > 0) localLog(`dropped ${logs.dropped} log lines after repeated post failures`);
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
