#!/usr/bin/env node
// Self-test for conductor-step-entrypoint.mjs. Zero dependencies; run with
// `node step-entrypoint.selftest.mjs`. Spawns the entrypoint as a real child process against a
// local HTTP server standing in for the Conductor log-chunk / step-complete callbacks.
// Wired into .github/workflows/publish-render-image.yml.

import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ENTRYPOINT = path.join(path.dirname(fileURLToPath(import.meta.url)), 'conductor-step-entrypoint.mjs');

let failures = 0;
function assert(cond, message) {
  if (!cond) {
    failures++;
    console.error(`  FAIL: ${message}`);
  } else {
    console.log(`  ok: ${message}`);
  }
}

/** completeStatuses: HTTP statuses returned to successive step-complete posts (then 200). */
function startFakeBackend({ completeStatuses = [] } = {}) {
  const calls = { logChunks: [], completes: [], authHeaders: [] };
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      calls.authHeaders.push(req.headers.authorization);
      const parsed = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : {};
      if (req.url === '/log-chunk') {
        calls.logChunks.push(parsed);
      } else if (req.url === '/step-complete') {
        calls.completes.push(parsed);
        const status = completeStatuses[calls.completes.length - 1] ?? 200;
        if (status !== 200) {
          res.writeHead(status);
          res.end();
          return;
        }
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{}');
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, calls, port: server.address().port }));
  });
}

function baseEnv(port, extra = {}) {
  return {
    PATH: process.env.PATH,
    CONDUCTOR_STEP_RUN: 'echo hello',
    CONDUCTOR_API_URL: 'http://example.invalid',
    CONDUCTOR_PROJECT_ID: 'proj_1',
    CONDUCTOR_WORKFLOW_RUN_ID: 'run_1',
    CONDUCTOR_JOB_ID: 'job_1',
    CONDUCTOR_WORKER_JOB_ID: 'wjob_1',
    CONDUCTOR_RUN_TOKEN: 'super-secret-run-token',
    CONDUCTOR_LOG_CHUNK_URL: `http://127.0.0.1:${port}/log-chunk`,
    CONDUCTOR_STEP_COMPLETE_URL: `http://127.0.0.1:${port}/step-complete`,
    CONDUCTOR_TIMEOUT_MINUTES: '1',
    ...extra,
  };
}

function run(env) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [ENTRYPOINT], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    child.stdout.on('data', (d) => (stdout += d));
    child.stderr.on('data', (d) => (stdout += d));
    child.on('close', (code) => resolve({ code, stdout }));
  });
}

const allLines = (calls) => calls.logChunks.flatMap((c) => c.lines);

async function withBackend(opts, fn) {
  const backend = await startFakeBackend(opts);
  try {
    await fn(backend);
  } finally {
    backend.server.close();
  }
}

async function testSuccess() {
  console.log('exit 0 -> SUCCESS posted, exit 0, output streamed');
  await withBackend({}, async ({ calls, port }) => {
    const r = await run(baseEnv(port, { CONDUCTOR_STEP_RUN: 'echo out-line; echo err-line >&2' }));
    assert(r.code === 0, `exits 0 (got ${r.code})`);
    assert(calls.completes.length === 1, 'one step-complete post');
    const c = calls.completes[0];
    assert(c.status === 'SUCCESS' && c.exitCode === 0, `status SUCCESS exitCode 0 (got ${JSON.stringify(c)})`);
    assert(c.errorReason === undefined, 'no errorReason on success');
    assert(JSON.stringify(c.outputs) === '{}', 'outputs is an empty object');
    const lines = allLines(calls);
    assert(lines.includes('out-line') && lines.includes('err-line'), 'stdout and stderr both streamed as log lines');
    assert(calls.logChunks.every((l) => l.workerJobId === 'wjob_1'), 'log chunks carry workerJobId');
    assert(calls.authHeaders.every((h) => h === 'Bearer super-secret-run-token'), 'all posts use the Bearer run token');
  });
}

async function testNonZero() {
  console.log('exit 3 -> FAILED/STEP_FAILED, exit 3');
  await withBackend({}, async ({ calls, port }) => {
    const r = await run(baseEnv(port, { CONDUCTOR_STEP_RUN: 'echo boom >&2; exit 3' }));
    assert(r.code === 3, `exits 3 (got ${r.code})`);
    const c = calls.completes[0];
    assert(c && c.status === 'FAILED' && c.exitCode === 3 && c.errorReason === 'STEP_FAILED', `FAILED/STEP_FAILED/3 (got ${JSON.stringify(c)})`);
    assert(allLines(calls).includes('boom'), 'failure output still streamed');
  });
}

async function testMissingEnv() {
  console.log('missing required env -> exit 20, STEP_CONFIG_ERROR reported when URL + token exist');
  await withBackend({}, async ({ calls, port }) => {
    const env = baseEnv(port);
    delete env.CONDUCTOR_STEP_RUN;
    const r = await run(env);
    assert(r.code === 20, `exits 20 (got ${r.code})`);
    const c = calls.completes[0];
    assert(c && c.status === 'FAILED' && c.exitCode === 20 && c.errorReason === 'STEP_CONFIG_ERROR', `STEP_CONFIG_ERROR posted (got ${JSON.stringify(c)})`);
  });
  await withBackend({}, async ({ calls, port }) => {
    const env = baseEnv(port);
    delete env.CONDUCTOR_RUN_TOKEN;
    const r = await run(env);
    assert(r.code === 20, 'no token: exits 20');
    assert(calls.completes.length === 0, 'no token: step-complete not attempted');
  });
  await withBackend({}, async ({ calls, port }) => {
    const env = baseEnv(port);
    delete env.CONDUCTOR_STEP_COMPLETE_URL;
    const r = await run(env);
    assert(r.code === 20, 'no URL: exits 20');
    assert(calls.completes.length === 0, 'no URL: step-complete not attempted');
  });
  // Nothing at all (the exact "launched without env" case): must still exit 20, not crash.
  const r = await run({ PATH: process.env.PATH });
  assert(r.code === 20, `empty env: exits 20 (got ${r.code})`);
}

async function testTimeout() {
  console.log('timeout -> STEP_TIMEOUT, exit 124, child killed');
  await withBackend({}, async ({ calls, port }) => {
    const marker = path.join(tmpdir(), `step-selftest-${process.pid}`);
    const started = Date.now();
    // The grandchild writes a marker after 4s; a killed process tree never gets there.
    const r = await run(
      baseEnv(port, {
        CONDUCTOR_TIMEOUT_MINUTES: '0.02', // 1.2 s
        CONDUCTOR_STEP_RUN: `(sleep 4; touch ${marker}) & sleep 60`,
      }),
    );
    const elapsed = Date.now() - started;
    assert(r.code === 124, `exits 124 (got ${r.code})`);
    const c = calls.completes[0];
    assert(c && c.status === 'FAILED' && c.exitCode === 124 && c.errorReason === 'STEP_TIMEOUT', `FAILED/STEP_TIMEOUT/124 (got ${JSON.stringify(c)})`);
    assert(elapsed < 8000, `returned promptly, child did not run to completion (${elapsed} ms)`);
    await new Promise((res) => setTimeout(res, 3500));
    const { existsSync, rmSync } = await import('node:fs');
    assert(!existsSync(marker), 'grandchild was killed with the process group');
    rmSync(marker, { force: true });
  });
  console.log('timeout, child ignoring SIGTERM -> SIGKILL after the grace period');
  await withBackend({}, async ({ calls, port }) => {
    const r = await run(
      baseEnv(port, {
        CONDUCTOR_TIMEOUT_MINUTES: '0.02',
        CONDUCTOR_KILL_GRACE_MS: '500',
        CONDUCTOR_STEP_RUN: `trap '' TERM; while true; do sleep 1; done`,
      }),
    );
    assert(r.code === 124, `exits 124 (got ${r.code})`);
    assert(calls.completes[0]?.errorReason === 'STEP_TIMEOUT', 'STEP_TIMEOUT reported');
  });
}

async function testScrubbing() {
  console.log('secrets scrubbed from log lines');
  await withBackend({}, async ({ calls, port }) => {
    const r = await run(
      baseEnv(port, {
        CONDUCTOR_API_KEY: 'cond_api_key_value_0123456789',
        MY_SERVICE_PASSWORD: 'hunter2-hunter2-hunter2',
        SHORT_TOKEN: 'abc123',
        CONDUCTOR_STEP_RUN:
          'echo "token=$CONDUCTOR_RUN_TOKEN"; echo "key=$CONDUCTOR_API_KEY"; echo "pw=$MY_SERVICE_PASSWORD"; echo "short=$SHORT_TOKEN"',
      }),
    );
    assert(r.code === 0, 'exits 0');
    const text = allLines(calls).join('\n');
    assert(!text.includes('super-secret-run-token'), 'run token not in posted logs');
    assert(!text.includes('cond_api_key_value_0123456789'), 'API key not in posted logs');
    assert(!text.includes('hunter2-hunter2-hunter2'), 'long *PASSWORD* env value not in posted logs');
    assert(text.includes('token=[REDACTED]') && text.includes('key=[REDACTED]'), 'redaction marker present');
    assert(text.includes('short=abc123'), 'short (<=16 char) values are not treated as secrets');
    assert(!r.stdout.includes('super-secret-run-token'), 'run token not in the entrypoint own stdout');
  });
}

async function testRetry() {
  console.log('step-complete 5xx twice then 200 -> retried and succeeds');
  await withBackend({ completeStatuses: [503, 500] }, async ({ calls, port }) => {
    const r = await run(baseEnv(port));
    assert(r.code === 0, `exits 0 (got ${r.code})`);
    assert(calls.completes.length === 3, `three attempts (got ${calls.completes.length})`);
    assert(calls.completes.every((c) => c.status === 'SUCCESS'), 'same SUCCESS body each attempt');
  });
}

for (const t of [testSuccess, testNonZero, testMissingEnv, testTimeout, testScrubbing, testRetry]) {
  await t();
}

if (failures > 0) {
  console.error(`\n${failures} assertion(s) failed`);
  process.exit(1);
}
console.log('\nAll step-entrypoint self-tests passed');
