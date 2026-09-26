/* transport.test.mjs — createHttpTransport against a fake `/internal/v1/
 * creative-renders` backend (a real node:http server on an ephemeral port,
 * no mocking library). Verifies the HTTP shape (paths, method, auth header,
 * query params, body) independently of job/render.mjs's rendering core,
 * which never sees any of this directly — see transport.mjs's header
 * comment for why the two are split.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createHttpTransport } from '../job/transport.mjs';

async function readBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks);
}

function startFakeBackend(handler) {
  return new Promise((resolve) => {
    const server = createServer(async (req, res) => {
      try {
        await handler(req, res);
      } catch (err) {
        res.writeHead(500).end(String(err && err.message));
      }
    });
    server.listen(0, '127.0.0.1', () => resolve({ server, origin: `http://127.0.0.1:${server.address().port}` }));
  });
}

test('getSpec: GETs the spec path with the bearer token, returns the parsed JSON', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push({ method: req.method, url: req.url, auth: req.headers.authorization });
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ renderId: 'r1', placements: ['1x1'] }));
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 'tok123', renderId: 'r1' });
    const spec = await transport.getSpec();
    assert.deepEqual(spec, { renderId: 'r1', placements: ['1x1'] });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, 'GET');
    assert.equal(calls[0].url, '/internal/v1/creative-renders/r1/spec');
    assert.equal(calls[0].auth, 'Bearer tok123');
  } finally {
    server.close();
  }
});

test('getSpec: a non-2xx response throws with the status and body', async () => {
  const { server, origin } = await startFakeBackend(async (req, res) => {
    res.writeHead(404).end('render not found');
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'missing' });
    await assert.rejects(() => transport.getSpec(), /GET spec failed: 404/);
  } finally {
    server.close();
  }
});

test('putFrame: PUTs PNG bytes with width/height/index query params and image/png content-type', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    const body = await readBody(req);
    calls.push({ method: req.method, url: req.url, contentType: req.headers['content-type'], body });
    res.writeHead(204).end();
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'r1' });
    const png = Buffer.from([1, 2, 3, 4]);
    await transport.putFrame('9x16', { index: 2, width: 2160, height: 3840, png });
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, 'PUT');
    const url = new URL(calls[0].url, origin);
    assert.equal(url.pathname, '/internal/v1/creative-renders/r1/frames/9x16');
    assert.equal(url.searchParams.get('index'), '2');
    assert.equal(url.searchParams.get('width'), '2160');
    assert.equal(url.searchParams.get('height'), '3840');
    assert.equal(calls[0].contentType, 'image/png');
    assert.deepEqual([...calls[0].body], [1, 2, 3, 4]);
  } finally {
    server.close();
  }
});

test('putFrame: omits the index param for a non-sequence frame', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push(req.url);
    res.writeHead(204).end();
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'r1' });
    await transport.putFrame('4x5', { width: 2160, height: 2700, png: Buffer.from([0]) });
    const url = new URL(calls[0], origin);
    assert.equal(url.searchParams.has('index'), false);
  } finally {
    server.close();
  }
});

test('putFrame: a response other than 204 throws', async () => {
  const { server, origin } = await startFakeBackend(async (req, res) => {
    res.writeHead(500).end('boom');
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'r1' });
    await assert.rejects(
      () => transport.putFrame('1x1', { width: 1, height: 1, png: Buffer.from([0]) }),
      /PUT frame 1x1 failed: 500/
    );
  } finally {
    server.close();
  }
});

test('complete: POSTs warnings as JSON', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    const body = await readBody(req);
    calls.push({ url: req.url, method: req.method, json: JSON.parse(body.toString()) });
    res.writeHead(200).end();
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'r1' });
    await transport.complete([{ placementKey: '1x1', message: 'photo upscaled' }]);
    assert.equal(calls[0].method, 'POST');
    assert.equal(calls[0].url, '/internal/v1/creative-renders/r1/complete');
    assert.deepEqual(calls[0].json, { warnings: [{ placementKey: '1x1', message: 'photo upscaled' }] });
  } finally {
    server.close();
  }
});

test('complete: defaults warnings to an empty array', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push(JSON.parse((await readBody(req)).toString()));
    res.writeHead(200).end();
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'r1' });
    await transport.complete();
    assert.deepEqual(calls[0], { warnings: [] });
  } finally {
    server.close();
  }
});

test('fail: POSTs the message, and the log only when provided', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push({ url: req.url, json: JSON.parse((await readBody(req)).toString()) });
    res.writeHead(200).end();
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'r1' });
    await transport.fail('photo failed to load');
    assert.equal(calls[0].url, '/internal/v1/creative-renders/r1/fail');
    assert.deepEqual(calls[0].json, { message: 'photo failed to load' });

    await transport.fail('boom', 'stack trace here');
    assert.deepEqual(calls[1].json, { message: 'boom', log: 'stack trace here' });
  } finally {
    server.close();
  }
});

test('a slow backend times out rather than hanging forever', async () => {
  const { server, origin } = await startFakeBackend(async () => {
    // never responds
  });
  try {
    const transport = createHttpTransport({ apiUrl: origin, renderToken: 't', renderId: 'r1', timeoutMs: 50 });
    await assert.rejects(() => transport.getSpec(), /GET spec timed out after 50ms/);
  } finally {
    server.close();
  }
});
