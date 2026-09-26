/* transport.mjs — the render job's ONLY knowledge of how it talks to the
 * backend. render.mjs's rendering core never touches `fetch` or an
 * `/internal/v1/...` path directly; it calls the four methods below. Kept
 * separate because the delivery contract is expected to change (the backend
 * side of T3 was originally a direct Cloud Run Job launch talking straight
 * REST to `/internal/v1/creative-renders/*`; it may move to sit behind a
 * Conductor Workflow instead) — when that happens, only this file and its
 * own test change, not the rendering core or its tests.
 *
 * Today's contract (all requests carry `Authorization: Bearer <renderToken>`):
 *   GET  {apiUrl}/internal/v1/creative-renders/{renderId}/spec
 *     -> { renderId, previewOnly, creative, brand, placements }
 *   PUT  {apiUrl}/internal/v1/creative-renders/{renderId}/frames/{placementKey}?index&width&height
 *     body: PNG bytes, Content-Type: image/png -> 204
 *   POST {apiUrl}/internal/v1/creative-renders/{renderId}/complete
 *     body: { warnings: [{ placementKey, index?, message }] }
 *   POST {apiUrl}/internal/v1/creative-renders/{renderId}/fail
 *     body: { message, log? }
 */

const DEFAULT_TIMEOUT_MS = 20_000;

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

/** Builds the transport the real job uses, talking to the Conductor backend
 * over HTTP per today's `/internal/v1/creative-renders` contract.
 * `timeoutMs` bounds every individual network call (per the T3 requirement
 * that every network step has its own timeout, so a hung backend fails the
 * job instead of hanging the Cloud Run Job / worker forever). */
export function createHttpTransport({ apiUrl, renderToken, renderId, timeoutMs = DEFAULT_TIMEOUT_MS, fetchImpl = fetch }) {
  const base = `${apiUrl.replace(/\/+$/, '')}/internal/v1/creative-renders/${renderId}`;
  const authHeaders = { Authorization: `Bearer ${renderToken}` };

  async function postJson(path, body) {
    return withTimeout(async (signal) => {
      const res = await fetchImpl(`${base}/${path}`, {
        method: 'POST',
        headers: { ...authHeaders, 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal,
      });
      if (!res.ok) throw new Error(`POST ${path} failed: ${res.status} ${await safeText(res)}`);
    }, timeoutMs, `POST ${path}`);
  }

  return {
    async getSpec() {
      return withTimeout(async (signal) => {
        const res = await fetchImpl(`${base}/spec`, { headers: authHeaders, signal });
        if (!res.ok) throw new Error(`GET spec failed: ${res.status} ${await safeText(res)}`);
        return res.json();
      }, timeoutMs, 'GET spec');
    },

    async putFrame(placementKey, { index, width, height, png }) {
      const qs = new URLSearchParams({ width: String(width), height: String(height) });
      if (index !== undefined && index !== null) qs.set('index', String(index));
      return withTimeout(async (signal) => {
        const res = await fetchImpl(`${base}/frames/${encodeURIComponent(placementKey)}?${qs}`, {
          method: 'PUT',
          headers: { ...authHeaders, 'Content-Type': 'image/png' },
          body: png,
          signal,
        });
        if (res.status !== 204) throw new Error(`PUT frame ${placementKey} failed: ${res.status} ${await safeText(res)}`);
      }, timeoutMs, `PUT frame ${placementKey}`);
    },

    async complete(warnings) {
      return postJson('complete', { warnings: warnings || [] });
    },

    async fail(message, log) {
      return postJson('fail', log ? { message, log } : { message });
    },
  };
}
