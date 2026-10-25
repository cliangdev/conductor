#!/usr/bin/env node
// Self-test for runner-protocol.mjs (the helpers shared by both entrypoints). Zero dependencies.
import { createServer } from 'node:http';
import { armTimeout, buildSecrets, createLineSplitter, createLogBatcher, postJson, scrub } from './runner-protocol.mjs';

let failures = 0;
function assert(cond, message) {
  if (!cond) {
    failures++;
    console.error(`  FAIL: ${message}`);
  } else {
    console.log(`  ok: ${message}`);
  }
}

// buildSecrets / scrub
const env = { CONDUCTOR_RUN_TOKEN: 'tok', OTHER_API_KEY: 'x'.repeat(20), SHORT_KEY: 'abc', PLAIN: 'y'.repeat(30) };
assert(buildSecrets(env, { names: ['CONDUCTOR_RUN_TOKEN', 'MISSING'] }).join() === 'tok', 'names only: set names, no pattern');
const withPattern = buildSecrets(env, { names: ['CONDUCTOR_RUN_TOKEN'], pattern: true });
assert(withPattern[0] === 'x'.repeat(20) && withPattern.includes('tok') && withPattern.length === 2, 'pattern adds long KEY/TOKEN values, longest first, skips short and unmatched');
assert(scrub('a tok b', ['tok']) === 'a [REDACTED] b', 'scrub redacts');

// line splitter
const got = [];
const splitter = createLineSplitter((l) => got.push(l));
splitter.write(Buffer.from('one\n\ntw'));
splitter.write(Buffer.from('o\nthree'));
splitter.end();
assert(got.join('|') === 'one|two|three', 'line splitter keeps partial lines, drops blanks, flushes tail');

// postJson retry + log batcher
let hits = 0;
const bodies = [];
const server = createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    hits++;
    bodies.push({ auth: req.headers.authorization, body: JSON.parse(Buffer.concat(chunks).toString()) });
    res.writeHead(hits === 1 ? 503 : 200).end();
  });
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const url = `http://127.0.0.1:${server.address().port}/x`;
assert((await postJson(url, 'T', { a: 1 }, { retries: 1, log: () => {} })) === true && hits === 2, 'postJson retries a 5xx then succeeds');
assert(bodies[1].auth === 'Bearer T', 'postJson sends the bearer token');

const batcher = createLogBatcher({ url, token: 'T', workerJobId: 'w1', secrets: ['sekret'], log: () => {}, initialLines: ['first'] });
batcher.push('has sekret inside');
batcher.stop();
await batcher.flush();
const last = bodies[bodies.length - 1].body;
assert(last.workerJobId === 'w1' && last.lines.join('|') === 'first|has [REDACTED] inside', 'log batcher posts initial + pushed lines scrubbed');
server.close();

// armTimeout: SIGTERM then SIGKILL after grace; clear() cancels
const signals = [];
const t = armTimeout({ minutes: 0.0005, graceMs: 20, kill: (s) => signals.push(s), onTimeout: () => signals.push('cb') });
await new Promise((r) => setTimeout(r, 150));
assert(signals.join() === 'cb,SIGTERM,SIGKILL', 'armTimeout fires callback, SIGTERM, then SIGKILL after grace');
t.clear();
const none = [];
const t2 = armTimeout({ minutes: 0.0005, graceMs: 20, kill: (s) => none.push(s) });
t2.clear();
await new Promise((r) => setTimeout(r, 100));
assert(none.length === 0, 'clear() cancels the timers');

if (failures > 0) {
  console.error(`${failures} assertion(s) failed`);
  process.exit(1);
}
console.log('runner-protocol selftest passed');
