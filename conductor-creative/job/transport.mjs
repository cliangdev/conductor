/* transport.mjs — the render job's ONLY knowledge of how it talks to the
 * backend. render.mjs's rendering core never touches `fetch` or a
 * `/api/v2/...` path directly; it calls the four methods below. Kept
 * separate because the delivery contract is expected to change (T3's
 * decision log: renders run LOCALLY through the Conductor CLI/MCP server,
 * not as a backend-launched Cloud Run Job) — when it changes again, only
 * this file and its own test change, not the rendering core or its tests.
 *
 * Today's contract (see the T3 render contract doc): the external v2
 * creatives API, `Authorization: Bearer <apiKey>` on every request (the CLI
 * sends the project/user API key — there is no separate render token).
 *
 *   POST {apiUrl}/api/v2/projects/{projectId}/marketing/creatives/{creativeId}/renders
 *     body: { previewOnly?, renderer?, workflowRunId? }
 *     -> 201 CreativeRenderResponse { id, state: "RUNNING", ..., spec: { renderId, previewOnly, creative, brand, placements } }
 *     `spec` is only ever returned by this call — getSpec() hands back `response.spec` and
 *     remembers `renderId` for the three calls below.
 *   PUT  {apiUrl}/api/v2/projects/{projectId}/marketing/creatives/{creativeId}/renders/{renderId}/frames/{placementKey}?index&width&height
 *     body: raw image bytes, Content-Type: image/jpeg (a placement frame) or image/png (the `sheet`
 *     contact sheet) -> 204
 *   POST {apiUrl}/api/v2/projects/{projectId}/marketing/creatives/{creativeId}/renders/{renderId}/complete
 *     body: { warnings: [{ placementKey, index?, message }] } -> 200 CreativeRenderResponse
 *   POST {apiUrl}/api/v2/projects/{projectId}/marketing/creatives/{creativeId}/renders/{renderId}/fail
 *     body: { message, log? } -> 200 CreativeRenderResponse
 */

const DEFAULT_TIMEOUT_MS = 20_000;
// A frame is a few hundred KB to a few MB; through a scale-to-zero backend (a cold start, a slow uplink)
// that can outlast the JSON calls' limit, so uploads get their own, and one retry — the PUT is an upsert.
const DEFAULT_UPLOAD_TIMEOUT_MS = 90_000;
const UPLOAD_ATTEMPTS = 2;

async function withTimeout(fn, ms, label) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(), ms);
  try {
    return await fn(ctrl.signal);
  } catch (err) {
    if (err && err.name === 'AbortError') throw new Error(`${label} timed out after ${ms}ms`);
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

async function safeText(res) {
  try {
    return await res.text();
  } catch {
    return '';
  }
}

/** Builds the transport the real job uses, talking to the Conductor backend over HTTP per today's
 * external v2 `/marketing/creatives/{creativeId}/renders` contract. `timeoutMs` bounds every
 * individual network call, so a hung backend fails the job instead of hanging the caller (a Claude
 * Code/Desktop session, or a self-hosted Workflow daemon) forever.
 *
 * `renderId` is not known until `getSpec()` returns (the backend assigns it on the POST that starts
 * the render), so `putFrame`/`complete`/`fail` all reach for it lazily via `renderBase()` rather than
 * capturing it at construction time. `getRenderId()` is an addition beyond the four-method adapter
 * interface `render.mjs`'s `run()` relies on — callers that need the id for their own reporting (the
 * CLI, the MCP tool) can read it after `getSpec()` without `run()` having to hand it back itself. */
export function createApiTransport({
  apiUrl,
  apiKey,
  projectId,
  creativeId,
  previewOnly,
  renderer,
  workflowRunId,
  timeoutMs = DEFAULT_TIMEOUT_MS,
  uploadTimeoutMs = DEFAULT_UPLOAD_TIMEOUT_MS,
  fetchImpl = fetch,
}) {
  const creativeBase = `${apiUrl.replace(/\/+$/, '')}/api/v2/projects/${projectId}/marketing/creatives/${creativeId}`;
  const authHeaders = { Authorization: `Bearer ${apiKey}` };
  let renderId;

  function renderBase() {
    if (!renderId) throw new Error('renderId is not known yet — getSpec() must succeed first');
    return `${creativeBase}/renders/${renderId}`;
  }

  async function postJson(url, body) {
    return withTimeout(async (signal) => {
      const res = await fetchImpl(url, {
        method: 'POST',
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal,
      });
      if (!res.ok) throw new Error(`POST ${url} failed: ${res.status} ${await safeText(res)}`);
      if (res.status === 204) return undefined;
      try {
        return await res.json();
      } catch {
        return undefined;
      }
    }, timeoutMs, `POST ${url}`);
  }

  return {
    async getSpec() {
      const body = { previewOnly: !!previewOnly };
      if (renderer) body.renderer = renderer;
      if (workflowRunId) body.workflowRunId = workflowRunId;
      const response = await withTimeout(async (signal) => {
        const res = await fetchImpl(`${creativeBase}/renders`, {
          method: 'POST',
          headers: { ...authHeaders, 'Content-Type': 'application/json' },
          body: JSON.stringify(body),
          signal,
        });
        if (!res.ok) throw new Error(`POST renders failed: ${res.status} ${await safeText(res)}`);
        return res.json();
      }, timeoutMs, 'POST renders');

      const spec = response && response.spec;
      const id = (spec && spec.renderId) || (response && response.id);
      if (!spec || !id) throw new Error('POST renders response is missing `spec`/a render id');
      renderId = id;
      return spec;
    },

    async putFrame(placementKey, { index, width, height, bytes, contentType = 'image/jpeg' }) {
      const qs = new URLSearchParams({ width: String(width), height: String(height) });
      if (index !== undefined && index !== null) qs.set('index', String(index));
      const base = renderBase();
      const put = () => withTimeout(async (signal) => {
        const res = await fetchImpl(`${base}/frames/${encodeURIComponent(placementKey)}?${qs}`, {
          method: 'PUT',
          headers: { ...authHeaders, 'Content-Type': contentType },
          body: bytes,
          signal,
        });
        if (res.status !== 204) {
          const err = new Error(`PUT frame ${placementKey} failed: ${res.status} ${await safeText(res)}`);
          err.retryable = res.status >= 500;
          throw err;
        }
      }, uploadTimeoutMs, `PUT frame ${placementKey}`);
      for (let attempt = 1; ; attempt++) {
        try {
          return await put();
        } catch (err) {
          const retryable = err.retryable || /timed out/.test(err.message);
          if (!retryable || attempt >= UPLOAD_ATTEMPTS) throw err;
        }
      }
    },

    async complete(warnings) {
      return postJson(`${renderBase()}/complete`, { warnings: warnings || [] });
    },

    async fail(message, log) {
      return postJson(`${renderBase()}/fail`, log ? { message, log } : { message });
    },

    /** The render id assigned by `getSpec()`'s POST, or `undefined` before it has run. Not part of the
     * `run()` adapter interface — a convenience for callers that report the id themselves. */
    getRenderId() {
      return renderId;
    },
  };
}
