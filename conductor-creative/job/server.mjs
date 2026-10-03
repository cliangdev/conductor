/* server.mjs — tiny static file server scoped to the conductor-creative
 * package root, so frame.html/sheet.html and everything they import
 * (render.js, placements.js, layouts/, styles.css, assertions.js, ...)
 * resolve over http. Dependency-free node:http: the job's only npm
 * dependency is playwright. `type="module"` imports and stylesheet <link>s
 * are both blocked under file://, which is why this exists rather than just
 * pointing Playwright at the files directly.
 *
 * Adapted from nexus-marketing/social/server.mjs; the only change is that
 * the static root is a parameter (this package's root) rather than assumed
 * to be the module's own directory, since this job lives one level down, in
 * job/.
 */
import { createServer } from 'node:http';
import { createReadStream } from 'node:fs';
import { stat } from 'node:fs/promises';
import { extname, join, normalize, sep } from 'node:path';

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.gif': 'image/gif',
  '.mp4': 'video/mp4',
  '.m4v': 'video/mp4',
  '.mov': 'video/quicktime',
  '.webm': 'video/webm',
  '.mp3': 'audio/mpeg',
  '.m4a': 'audio/mp4',
  '.aac': 'audio/aac',
  '.wav': 'audio/wav',
  '.ogg': 'audio/ogg',
};

/* Parses a single `Range: bytes=a-b` header against a file of `size` bytes. Returns `{start, end}`
 * (inclusive), `'unsatisfiable'`, or `null` when there is no usable range (serve the whole file). */
function parseRange(header, size) {
  const m = /^bytes=(\d*)-(\d*)$/.exec(header || '');
  if (!m || (m[1] === '' && m[2] === '')) return null;
  let start;
  let end;
  if (m[1] === '') {
    const suffix = Number(m[2]);
    start = Math.max(0, size - suffix);
    end = size - 1;
  } else {
    start = Number(m[1]);
    end = m[2] === '' ? size - 1 : Math.min(Number(m[2]), size - 1);
  }
  if (start > end || start >= size) return 'unsatisfiable';
  return { start, end };
}

/* `cors` adds a permissive Access-Control-Allow-Origin header. It is for a SECOND server that serves
 * local media (job/file-transport.mjs) from a different loopback port than the page that embeds it:
 * a cross-origin <img>/<video> needs no CORS to display, but a stricter load path (a fetch, a font)
 * does, and the server only ever listens on 127.0.0.1. Range requests are always honoured — a video
 * <video> element cannot seek without them. */
function makeHandler(root, { cors = false } = {}) {
  return async function handler(req, res) {
    const url = new URL(req.url, 'http://localhost');
    let rel = decodeURIComponent(url.pathname);
    if (rel === '/') rel = '/frame.html';
    // Contain every request inside `root`.
    const path = join(root, normalize(rel).replace(/^(\.\.[/\\])+/, ''));
    if (path !== root && !path.startsWith(root + sep)) {
      res.writeHead(403).end('forbidden');
      return;
    }
    let info;
    try {
      info = await stat(path);
      if (!info.isFile()) throw new Error('not a file');
    } catch {
      res.writeHead(404).end('not found');
      return;
    }
    const headers = {
      'content-type': TYPES[extname(path).toLowerCase()] || 'application/octet-stream',
      'accept-ranges': 'bytes',
      'cache-control': 'no-store',
      ...(cors ? { 'access-control-allow-origin': '*' } : {}),
    };
    const range = parseRange(req.headers.range, info.size);
    if (range === 'unsatisfiable') {
      res.writeHead(416, { ...headers, 'content-range': `bytes */${info.size}` }).end();
      return;
    }
    const status = range ? 206 : 200;
    const start = range ? range.start : 0;
    const end = range ? range.end : info.size - 1;
    res.writeHead(status, {
      ...headers,
      'content-length': info.size === 0 ? 0 : end - start + 1,
      ...(range ? { 'content-range': `bytes ${start}-${end}/${info.size}` } : {}),
    });
    if (req.method === 'HEAD' || info.size === 0) {
      res.end();
      return;
    }
    const stream = createReadStream(path, { start, end });
    stream.on('error', () => res.destroy());
    stream.pipe(res);
  };
}

/** Starts a static server rooted at `root` on an ephemeral loopback port.
 * Resolves `{ server, origin }`; call `server.close()` when done. `options.cors` adds an
 * `Access-Control-Allow-Origin: *` header (see makeHandler). */
export function startServer(root, port = 0, options = {}) {
  const handle = makeHandler(root, options);
  return new Promise((resolve) => {
    const server = createServer((req, res) => {
      handle(req, res).catch((err) => {
        res.writeHead(500).end(String((err && err.message) || err));
      });
    });
    server.listen(port, '127.0.0.1', () => {
      resolve({ server, origin: `http://127.0.0.1:${server.address().port}` });
    });
  });
}
