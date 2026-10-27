import { slideCss } from './css.js'
import { fontFamilyNames, GENERIC_FAMILIES, resolveSlideshowStyle, type ResolvedSlideshowStyle } from './style.js'
import type { SlideRatio, SlideshowSpec, SlideSpec } from './spec.js'

export const FRAME_W = 1080
export const FRAME_H: Record<SlideRatio, number> = { '9x16': 1920, '4x5': 1350 }
/** TikTok / Reels overlay bands (px) on a 9:16 frame. */
export const SAFE = { top: 220, bottom: 430, right: 140 } as const

export const esc = (s: unknown): string =>
  String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)

/**
 * y values are % of the 9:16 frame; on 4:5 they are mapped into the centre 1350 band.
 */
export function yPx(y: number, ratio: SlideRatio): number {
  return ratio === '4x5' ? Math.max(70, Math.min(1350 - 140, (y / 100) * 1920 - 285)) : (y / 100) * 1920
}

/** Browser-side measuring: runs after fonts and images load, sets window.__CHECKS then window.__READY. */
const CHECK_SCRIPT = String.raw`
(function () {
  var M = window.__META, W = ${FRAME_W}, H = M.ratio === '4x5' ? 1350 : 1920, SZ = ${JSON.stringify(SAFE)};
  var out = [];
  function add(rule, severity, message) { out.push({ slide: M.slide, ratio: M.ratio, rule: rule, severity: severity, message: message }); }
  function label(el) { var nm = el.getAttribute('data-name'); if (nm) return nm; var t = (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 32); return t ? '"' + t + '"' : '<' + el.tagName.toLowerCase() + '>'; }
  async function run() {
    var FF = M.fontFamily, FQ = FF ? '"' + FF + '"' : 'TT', faces = [];
    try { faces = await Promise.all(['600', '700', '800'].map(function (w) { return document.fonts.load(w + ' 60px ' + FQ); })); } catch (e) {}
    try { await document.fonts.ready; } catch (e) {}
    await Promise.all(Array.prototype.map.call(document.images, function (im) { return im.complete ? 0 : new Promise(function (r) { im.onload = im.onerror = r; }); }));
    if (!FF) {
      if (!document.fonts.check('700 60px TT')) add('font', 'error', 'TikTok Sans did not load (document.fonts.check failed)');
    } else if (M.firstFamily && M.generics.indexOf(M.firstFamily.toLowerCase()) < 0) {
      var loaded = faces.some(function (f) { return f && f.length > 0; });
      var probe = document.createElement('span');
      probe.textContent = 'mmmmmmmmmmlliWW@#';
      probe.style.cssText = 'position:absolute;visibility:hidden;white-space:nowrap;font-size:72px;font-weight:700';
      document.body.appendChild(probe);
      var width = function (ff) { probe.style.fontFamily = ff; return probe.getBoundingClientRect().width; };
      var present = loaded || ['monospace', 'serif', 'sans-serif'].some(function (b) { return width('"' + M.firstFamily + '",' + b) !== width(b); });
      probe.remove();
      if (!present) {
        add('font', 'error', M.fontUrl
          ? 'font "' + M.firstFamily + '" did not load from ' + M.fontUrl + ' (check the family name and the stylesheet URL, or use the native look)'
          : 'font "' + M.firstFamily + '" is not installed on this machine; set a fontUrl (for example a Google Fonts stylesheet link) or use an installed family');
      }
    }
    Array.prototype.forEach.call(document.images, function (im) {
      if (!im.naturalWidth) add('image', 'error', 'image failed to load: ' + (im.getAttribute('src') || '').slice(0, 120));
    });
    M.lines.forEach(function (ln) {
      var n = ln.split(/\s+/).filter(Boolean).length;
      if (n > 8) add('wordsPerLine', 'warning', 'text line has ' + n + ' words (max 8 reads well): "' + ln.slice(0, 40) + '"');
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-fg]'), function (el) {
      var r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) return;
      var t = Math.round(r.top), b = Math.round(r.bottom), rt = Math.round(r.right), l = Math.round(r.left);
      if (l < -1 || t < -1 || rt > W + 1 || (b > H + 1 && !el.hasAttribute('data-bleedb'))) {
        add('spill', 'error', label(el) + ' extends past the frame (left ' + l + ', top ' + t + ', right ' + rt + ', bottom ' + b + ' of ' + W + 'x' + H + ')');
      }
      if (M.ratio === '9x16' && !el.hasAttribute('data-spillonly')) {
        if (t < SZ.top) add('safeZone', 'error', label(el) + ' intrudes into the top ' + SZ.top + 'px overlay band (starts at ' + t + 'px)');
        if (b > H - SZ.bottom) add('safeZone', 'error', label(el) + ' intrudes into the bottom ' + SZ.bottom + 'px overlay band (ends at ' + b + 'px, limit ' + (H - SZ.bottom) + 'px)');
        if (el.hasAttribute('data-right') && rt > W - SZ.right) add('safeZone', 'error', label(el) + ' intrudes into the right ' + SZ.right + 'px overlay band (ends at ' + rt + 'px, limit ' + (W - SZ.right) + 'px)');
      }
    });
    Array.prototype.forEach.call(document.querySelectorAll('[data-ts]'), function (el) {
      var fs = parseFloat(getComputedStyle(el).fontSize);
      if (fs < 40) add('textSize', 'warning', label(el) + ' is ' + Math.round(fs) + 'px; text under 40px is hard to read on a phone');
    });
    window.__CHECKS = out;
    window.__READY = true;
  }
  run().catch(function (e) {
    window.__CHECKS = [{ slide: M.slide, ratio: M.ratio, rule: 'render', severity: 'error', message: 'measuring failed: ' + (e && e.message) }];
    window.__READY = true;
  });
})();
`

function slideBody(sl: SlideSpec, spec: SlideshowSpec, ratio: SlideRatio, style: ResolvedSlideshowStyle): string {
  const r4 = ratio === '4x5'
  const objTop = (d: number) => (r4 ? (sl.top4 ?? 60) : (sl.top ?? d))
  let html = ''
  if (sl.bg) html += `<img class="bg ${sl.blur ? 'blur' : ''} ${sl.grade ? 'grade' : ''}" src="${esc(sl.bg)}" style="object-position:${esc(sl.pos || '50% 50%')}">`
  if (sl.bgColor) html += `<div class="bg" style="background:${esc(sl.bgColor)}"></div>`
  if (sl.dim) html += `<div class="dim" style="background:rgba(0,0,0,${sl.dim === true ? 0.28 : Number(sl.dim)})"></div>`

  if (sl.type === 'screen') {
    const top = yPx(sl.phoneY ?? 30, ratio)
    const crop = sl.cropH ? `style="height:${Number(sl.cropH)}px"` : ''
    const callouts = (sl.callouts ?? [])
      .map((c) => {
        const x = Math.max(0, Math.min(100, Number(c.x)))
        const y = Math.max(0, Math.min(100, Number(c.y)))
        const up = y >= 30
        const len = 80
        const lineTop = up ? `calc(${y}% - ${len}px)` : `${y}%`
        const pillTop = up ? `calc(${y}% - ${len}px)` : `calc(${y}% + ${len}px)`
        // the pill slides from left-aligned (x=0) to right-aligned (x=100) so it stays inside the screenshot
        const shift = `translate(-${x}%,${up ? '-100%' : '0'})`
        return (
          `<div class="ln" style="left:${x}%;top:${lineTop};height:${len}px"></div>` +
          `<div class="dot" style="left:${x}%;top:${y}%"></div>` +
          `<div class="pill" data-fg data-ts style="left:${x}%;top:${pillTop};transform:${shift}">${esc(c.label)}</div>`
        )
      })
      .join('')
    html +=
      `<div class="phone" data-fg data-spillonly data-bleedb data-name="phone screenshot" style="top:${top}px;--pw:${Number(sl.pw || 640)}px;--rot:${Number(sl.rot || 0)}deg">` +
      `<div class="scr"><div class="crop" ${crop}><img src="${esc(sl.shot)}" style="${sl.shift ? `margin-top:-${Number(sl.shift)}px` : ''}"></div></div>` +
      `${callouts ? `<div class="callouts">${callouts}</div>` : ''}</div>`
  }

  if (sl.type === 'notes') {
    const items = (sl.items ?? [])
      .map((it) =>
        typeof it === 'string'
          ? `<li data-ts><i></i><span>${esc(it)}</span></li>`
          : `<li data-ts class="${it.done ? 'done' : ''} ${it.strike ? 'strike' : ''}"><i></i><span>${esc(it.t)}</span></li>`
      )
      .join('')
    const paras = (sl.paras ?? []).map((p) => `<p data-fg data-ts>${esc(p)}</p>`).join('')
    html +=
      `<div class="notes" style="--top:${r4 ? 30 : 230}px;${sl.ls ? `--ls:${Number(sl.ls)}px;` : ''}${sl.gap ? `--gap:${Number(sl.gap)}px;` : ''}">` +
      `<div class="bar"><b>&#x2039; Notes</b><b>Done</b></div>` +
      `${sl.date ? `<div class="date">${esc(sl.date)}</div>` : ''}` +
      `<h1 data-fg data-ts>${esc(sl.title || '')}</h1>${paras}<ul data-fg>${items}</ul></div>`
  }

  if (sl.type === 'grid') {
    const cells = (sl.photos ?? [])
      .map(
        (p) =>
          `<div class="${p.x ? 'x' : ''} ${p.ok ? 'ok' : ''} ${p.restore ? 'restore' : ''}"><img src="${esc(p.src)}" style="object-position:${esc(p.pos || '50% 50%')};${p.zoom ? `transform:scale(${Number(p.zoom)})` : ''}">` +
          `${p.cap ? '<i class="cap"><b></b><b></b></i>' : ''}${p.badge ? `<span class="badge">${esc(p.badge)}</span>` : ''}</div>`
      )
      .join('')
    html += `<div class="grid" style="--cols:${Number(sl.cols || 3)};--top:${Number(sl.gridTop ?? 0)}px">${cells}</div>`
  }

  if (sl.type === 'stack') {
    const photos = sl.photos ?? []
    const n = photos.length
    const panels = photos
      .map((p, i) => {
        // keep the first panel's label below TikTok's top overlay band on 9:16
        const lt = i === 0 && ratio === '9x16' ? SAFE.top + 20 : 30
        return (
          `<div><img class="grade" src="${esc(p.src)}" style="object-position:${esc(p.pos || '50% 50%')}">` +
          `${p.label ? `<div class="lbl" style="--lt:${lt}px"><span class="box" data-fg data-ts>${esc(p.label)}</span></div>` : ''}</div>`
        )
      })
      .join('')
    html += `<div class="stack" style="--n:${n}">${panels}</div>`
  }

  if (sl.type === 'tier') {
    const rows = (sl.rows ?? [])
      .map((row) => `<div class="row"><div class="k" style="background:${esc(row.c)}">${esc(row.k)}</div><div class="v">${(row.items ?? []).map((x) => `<span data-ts>${esc(x)}</span>`).join('')}</div></div>`)
      .join('')
    html += `<div class="tier" data-fg style="--top:${objTop(250)}px;${sl.ts ? `--ts:${Number(sl.ts)}px` : ''}">${sl.title ? `<h2 data-ts>${esc(sl.title)}</h2>` : ''}${rows}</div>`
  }

  if (sl.type === 'week') {
    const days = (sl.days ?? [])
      .map(
        (d) =>
          `<div class="d ${d.fog ? 'fog' : ''}"><b>${esc(d.d)}</b><div class="im">${d.img ? `<img src="${esc(d.img)}">` : esc(d.icon ?? '?')}</div><span data-ts>${esc(d.t)}</span></div>`
      )
      .join('')
    html += `<div class="week" data-fg style="--top:${objTop(260)}px">${sl.title ? `<h3 data-ts>${esc(sl.title)}</h3>` : ''}${days}</div>`
  }

  if (sl.type === 'receipt') {
    const body = (sl.sections ?? [])
      .map((s) => `<div class="a">${esc(s.h)}</div>${(s.items ?? []).map((x) => `<div class="r"><span>${esc(x)}</span></div>`).join('')}`)
      .join('')
    html +=
      `<div class="receipt" data-fg data-ts style="--top:${objTop(250)}px"><div class="c">${esc(sl.head ?? '')}</div><hr>${body}<hr>` +
      `<div class="r"><span>ITEMS</span><span>${esc(sl.count || '')}</span></div></div>`
  }

  if (sl.type === 'placard') {
    html +=
      `<div class="placard" data-fg data-right style="--y:${yPx(sl.y ?? 52, ratio)}px"><div class="ex">${esc(sl.ex || '')}</div><h4 data-ts>${esc(sl.title)}</h4>` +
      `${(sl.lines ?? []).map((l) => `<p data-ts>${l.k ? `<i>${esc(l.k)}</i> ` : ''}${esc(l.v)}</p>`).join('')}</div>`
  }

  if (sl.type === 'messages') {
    const bubbles = (sl.bubbles ?? []).map((b) => `<div class="b ${b.from === 'me' ? 'me' : 'them'}" data-fg data-ts>${esc(b.t)}</div>`).join('')
    html +=
      `<div class="msgs ${sl.bg || sl.bgColor ? 'clear' : ''}"><div class="in" style="--top:${objTop(300)}px">` +
      `${sl.title ? `<div class="ttl" data-fg data-ts>${esc(sl.title)}</div>` : ''}${bubbles}</div></div>`
  }

  // texts without a y join the previous text's stack, so wrapped lines never collide
  const groups: Array<{ y: number | undefined; full?: boolean; items: NonNullable<SlideSpec['texts']> }> = []
  for (const t of sl.texts ?? []) {
    if (t.y === undefined && groups.length) groups[groups.length - 1]!.items.push(t)
    else groups.push({ y: t.y, full: t.full, items: [t] })
  }
  for (const g of groups) {
    const inner = g.items
      .map((t) => {
        const kind = t.style ?? style.defaultText
        const cls = kind === 'stroke' ? 'stroke' : kind === 'sub' ? 'sub' : `box ${kind === 'dark' ? 'dark' : ''}`
        const lines = String(t.t).split('\n').map(esc).join('<br>')
        return `<div style="${t.fs ? `--fs:${Number(t.fs)}px;` : ''}${t.ss ? `--ss:${Number(t.ss)}px;` : ''}"><span class="${cls}" data-fg data-right data-ts>${lines}</span></div>`
      })
      .join('')
    html += `<div class="txt ${g.full ? 'full' : ''}" style="top:${yPx(g.y ?? 0, ratio)}px">${inner}</div>`
  }
  if (sl.grain || spec.grain) html += '<div class="grain"></div>'
  return html
}

/**
 * The full HTML document for one slide. Pure: image sources are used as given (the composer resolves them
 * to file URLs first); `assetBase` is the URL, ending in "/", of the folder holding the bundled fonts.
 */
export function buildSlideHtml(spec: SlideshowSpec, index: number, ratio: SlideRatio, assetBase: string): string {
  const sl = spec.slides[index]
  if (!sl) throw new Error(`No slide at index ${index}`)
  const lines = (sl.texts ?? []).flatMap((t) => String(t.t).split('\n'))
  const style = resolveSlideshowStyle(spec.style)
  const meta = JSON.stringify({ slide: index + 1, ratio, lines, fontFamily: style.fontFamily, firstFamily: style.fontFamily ? fontFamilyNames(style.fontFamily)[0] : null, generics: GENERIC_FAMILIES, fontUrl: style.fontUrl }).replace(/</g, '\\u003c')
  const base = assetBase.endsWith('/') ? assetBase : `${assetBase}/`
  return (
    `<!doctype html>\n<html style="--H:${FRAME_H[ratio]}px"><head><meta charset="utf-8">${style.fontUrl ? `<link rel="stylesheet" href="${esc(style.fontUrl)}">` : ''}<style>${slideCss(base, style)}</style></head>\n` +
    `<body><div id="s">${slideBody(sl, spec, ratio, style)}</div>\n` +
    `<script>window.__META=${meta};</script><script>${CHECK_SCRIPT}</script></body></html>\n`
  )
}
