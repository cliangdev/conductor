/* transport.test.mjs — createApiTransport against a fake external v2
 * `/marketing/creatives/{creativeId}/renders` backend (a real node:http
 * server on an ephemeral port, no mocking library). Verifies the HTTP shape
 * (paths, method, auth header, query params, body) independently of
 * job/render.mjs's rendering core, which never sees any of this directly —
 * see transport.mjs's header comment for why the two are split.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { createApiTransport } from '../job/transport.mjs';

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

const SPEC = { renderId: 'r1', previewOnly: false, creative: { headline: 'h' }, brand: {}, placements: ['1x1'] };

test('getSpec: POSTs the renders path with the bearer key, previewOnly/renderer/workflowRunId, and returns `spec`', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push({ method: req.method, url: req.url, auth: req.headers.authorization, json: JSON.parse((await readBody(req)).toString()) });
    res.writeHead(201, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ id: 'r1', state: 'RUNNING', spec: SPEC }));
  });
  try {
    const transport = createApiTransport({
      apiUrl: origin,
      apiKey: 'key123',
      projectId: 'proj1',
      creativeId: 'cr1',
      previewOnly: true,
      renderer: 'cli',
      workflowRunId: 'run1',
    });
    const spec = await transport.getSpec();
    assert.deepEqual(spec, SPEC);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].method, 'POST');
    assert.equal(calls[0].url, '/api/v2/projects/proj1/marketing/creatives/cr1/renders');
    assert.equal(calls[0].auth, 'Bearer key123');
    assert.deepEqual(calls[0].json, { previewOnly: true, renderer: 'cli', workflowRunId: 'run1' });
    assert.equal(transport.getRenderId(), 'r1');
  } finally {
    server.close();
  }
});

test('getSpec: omits renderer/workflowRunId when not given, defaults previewOnly to false', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push(JSON.parse((await readBody(req)).toString()));
    res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await transport.getSpec();
    assert.deepEqual(calls[0], { previewOnly: false });
  } finally {
    server.close();
  }
});

test('getSpec: a non-2xx response throws with the status and body', async () => {
  const { server, origin } = await startFakeBackend(async (req, res) => {
    res.writeHead(422).end('the creative has no photo');
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await assert.rejects(() => transport.getSpec(), /POST renders failed: 422/);
  } finally {
    server.close();
  }
});

test('getSpec: a response with no `spec` throws rather than silently proceeding', async () => {
  const { server, origin } = await startFakeBackend(async (req, res) => {
    res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', state: 'RUNNING' }));
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await assert.rejects(() => transport.getSpec(), /missing `spec`/);
  } finally {
    server.close();
  }
});

test('putFrame: PUTs a placement frame\'s JPEG bytes to the render-scoped path with width/height/index query params', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    const body = await readBody(req);
    calls.push({ method: req.method, url: req.url, contentType: req.headers['content-type'], auth: req.headers.authorization, body });
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    res.writeHead(204).end();
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'proj1', creativeId: 'cr1' });
    await transport.getSpec();
    const bytes = Buffer.from([1, 2, 3, 4]);
    await transport.putFrame('9x16', { index: 2, width: 2160, height: 3840, bytes, contentType: 'image/jpeg' });

    const put = calls.find((c) => c.method === 'PUT');
    const url = new URL(put.url, origin);
    assert.equal(url.pathname, '/api/v2/projects/proj1/marketing/creatives/cr1/renders/r1/frames/9x16');
    assert.equal(url.searchParams.get('index'), '2');
    assert.equal(url.searchParams.get('width'), '2160');
    assert.equal(url.searchParams.get('height'), '3840');
    assert.equal(put.contentType, 'image/jpeg');
    assert.equal(put.auth, 'Bearer k');
    assert.deepEqual([...put.body], [1, 2, 3, 4]);
  } finally {
    server.close();
  }
});

test('putFrame: PUTs the "sheet" contact sheet as PNG', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push({ method: req.method, url: req.url, contentType: req.headers['content-type'] });
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    res.writeHead(204).end();
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await transport.getSpec();
    await transport.putFrame('sheet', { width: 1000, height: 1000, bytes: Buffer.from([0]), contentType: 'image/png' });
    const put = calls.find((c) => c.method === 'PUT');
    assert.equal(put.contentType, 'image/png');
  } finally {
    server.close();
  }
});

test('putFrame: defaults to image/jpeg when no contentType is given', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    calls.push({ method: req.method, contentType: req.headers['content-type'] });
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    res.writeHead(204).end();
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await transport.getSpec();
    await transport.putFrame('1x1', { width: 1, height: 1, bytes: Buffer.from([0]) });
    assert.equal(calls.find((c) => c.method === 'PUT').contentType, 'image/jpeg');
  } finally {
    server.close();
  }
});

test('putFrame: omits the index param for a non-sequence frame', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    calls.push(req.url);
    res.writeHead(204).end();
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await transport.getSpec();
    await transport.putFrame('4x5', { width: 2160, height: 2700, bytes: Buffer.from([0]) });
    const url = new URL(calls[0], origin);
    assert.equal(url.searchParams.has('index'), false);
  } finally {
    server.close();
  }
});

test('putFrame: before getSpec() has run, throws rather than hitting an unknown render id', async () => {
  const transport = createApiTransport({ apiUrl: 'http://127.0.0.1:1', apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
  await assert.rejects(
    () => transport.putFrame('1x1', { width: 1, height: 1, bytes: Buffer.from([0]) }),
    /renderId is not known yet/
  );
});

test('putFrame: a response other than 204 throws', async () => {
  const { server, origin } = await startFakeBackend(async (req, res) => {
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    res.writeHead(500).end('boom');
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await transport.getSpec();
    await assert.rejects(
      () => transport.putFrame('1x1', { width: 1, height: 1, bytes: Buffer.from([0]) }),
      /PUT frame 1x1 failed: 500/
    );
  } finally {
    server.close();
  }
});

test('complete: POSTs warnings as JSON to the render-scoped complete path', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    const body = await readBody(req);
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    calls.push({ url: req.url, method: req.method, json: JSON.parse(body.toString()) });
    res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', state: 'SUCCEEDED' }));
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'proj1', creativeId: 'cr1' });
    await transport.getSpec();
    await transport.complete([{ placementKey: '1x1', message: 'photo upscaled' }]);
    assert.equal(calls[0].method, 'POST');
    assert.equal(calls[0].url, '/api/v2/projects/proj1/marketing/creatives/cr1/renders/r1/complete');
    assert.deepEqual(calls[0].json, { warnings: [{ placementKey: '1x1', message: 'photo upscaled' }] });
  } finally {
    server.close();
  }
});

test('complete: defaults warnings to an empty array', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    calls.push(JSON.parse((await readBody(req)).toString()));
    res.writeHead(200).end();
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await transport.getSpec();
    await transport.complete();
    assert.deepEqual(calls[0], { warnings: [] });
  } finally {
    server.close();
  }
});

test('fail: POSTs the message, and the log only when provided, to the render-scoped fail path', async () => {
  const calls = [];
  const { server, origin } = await startFakeBackend(async (req, res) => {
    if (req.url.endsWith('/renders')) {
      res.writeHead(201, { 'content-type': 'application/json' }).end(JSON.stringify({ id: 'r1', spec: SPEC }));
      return;
    }
    calls.push({ url: req.url, json: JSON.parse((await readBody(req)).toString()) });
    res.writeHead(200).end();
  });
  try {
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1' });
    await transport.getSpec();
    await transport.fail('photo failed to load');
    assert.equal(calls[0].url, '/api/v2/projects/p1/marketing/creatives/c1/renders/r1/fail');
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
    const transport = createApiTransport({ apiUrl: origin, apiKey: 'k', projectId: 'p1', creativeId: 'c1', timeoutMs: 50 });
    await assert.rejects(() => transport.getSpec(), /POST renders timed out after 50ms/);
  } finally {
    server.close();
  }
});
