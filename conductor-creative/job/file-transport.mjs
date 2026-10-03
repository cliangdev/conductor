/* file-transport.mjs — a transport (see transport.mjs) that talks to NO backend at all: it renders a
 * DRAFT spec (the backend's `draft-spec` response, which persists nothing) and leaves the frames in a
 * local directory, so a person can look at the artwork before anything is uploaded or saved.
 *
 * It implements the same interface `run()` in render.mjs calls: getSpec / putFrame / putPoster /
 * complete / fail, plus `getRenderId()` (run() asks for it before reporting a failure).
 *
 *   getSpec()   the draft spec, with every `local:<key>` string replaced by an http URL the headless
 *               browser (and ffmpeg, for audio) can load. The local files are served by a small
 *               loopback server (job/server.mjs, cors + Range) rooted at a temp dir that holds a
 *               symlink (or copy) of each file — the server never exposes anything but those files.
 *   putFrame    writes `sheet.jpg` (the contact sheet; named by the frame's content type),
 *               `<placement>[-<index>].jpg|.mp4`
 *   putPoster   writes `poster-<placement>[-<index>].jpg`
 *   complete    writes `manifest.json` { ok: true, frames, warnings } — plus, when run() was asked to
 *               check placements, `checks` (the full render's per-placement assertion results) and
 *               `passed` (no check has severity "error")
 *   fail        writes `manifest.json` { ok: false, error, frames, warnings }
 *
 * The asset server is started lazily by getSpec() and closed by complete()/fail()/close() (all
 * idempotent), so a caller that never reaches either still has `close()`.
 */
import { copyFile, mkdir, mkdtemp, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { extname, join, resolve } from 'node:path';
import { startServer } from './server.mjs';

const LOCAL_REF = /^local:([a-z0-9][a-z0-9-]{0,63})$/;

const EXT_BY_TYPE = {
  'image/jpeg': '.jpg',
  'image/png': '.png',
  'image/webp': '.webp',
  'video/mp4': '.mp4',
};

/** A filesystem-safe fragment of a placement key / index. */
function safe(part) {
  return String(part).replace(/[^A-Za-z0-9._-]/g, '_');
}

/** Returns a copy of `value` with every string of the exact form `local:<key>` replaced by
 * `urlFor(key)`. Walks objects and arrays; leaves every other value untouched. */
function replaceLocalRefs(value, urlFor) {
  if (typeof value === 'string') {
    const m = LOCAL_REF.exec(value);
    return m ? urlFor(m[1]) : value;
  }
  if (Array.isArray(value)) return value.map((v) => replaceLocalRefs(v, urlFor));
  if (value && typeof value === 'object') {
    const out = {};
    for (const [k, v] of Object.entries(value)) out[k] = replaceLocalRefs(v, urlFor);
    return out;
  }
  return value;
}

/** Collects every `local:<key>` key a spec references. */
export function localKeysIn(spec) {
  const keys = new Set();
  replaceLocalRefs(spec, (key) => {
    keys.add(key);
    return key;
  });
  return [...keys];
}

/**
 * @param {object}  opts
 * @param {object}  opts.spec        the draft spec (`{renderId, previewOnly, creative, brand, placements}`)
 * @param {Record<string,string>} [opts.localFiles]  key -> absolute path of the local file behind `local:<key>`
 * @param {string}  opts.outDir      directory the frames and manifest.json are written into (created if absent)
 */
export function createFileTransport({ spec, localFiles = {}, outDir }) {
  if (!spec) throw new Error('createFileTransport: `spec` is required');
  if (!outDir) throw new Error('createFileTransport: `outDir` is required');
  const out = resolve(outDir);

  let assetDir;
  let assetServer;
  let renderId;
  let closed = false;
  let error;
  const frames = [];
  let warnings = [];
  let checks;

  async function startAssetServer(keys) {
    assetDir = await mkdtemp(join(tmpdir(), 'cc-draft-assets-'));
    const files = {};
    for (const key of keys) {
      const source = localFiles[key];
      if (!source) {
        throw new Error(`the draft references local:${key} but no local file was given for "${key}"`);
      }
      const name = `${key}${extname(source).toLowerCase()}`;
      const dest = join(assetDir, name);
      try {
        await symlink(resolve(source), dest);
      } catch {
        await copyFile(source, dest); // symlinks can be unavailable (Windows without privileges)
      }
      files[key] = name;
    }
    assetServer = await startServer(assetDir, 0, { cors: true });
    return files;
  }

  async function close() {
    if (closed) return;
    closed = true;
    if (assetServer) await new Promise((r) => assetServer.server.close(() => r()));
    if (assetDir) await rm(assetDir, { recursive: true, force: true }).catch(() => {});
  }

  async function writeManifest(extra) {
    await mkdir(out, { recursive: true });
    const manifest = {
      ok: !error,
      ...(error ? { error } : {}),
      renderId: renderId || 'draft',
      previewOnly: !!spec.previewOnly,
      frames,
      warnings,
      ...(checks ? { checks, passed: !checks.some((c) => c.severity === 'error') } : {}),
      ...extra,
    };
    await writeFile(join(out, 'manifest.json'), JSON.stringify(manifest, null, 2));
    return manifest;
  }

  return {
    async getSpec() {
      let resolved = spec;
      const keys = localKeysIn(spec);
      if (keys.length) {
        try {
          const files = await startAssetServer(keys);
          resolved = replaceLocalRefs(spec, (key) => `${assetServer.origin}/${files[key]}`);
        } catch (err) {
          error = err.message; // run() does not call fail() when the spec never resolved; keep the reason
          await close();
          throw err;
        }
      }
      renderId = spec.renderId || 'draft';
      return resolved;
    },

    async putFrame(placementKey, { index, width, height, bytes, contentType = 'image/jpeg', durationSeconds, hasAudio } = {}) {
      const ext = EXT_BY_TYPE[contentType] || '.bin';
      const hasIndex = index !== undefined && index !== null;
      const file = `${safe(placementKey)}${hasIndex ? `-${safe(index)}` : ''}${ext}`;
      await mkdir(out, { recursive: true });
      await writeFile(join(out, file), bytes);
      frames.push({
        placementKey,
        ...(hasIndex ? { index } : {}),
        file,
        contentType,
        width,
        height,
        sizeBytes: bytes.length,
        ...(durationSeconds !== undefined && durationSeconds !== null ? { durationSeconds } : {}),
        ...(hasAudio !== undefined && hasAudio !== null ? { hasAudio } : {}),
      });
    },

    async putPoster(placementKey, bytes, { index } = {}) {
      const hasIndex = index !== undefined && index !== null;
      const file = `poster-${safe(placementKey)}${hasIndex ? `-${safe(index)}` : ''}.jpg`;
      await mkdir(out, { recursive: true });
      await writeFile(join(out, file), bytes);
      const frame = frames.find((f) => f.placementKey === placementKey && (f.index ?? null) === (hasIndex ? index : null));
      if (frame) frame.poster = file;
    },

    async complete(w, c) {
      warnings = Array.isArray(w) ? w : [];
      checks = Array.isArray(c) ? c : undefined;
      try {
        return await writeManifest();
      } finally {
        await close();
      }
    },

    async fail(message) {
      error = message || 'render failed';
      try {
        return await writeManifest();
      } finally {
        await close();
      }
    },

    /** 'draft' once getSpec() has succeeded, `undefined` before — run() uses this to decide whether
     * there is a render to mark failed. */
    getRenderId() {
      return renderId;
    },

    /** The error `fail()` (or a failed getSpec()) recorded, if any. */
    getError() {
      return error;
    },

    /** Stops the asset server and removes the temp asset dir. Safe to call more than once. */
    close,
  };
}
