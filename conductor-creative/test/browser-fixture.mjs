/* browser-fixture.mjs — shared helpers for the tests that drive this package's own pages (frame.html,
 * sheet.html) in a real Chromium and measure what the browser actually laid out. Not a test file itself
 * (the test glob matches `*.test.mjs` only). `playwright` lives in job/node_modules, so it is resolved
 * relative to job/, the same way job/render.mjs does. */
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { startServer } from '../job/server.mjs';
import { isChromiumAvailable } from '../job/render.mjs';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const requireFromJob = createRequire(new URL('../job/render.mjs', import.meta.url));

/** SVG data URLs of a known size: a decodable picture whose natural aspect ratio the layout must cope with. */
export function svgPhoto(w, h, fill = '#3a7') {
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${w}" height="${h}" viewBox="0 0 ${w} ${h}">`
    + `<rect width="${w}" height="${h}" fill="${fill}"/><circle cx="${w / 2}" cy="${h / 2}" r="${Math.min(w, h) / 4}" fill="#e44"/></svg>`;
  return 'data:image/svg+xml;utf8,' + encodeURIComponent(svg);
}

export const PHOTOS = {
  landscape: svgPhoto(1600, 900),
  portrait: svgPhoto(900, 1600),
  square: svgPhoto(1000, 1000),
};

export const LOGO = svgPhoto(200, 60, '#e44');
export const BRAND = { logos: { mark: LOGO, wordmarkLight: LOGO, wordmarkDark: LOGO, badge: LOGO }, ctaClaim: '7 days free' };

let available = null;
export async function chromiumReady() {
  if (available === null) available = await isChromiumAvailable();
  return available;
}

/** Starts the static server and one Chromium; `open(page, spec)` loads a page with `window.__RENDER_SPEC__`
 * set and resolves once the page reports ready. Always `close()` it. */
export async function startBrowser() {
  const { chromium } = requireFromJob('playwright');
  const { server, origin } = await startServer(ROOT);
  const browser = await chromium.launch();
  return {
    origin,
    async open(pageName, spec, { scale = 1 } = {}) {
      const page = await browser.newPage({ deviceScaleFactor: scale });
      await page.addInitScript((s) => { window.__RENDER_SPEC__ = s; }, spec);
      await page.goto(`${origin}/${pageName}`, { waitUntil: 'load' });
      await page.waitForFunction(() => window.__ready === true, null, { timeout: 20000 });
      return page;
    },
    async close() {
      await browser.close();
      server.close();
    },
  };
}
