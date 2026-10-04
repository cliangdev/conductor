/* Brand font loading shared by the editor (mount.js) and the headless render
 * pages (frame.js, sheet.js). Browser-only; a no-op outside a document. */

/* Loads `brand.fontUrl` as a <link rel="stylesheet"> (a Google Fonts URL or a
 * self-hosted @font-face sheet both work this way) and resolves once the
 * browser has it, or immediately if there is no fontUrl. Idempotent: calling
 * it twice with the same URL does not insert a second <link>. Does not touch
 * `document.fonts.ready` itself — callers that need the font *painted*, not
 * just requested, should await that separately (mountBoard does). */
const loadedFontUrls = new Set();
export function loadFont(brand) {
  const href = brand && brand.fontUrl;
  if (!href) return Promise.resolve();
  if (loadedFontUrls.has(href)) return Promise.resolve();
  if (typeof document === 'undefined') return Promise.resolve();
  const existing = Array.from(document.querySelectorAll('link[rel="stylesheet"]')).find((l) => l.href === href);
  if (existing) {
    loadedFontUrls.add(href);
    return Promise.resolve();
  }
  return new Promise((resolve, reject) => {
    const link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = href;
    link.onload = () => { loadedFontUrls.add(href); resolve(); };
    link.onerror = () => reject(new Error('failed to load font stylesheet: ' + href));
    document.head.appendChild(link);
  });
}

/* Loads the brand font and waits until its face is actually available, so
 * fitting measures the real glyphs. Safe when the brand has no font. */
export async function ensureBrandFont(brand) {
  await loadFont(brand);
  const family = brand && brand.fontFamily;
  if (family && typeof document !== 'undefined' && document.fonts && document.fonts.load) {
    await Promise.all([400, 700].map((w) => document.fonts.load(`${w} 32px "${family}"`).catch(() => [])));
  }
}

/* Waits until every picture under `root` has loaded: each <img> (the logo lockup, the badge, a band/card
 * photo) AND each bleed photo layer, which is a CSS background and so is not in `document.images` — the
 * page's `load` event does not wait for it either. Without this a screenshot (the contact sheet most of
 * all, which never ran this wait) could be taken before the photo or logos had arrived: a board with no
 * photo and no logo that nothing flagged. A picture that fails to load resolves anyway; it is
 * assertions.js's job (frame.html) to report it. Browser-only; a no-op outside a document. */
export async function settleImages(root) {
  if (typeof document === 'undefined') return;
  const scope = root || document;
  const loads = [...scope.querySelectorAll('img')].map((img) => (
    img.complete ? Promise.resolve() : new Promise((done) => {
      img.addEventListener('load', done, { once: true });
      img.addEventListener('error', done, { once: true });
    })
  ));
  for (const layer of scope.querySelectorAll('.cc-bg-photo')) {
    const m = /url\("?(.+?)"?\)/.exec(getComputedStyle(layer).backgroundImage || '');
    if (!m) continue;
    loads.push(new Promise((done) => {
      const probe = new Image();
      probe.onload = done;
      probe.onerror = done;
      probe.src = m[1];
    }));
  }
  await Promise.all(loads);
  await Promise.all([...scope.querySelectorAll('img')].map((img) => (img.decode ? img.decode().catch(() => {}) : null)));
}
