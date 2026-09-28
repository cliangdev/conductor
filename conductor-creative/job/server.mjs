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
import { readFile, stat } from 'node:fs/promises';
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
};

function makeHandler(root) {
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
    try {
      const info = await stat(path);
      if (!info.isFile()) throw new Error('not a file');
      const body = await readFile(path);
      res.writeHead(200, {
        'content-type': TYPES[extname(path).toLowerCase()] || 'application/octet-stream',
        'content-length': body.length,
        'cache-control': 'no-store',
      });
      res.end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  };
}

/** Starts a static server rooted at `root` on an ephemeral loopback port.
 * Resolves `{ server, origin }`; call `server.close()` when done. */
export function startServer(root, port = 0) {
  const handle = makeHandler(root);
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
