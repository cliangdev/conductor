#!/usr/bin/env node
// Self-test for conductor-claude-entrypoint.mjs. Zero dependencies (Node built-ins only), run
// directly via `node entrypoint.selftest.mjs`. Spawns the entrypoint as a real child process
// against a fake `claude` CLI stub (put first on PATH) and a local HTTP server standing in for
// the Conductor backend's log-chunk / step-complete callbacks, then asserts on what got posted.
// Wired into .github/workflows/publish-runner.yml as a lint-time check.

import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { mkdtempSync, writeFileSync, chmodSync, rmSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ENTRYPOINT = path.join(__dirname, 'conductor-claude-entrypoint.mjs');

let failures = 0;
function assert(cond, message) {
  if (!cond) {
    failures++;
    console.error(`  FAIL: ${message}`);
  } else {
    console.log(`  ok: ${message}`);
  }
}

/** Starts a fake Conductor backend capturing log-chunk and step-complete POST bodies. */
function startFakeBackend() {
  const calls = { logChunks: [], stepComplete: null, authHeaders: [] };
  const server = createServer((req, res) => {
    let body = '';
    req.on('data', (chunk) => (body += chunk));
    req.on('end', () => {
      calls.authHeaders.push(req.headers.authorization);
      const parsed = body ? JSON.parse(body) : {};
      if (req.url === '/log-chunk') {
        calls.logChunks.push(parsed);
      } else if (req.url === '/step-complete') {
        calls.stepComplete = parsed;
      }
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end('{}');
    });
  });
  return new Promise((resolve) => {
    server.listen(0, '127.0.0.1', () => resolve({ server, calls, port: server.address().port }));
  });
}

/** Writes an executable fake `claude` CLI stub that prints canned stream-json to stdout. */
function writeFakeClaude(binDir, script) {
  const p = path.join(binDir, 'claude');
  writeFileSync(p, `#!/usr/bin/env node\n${script}\n`);
  chmodSync(p, 0o755);
}

const runnerRoots = [];

function baseEnv({ binDir, port, extra = {} }) {
  const runnerRoot = mkdtempSync(path.join(tmpdir(), 'conductor-root-'));
  runnerRoots.push(runnerRoot);
  return {
    PATH: `${binDir}:${process.env.PATH}`,
    CONDUCTOR_RUNNER_ROOT: runnerRoot,
    CONDUCTOR_STEP_PROMPT: 'do the thing',
    CONDUCTOR_STEP_INPUTS_JSON: JSON.stringify({ 'a.json': '{"x":1}' }),
    CONDUCTOR_MCP_ENABLED: 'false',
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

function runEntrypoint(env) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, [ENTRYPOINT], { env, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '';
    child.stdout.on('data', (d) => (stdout += d));
    child.on('close', (code) => resolve({ code, stdout }));
  });
}

async function testSuccessWithStructuredOutput() {
  console.log('test: success run with structured output');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `console.log(JSON.stringify({type:'system',subtype:'init'}));
console.log(JSON.stringify({type:'result',subtype:'success',is_error:false,result:'{"summary":"done"}',structured_output:{summary:'done'},num_turns:2,session_id:'sess_1'}));
process.exit(0);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(calls.stepComplete?.status === 'SUCCESS', 'step-complete status is SUCCESS');
  assert(calls.stepComplete?.exitCode === 0, 'step-complete exitCode is 0');
  assert(calls.stepComplete?.outputs?.data === '{"summary":"done"}', 'outputs.data carries structured JSON');
  assert(calls.stepComplete?.outputs?.summary === 'done', 'outputs.summary is flattened from structured output');
  assert(calls.authHeaders.every((h) => h === 'Bearer super-secret-run-token'), 'all callbacks used the run token bearer auth');
}

async function testAuthError() {
  console.log('test: auth error maps to exit 11 / CLAUDE_AUTH_ERROR');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `console.log(JSON.stringify({type:'result',subtype:'success',is_error:true,api_error_status:401,result:'Invalid API key · Fix external API key'}));
process.exit(1);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 11, `exit code is 11 (got ${code})`);
  assert(calls.stepComplete?.status === 'FAILED', 'step-complete status is FAILED');
  assert(calls.stepComplete?.errorReason === 'CLAUDE_AUTH_ERROR', 'errorReason is CLAUDE_AUTH_ERROR');
}

async function testRateLimit() {
  console.log('test: rate-limit subtype maps to exit 12 / CLAUDE_RATE_LIMITED');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `console.log(JSON.stringify({type:'result',subtype:'error_usage_limit',is_error:true,result:'Usage limit reached'}));
process.exit(1);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 12, `exit code is 12 (got ${code})`);
  assert(calls.stepComplete?.errorReason === 'CLAUDE_RATE_LIMITED', 'errorReason is CLAUDE_RATE_LIMITED');
}

async function testTimeout() {
  console.log('test: hard timeout kills child and maps to exit 13 / CLAUDE_TIMEOUT');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  // Never emits a result event; sleeps far longer than the configured timeout.
  writeFakeClaude(binDir, `setInterval(() => {}, 1000);`);
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port, extra: { CONDUCTOR_TIMEOUT_MINUTES: (2 / 60).toFixed(4) } }); // ~2s
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 13, `exit code is 13 (got ${code})`);
  assert(calls.stepComplete?.errorReason === 'CLAUDE_TIMEOUT', 'errorReason is CLAUDE_TIMEOUT');
}

async function testMissingRequiredEnv() {
  console.log('test: missing required env maps to exit 20 / CLAUDE_CONFIG_ERROR');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(binDir, `process.exit(0);`);
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port });
  delete env.CONDUCTOR_STEP_PROMPT;
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 20, `exit code is 20 (got ${code})`);
  assert(calls.stepComplete?.errorReason === 'CLAUDE_CONFIG_ERROR', 'errorReason is CLAUDE_CONFIG_ERROR');
}

async function testAuthHygieneAndSecretScrubbing() {
  console.log('test: OAuth present deletes ANTHROPIC_API_KEY from child env, and log lines are scrubbed');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `console.log('ANTHROPIC_API_KEY present: ' + Boolean(process.env.ANTHROPIC_API_KEY));
console.log('leaking token value: SUPER-SECRET-OAUTH-abc123');
console.log(JSON.stringify({type:'result',subtype:'success',is_error:false,result:'OK'}));
process.exit(0);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({
    binDir,
    port,
    extra: { ANTHROPIC_API_KEY: 'should-be-deleted', CLAUDE_CODE_OAUTH_TOKEN: 'SUPER-SECRET-OAUTH-abc123' },
  });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  const allLoggedLines = calls.logChunks.flatMap((c) => c.lines || []);
  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(
    allLoggedLines.some((l) => l.includes('ANTHROPIC_API_KEY present: false')),
    'ANTHROPIC_API_KEY was deleted from the child env when OAuth token is set',
  );
  assert(
    !allLoggedLines.some((l) => l.includes('SUPER-SECRET-OAUTH-abc123')),
    'OAuth token value never appears raw in posted log lines',
  );
  assert(
    allLoggedLines.some((l) => l.includes('[REDACTED]')),
    'the scrubbed line shows a [REDACTED] marker in its place',
  );
}

async function testUnsafeInputFilenameRejected() {
  console.log('test: path-escaping input filename is rejected as a config error');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(binDir, `process.exit(0);`);
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({
    binDir,
    port,
    extra: { CONDUCTOR_STEP_INPUTS_JSON: JSON.stringify({ '../escape.txt': 'x' }) },
  });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 20, `exit code is 20 (got ${code})`);
  assert(calls.stepComplete?.errorReason === 'CLAUDE_CONFIG_ERROR', 'errorReason is CLAUDE_CONFIG_ERROR');
}

async function testMcpConfigWritten() {
  console.log('test: MCP enabled writes mcp-config.json and passes --mcp-config to claude');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `const args = process.argv.slice(2);
console.log('saw-mcp-config-flag: ' + args.includes('--mcp-config'));
console.log(JSON.stringify({type:'result',subtype:'success',is_error:false,result:'OK'}));
process.exit(0);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port, extra: { CONDUCTOR_MCP_ENABLED: 'true', CONDUCTOR_API_KEY: 'proj-api-key' } });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  const allLoggedLines = calls.logChunks.flatMap((c) => c.lines || []);
  // Read the config directly off disk rather than through the fake CLI's stdout — the entrypoint
  // correctly redacts CONDUCTOR_API_KEY (a secret) out of any log line that echoes it.
  const configPath = path.join(env.CONDUCTOR_RUNNER_ROOT, 'mcp-config.json');
  const config = JSON.parse(readFileSync(configPath, 'utf8'));

  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(allLoggedLines.includes('saw-mcp-config-flag: true'), '--mcp-config was passed to claude');
  assert(config?.mcpServers?.conductor?.env?.CONDUCTOR_API_KEY === 'proj-api-key', 'mcp-config.json carries the project API key');
  assert(config?.mcpServers?.conductor?.args?.includes('@cliangdev/conductor'), 'mcp-config.json points at the @cliangdev/conductor MCP server');
  assert(
    !allLoggedLines.some((l) => l.includes('proj-api-key')),
    'the API key value never appears raw in posted log lines (redacted like other secrets)',
  );
}

/** Serializes a stream-json event to a `console.log(JSON.stringify(...))` source line for a fake
 * claude stub, without manual string-escaping — safe for arbitrary embedded values. */
function logLine(event) {
  return `console.log(${JSON.stringify(JSON.stringify(event))});`;
}

async function testStreamJsonTranslation() {
  console.log('test: stream-json events are translated to compact human-readable log lines');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  const longArg = 'x'.repeat(150);
  const longText = 'y'.repeat(200);
  const script = [
    `console.log('not valid json at all, just a raw line');`,
    logLine({ type: 'system', subtype: 'init', model: 'claude-opus-4-8', session_id: 'abcdefgh-1234-5678-9999-000000000000' }),
    logLine({
      type: 'assistant',
      message: { role: 'assistant', content: [{ type: 'tool_use', id: 't1', name: 'Bash', input: { command: `echo super-secret-run-token and also ${longArg}` } }] },
    }),
    logLine({ type: 'assistant', message: { role: 'assistant', content: [{ type: 'text', text: longText }] } }),
    logLine({ type: 'result', subtype: 'success', is_error: false, result: 'OK', num_turns: 3, session_id: 'sess' }),
    `process.exit(0);`,
  ].join('\n');
  writeFakeClaude(binDir, script);
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  const allLoggedLines = calls.logChunks.flatMap((c) => c.lines || []);

  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(calls.stepComplete?.status === 'SUCCESS', 'result event is still classified as SUCCESS for the exit taxonomy');
  assert(
    allLoggedLines.some((l) => l === 'not valid json at all, just a raw line'),
    'malformed/non-JSON stdout line passes through raw, unchanged',
  );
  assert(
    allLoggedLines.some((l) => l.startsWith('→ claude session started') && l.includes('claude-opus-4-8') && l.includes('abcdefgh')),
    'init event is translated to a session-started line',
  );
  assert(
    allLoggedLines.some((l) => l.startsWith('→ tool: Bash {command:')),
    'assistant tool_use block is translated to a tool line',
  );
  assert(
    !allLoggedLines.some((l) => l.includes(longArg)),
    'long tool arg value is truncated, not emitted in full',
  );
  assert(
    !allLoggedLines.some((l) => l.includes('super-secret-run-token')),
    'run token embedded in tool args is scrubbed, never emitted raw',
  );
  assert(
    allLoggedLines.some((l) => l.startsWith('→ tool: Bash') && l.includes('[REDACTED]')),
    'the scrubbed tool line shows a [REDACTED] marker in place of the secret',
  );
  assert(
    allLoggedLines.some((l) => l.startsWith('💬 ') && !l.includes(longText)),
    'assistant text block is translated to a 💬 line, truncated',
  );
  assert(
    allLoggedLines.some((l) => l === '✓ done: 3 turns'),
    'result event is translated to a done line',
  );
  assert(
    allLoggedLines.some((l) => l === '→ container started, launching claude'),
    'an early "container started" line is emitted before any claude output',
  );
}

async function testWorkerJobIdInLogChunkBody() {
  console.log('test: log-chunk body carries workerJobId extracted from the step-complete URL path');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `console.log(JSON.stringify({type:'result',subtype:'success',is_error:false,result:'OK'}));
process.exit(0);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({
    binDir,
    port,
    extra: { CONDUCTOR_STEP_COMPLETE_URL: `http://127.0.0.1:${port}/workflow-runs/run_1/steps/wjob_abc123/complete` },
  });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(
    calls.logChunks.length > 0 && calls.logChunks.every((c) => c.workerJobId === 'wjob_abc123'),
    'every log-chunk POST body carries workerJobId extracted from the .../steps/{id}/complete URL',
  );
}

async function testWorkerJobIdOmittedOnMalformedUrl() {
  console.log('test: log-chunk body omits workerJobId when the step-complete URL has no /steps/{id}/ segment');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `console.log(JSON.stringify({type:'result',subtype:'success',is_error:false,result:'OK'}));
process.exit(0);`,
  );
  const { server, calls, port } = await startFakeBackend();
  // baseEnv's default CONDUCTOR_STEP_COMPLETE_URL (".../step-complete") has no /steps/{id}/
  // segment — spelled out here anyway so the test documents its own premise.
  const env = baseEnv({ binDir, port, extra: { CONDUCTOR_STEP_COMPLETE_URL: `http://127.0.0.1:${port}/step-complete` } });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(
    calls.logChunks.length > 0 && calls.logChunks.every((c) => !('workerJobId' in c)),
    'log-chunk body has no workerJobId key when the URL does not match the expected shape',
  );
}

async function testAllowedToolsMergesInputsReadWithUserList() {
  console.log('test: --allowedTools appends Read(/conductor/inputs/**) to a caller-supplied list');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `const args = process.argv.slice(2);
const idx = args.indexOf('--allowedTools');
console.log('allowedTools: ' + (idx >= 0 ? args[idx + 1] : '<missing>'));
console.log(JSON.stringify({type:'result',subtype:'success',is_error:false,result:'OK'}));
process.exit(0);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port, extra: { CONDUCTOR_ALLOWED_TOOLS: 'Bash(echo:*)' } });
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  const allLoggedLines = calls.logChunks.flatMap((c) => c.lines || []);
  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(
    allLoggedLines.some((l) => l === 'allowedTools: Bash(echo:*),Read(/conductor/inputs/**)'),
    'caller allowedTools is preserved and Read(/conductor/inputs/**) is appended',
  );
}

async function testAllowedToolsDefaultsToInputsReadWithoutUserList() {
  console.log('test: --allowedTools is always passed, defaulting to Read(/conductor/inputs/**) alone');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(
    binDir,
    `const args = process.argv.slice(2);
const idx = args.indexOf('--allowedTools');
console.log('allowedTools: ' + (idx >= 0 ? args[idx + 1] : '<missing>'));
console.log(JSON.stringify({type:'result',subtype:'success',is_error:false,result:'OK'}));
process.exit(0);`,
  );
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port });
  delete env.CONDUCTOR_ALLOWED_TOOLS;
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  const allLoggedLines = calls.logChunks.flatMap((c) => c.lines || []);
  assert(code === 0, `exit code is 0 (got ${code})`);
  assert(
    allLoggedLines.some((l) => l === 'allowedTools: Read(/conductor/inputs/**)'),
    '--allowedTools defaults to just Read(/conductor/inputs/**) when the caller sets none',
  );
}

async function testMcpEnabledWithoutApiKeyFails() {
  console.log('test: MCP enabled without CONDUCTOR_API_KEY is a config error');
  const binDir = mkdtempSync(path.join(tmpdir(), 'claude-bin-'));
  writeFakeClaude(binDir, `process.exit(0);`);
  const { server, calls, port } = await startFakeBackend();
  const env = baseEnv({ binDir, port, extra: { CONDUCTOR_MCP_ENABLED: 'true' } });
  delete env.CONDUCTOR_API_KEY;
  const { code } = await runEntrypoint(env);
  server.close();
  rmSync(binDir, { recursive: true, force: true });

  assert(code === 20, `exit code is 20 (got ${code})`);
  assert(calls.stepComplete?.errorReason === 'CLAUDE_CONFIG_ERROR', 'errorReason is CLAUDE_CONFIG_ERROR');
}

async function main() {
  await testSuccessWithStructuredOutput();
  await testAuthError();
  await testRateLimit();
  await testTimeout();
  await testMissingRequiredEnv();
  await testAuthHygieneAndSecretScrubbing();
  await testUnsafeInputFilenameRejected();
  await testMcpConfigWritten();
  await testMcpEnabledWithoutApiKeyFails();
  await testStreamJsonTranslation();
  await testWorkerJobIdInLogChunkBody();
  await testWorkerJobIdOmittedOnMalformedUrl();
  await testAllowedToolsMergesInputsReadWithUserList();
  await testAllowedToolsDefaultsToInputsReadWithoutUserList();

  for (const root of runnerRoots) rmSync(root, { recursive: true, force: true });

  console.log('');
  if (failures > 0) {
    console.error(`${failures} assertion(s) failed`);
    process.exit(1);
  }
  console.log('all self-test assertions passed');
}

main();
