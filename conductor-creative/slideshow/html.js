// GENERATED from conductor-tools/src/lib/slideshow/html.ts by conductor-tools/scripts/sync-slideshow-core.mjs.
// Do not edit: change the TypeScript source and run `npm run build` in conductor-tools.
import { BUNDLED_FONTS, slideCss } from './css.js';
import { fontFamilyNames, isGenericFamily, resolveSlideshowStyle } from './style.js';
export const FRAME_W = 1080;
export const FRAME_H = { '9x16': 1920, '4x5': 1350 };
/** TikTok / Reels overlay bands (px) on a 9:16 frame. */
export const SAFE = { top: 290, bottom: 430, right: 140 };
export const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
/**
 * y values are % of the 9:16 frame; on 4:5 they are mapped into the centre 1350 band.
 */
export function yPx(y, ratio) {
    return ratio === '4x5' ? Math.max(70, Math.min(1350 - 140, (y / 100) * 1920 - 285)) : (y / 100) * 1920;
}
/** Browser-side measuring: runs after fonts and images load, sets window.__CHECKS then window.__READY. */
const CHECK_SCRIPT = String.raw `
(function () {
  var M = window.__META, W = ${FRAME_W}, H = M.ratio === '4x5' ? 1350 : 1920, SZ = ${JSON.stringify(SAFE)};
  var out = [];
  function add(rule, severity, message) { out.push({ slide: M.slide, ratio: M.ratio, rule: rule, severity: severity, message: message }); }
  function label(el) { var nm = el.getAttribute('data-name'); if (nm) return nm; var t = (el.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 32); return t ? '"' + t + '"' : '<' + el.tagName.toLowerCase() + '>'; }
  async function run() {
    var FF = M.fontFamily, FQ = M.firstFamily ? '"' + M.firstFamily + '"' : 'TT', faces = [];
    // load the first real family even when no text on this slide uses it, so the check can tell loaded from missing
    try { faces = await Promise.all(['600', '700', '800'].map(function (w) { return document.fonts.load(w + ' 60px ' + FQ); })); } catch (e) {}
    // the bundled TikTok Sans is used by the native text and by a placard's label, so check it whenever any text uses it
    if (M.needsTT && FQ !== 'TT') { try { await Promise.all(['600', '700', '800'].map(function (w) { return document.fonts.load(w + ' 60px TT'); })); } catch (e) {} }
    try { await document.fonts.ready; } catch (e) {}
    await Promise.all(Array.prototype.map.call(document.images, function (im) { return im.complete ? 0 : new Promise(function (r) { im.onload = im.onerror = r; }); }));
    if (M.needsTT && !document.fonts.check('700 60px TT')) add('font', 'error', 'TikTok Sans did not load (document.fonts.check failed)');
    // the bundled Inter / Source Serif 4 / Courier Prime this slide's type uses: a face that will not load would fall back to whatever the machine has
    for (var bi = 0; bi < (M.bundled || []).length; bi++) {
      var bf = M.bundled[bi];
      for (var wi = 0; wi < bf.weights.length; wi++) {
        var got = [];
        try { got = await document.fonts.load(bf.weights[wi] + ' 60px "' + bf.family + '"'); } catch (e) {}
        if (!got.length) { add('font', 'error', 'bundled font ' + bf.family + ' (' + bf.weights[wi] + ') did not load; the package install is incomplete (assets/fonts)'); break; }
      }
    }
    if (FF && M.firstFamily) {
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
    contrast();
    window.__CHECKS = out;
    window.__READY = true;
  }

  // --- contrast (warnings): WCAG relative luminance, the same formula as conductor-creative/assertions.js ---
  var LIN = []; for (var li = 0; li < 256; li++) { var lv = li / 255; LIN.push(lv <= 0.03928 ? lv / 12.92 : Math.pow((lv + 0.055) / 1.055, 2.4)); }
  function lum(c) { return 0.2126 * LIN[Math.round(c[0])] + 0.7152 * LIN[Math.round(c[1])] + 0.0722 * LIN[Math.round(c[2])]; }
  function ratio(a, b) { var l1 = lum(a), l2 = lum(b); return Math.round(((Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05)) * 10) / 10; }
  function rgba(str) {
    var m = /^rgba?\(([0-9., ]+)\)$/.exec(String(str || '').trim());
    if (!m) return null;
    var p = m[1].split(/[, ]+/).filter(Boolean).map(Number);
    return p.length >= 3 && p.every(function (n) { return isFinite(n); }) ? { c: [p[0], p[1], p[2]], a: p.length > 3 ? p[3] : 1 } : null;
  }
  var MIN_CONTRAST = 3, HIDE = null;
  // the colour behind an element when something opaque is: its own or an ancestor's background (up to the slide, #s)
  function opaqueBg(el) {
    for (var n = el; n && n.nodeType === 1 && n.id !== 's'; n = n.parentElement) {
      var b = rgba(getComputedStyle(n).backgroundColor);
      if (b && b.a >= 0.95) return b.c;
    }
    return null;
  }
  // a text outline or halo wide enough to carry the text on its own: a stroke of 2px or more, or a shadow blurred 2px or more
  function halo(cs) {
    var sw = parseFloat(cs.webkitTextStrokeWidth);
    var sc = rgba(cs.webkitTextStrokeColor);
    if (sw >= 2 && sc && sc.a >= 0.5) return sc.c;
    var re = /(rgba?\([^)]*\))\s+(-?[0-9.]+)px\s+(-?[0-9.]+)px\s+([0-9.]+)px/g, m;
    while ((m = re.exec(cs.textShadow || ''))) {
      var c = rgba(m[1]);
      if (parseFloat(m[4]) >= 2 && c && c.a >= 0.5) return c.c;
    }
    return null;
  }
  function contrast() {
    window.__overImage = [];
    Array.prototype.forEach.call(document.querySelectorAll('[data-fg][data-ts]'), function (el) {
      var r = el.getBoundingClientRect();
      if (r.width < 1 || r.height < 1) return;
      var cs = getComputedStyle(el), fg = rgba(cs.color);
      if (!fg) return;
      var against = opaqueBg(el), what = 'its background';
      if (!against) { against = halo(cs); what = 'its outline'; }
      if (against) {
        var cr = ratio(fg.c, against);
        if (cr < MIN_CONTRAST) add('contrast', 'warning', label(el) + ' has ' + cr + ':1 contrast against ' + what + ' (' + MIN_CONTRAST + ':1 is the minimum for large text)');
      } else {
        window.__overImage.push({ label: label(el), fg: fg.c, rect: [r.left, r.top, r.width, r.height] });
      }
    });
  }
  // Text with no box or outline sits straight on the slide. A file: image taints a canvas, so the caller renders the
  // slide with its text hidden (__hideText), takes a screenshot and hands it back here as a data: URL to measure.
  window.__hideText = function (on) {
    if (!HIDE) { HIDE = document.createElement('style'); HIDE.textContent = '[data-fg][data-ts]{color:transparent!important;text-shadow:none!important;-webkit-text-stroke-color:transparent!important}'; }
    if (on) document.head.appendChild(HIDE); else HIDE.remove();
  };
  window.__sampleContrast = async function (dataUrl) {
    var found = [];
    try {
      var im = new Image();
      im.src = dataUrl;
      await im.decode();
      var cv = document.createElement('canvas');
      cv.width = W; cv.height = H;
      var cx = cv.getContext('2d', { willReadFrequently: true });
      cx.drawImage(im, 0, 0, W, H);
      (window.__overImage || []).forEach(function (t) {
        var x = Math.max(0, Math.floor(t.rect[0])), y = Math.max(0, Math.floor(t.rect[1]));
        var w = Math.min(W - x, Math.ceil(t.rect[2])), h = Math.min(H - y, Math.ceil(t.rect[3]));
        if (w < 1 || h < 1) return;
        var d = cx.getImageData(x, y, w, h).data, sum = 0, n = 0;
        for (var py = 0; py < h; py += 3) for (var px = 0; px < w; px += 3) { var i = (py * w + px) * 4; sum += lum([d[i], d[i + 1], d[i + 2]]); n++; }
        if (!n) return;
        var l = sum / n, l2 = lum(t.fg);
        var cr = Math.round(((Math.max(l, l2) + 0.05) / (Math.min(l, l2) + 0.05)) * 10) / 10;
        if (cr < MIN_CONTRAST) found.push({ slide: M.slide, ratio: M.ratio, rule: 'contrast', severity: 'warning', message: t.label + ' has ' + cr + ':1 contrast against the image behind it (' + MIN_CONTRAST + ':1 is the minimum for large text); add a box or outline, dim the photo, or change the text colour' });
      });
    } catch (e) { /* an unreadable canvas: skip the check rather than report a guess */ }
    return found;
  };
  run().catch(function (e) {
    window.__CHECKS = [{ slide: M.slide, ratio: M.ratio, rule: 'render', severity: 'error', message: 'measuring failed: ' + (e && e.message) }];
    window.__READY = true;
  });
})();
`;
/** The bundled families a slide's type draws with (see BUNDLED_FONTS); the native TikTok Sans text is not one. */
export function bundledFamiliesFor(sl) {
    const out = [];
    if (sl.type === 'notes' || sl.type === 'week' || sl.type === 'messages' || (sl.type === 'grid' && (sl.photos ?? []).some((p) => p.badge)))
        out.push('Inter');
    if (sl.type === 'placard')
        out.push('Source Serif 4');
    if (sl.type === 'receipt')
        out.push('Courier Prime');
    return out;
}
function slideBody(sl, spec, ratio, style) {
    const r4 = ratio === '4x5';
    const objTop = (d) => (r4 ? (sl.top4 ?? 60) : (sl.top ?? d));
    let html = '';
    if (sl.bg)
        html += `<img class="bg ${sl.blur ? 'blur' : ''} ${sl.grade ? 'grade' : ''}" src="${esc(sl.bg)}" style="object-position:${esc(sl.pos || '50% 50%')}">`;
    if (sl.bgColor)
        html += `<div class="bg" style="background:${esc(sl.bgColor)}"></div>`;
    if (sl.dim)
        html += `<div class="dim" style="background:rgba(0,0,0,${sl.dim === true ? 0.28 : Number(sl.dim)})"></div>`;
    if (sl.type === 'screen') {
        const top = yPx(sl.phoneY ?? 30, ratio);
        const crop = sl.cropH ? `style="height:${Number(sl.cropH)}px"` : '';
        const callouts = (sl.callouts ?? [])
            .map((c) => {
            const x = Math.max(0, Math.min(100, Number(c.x)));
            const y = Math.max(0, Math.min(100, Number(c.y)));
            const up = y >= 30;
            const len = 80;
            const lineTop = up ? `calc(${y}% - ${len}px)` : `${y}%`;
            const pillTop = up ? `calc(${y}% - ${len}px)` : `calc(${y}% + ${len}px)`;
            // the pill slides from left-aligned (x=0) to right-aligned (x=100) so it stays inside the screenshot
            const shift = `translate(-${x}%,${up ? '-100%' : '0'})`;
            return (`<div class="ln" style="left:${x}%;top:${lineTop};height:${len}px"></div>` +
                `<div class="dot" style="left:${x}%;top:${y}%"></div>` +
                `<div class="pill" data-fg data-ts style="left:${x}%;top:${pillTop};transform:${shift}">${esc(c.label)}</div>`);
        })
            .join('');
        html +=
            `<div class="phone" data-fg data-spillonly data-bleedb data-name="phone screenshot" style="top:${top}px;--pw:${Number(sl.pw || 640)}px;--rot:${Number(sl.rot || 0)}deg">` +
                `<div class="scr"><div class="crop" ${crop}><img src="${esc(sl.shot)}" style="${sl.shift ? `margin-top:-${Number(sl.shift)}px` : ''}"></div></div>` +
                `${callouts ? `<div class="callouts">${callouts}</div>` : ''}</div>`;
    }
    if (sl.type === 'notes') {
        const items = (sl.items ?? [])
            .map((it) => typeof it === 'string'
            ? `<li data-ts><i></i><span>${esc(it)}</span></li>`
            : `<li data-ts class="${it.done ? 'done' : ''} ${it.strike ? 'strike' : ''}"><i></i><span>${esc(it.t)}</span></li>`)
            .join('');
        const paras = (sl.paras ?? []).map((p) => `<p data-fg data-ts>${esc(p)}</p>`).join('');
        html +=
            `<div class="notes" style="--top:${r4 ? 30 : 230}px;${sl.ls ? `--ls:${Number(sl.ls)}px;` : ''}${sl.gap ? `--gap:${Number(sl.gap)}px;` : ''}">` +
                `<div class="bar"><b>&#x2039; Notes</b><b>Done</b></div>` +
                `${sl.date ? `<div class="date">${esc(sl.date)}</div>` : ''}` +
                `<h1 data-fg data-ts>${esc(sl.title || '')}</h1>${paras}<ul data-fg>${items}</ul></div>`;
    }
    if (sl.type === 'grid') {
        const cells = (sl.photos ?? [])
            .map((p) => `<div class="${p.x ? 'x' : ''} ${p.ok ? 'ok' : ''} ${p.restore ? 'restore' : ''}"><img src="${esc(p.src)}" style="object-position:${esc(p.pos || '50% 50%')};${p.zoom ? `transform:scale(${Number(p.zoom)})` : ''}">` +
            `${p.cap ? '<i class="cap"><b></b><b></b></i>' : ''}${p.badge ? `<span class="badge">${esc(p.badge)}</span>` : ''}</div>`)
            .join('');
        html += `<div class="grid" style="--cols:${Number(sl.cols || 3)};--top:${Number(sl.gridTop ?? 0)}px">${cells}</div>`;
    }
    if (sl.type === 'stack') {
        const photos = sl.photos ?? [];
        const n = photos.length;
        const panels = photos
            .map((p, i) => {
            // keep the first panel's label below TikTok's top overlay band on 9:16
            const lt = i === 0 && ratio === '9x16' ? SAFE.top + 20 : 30;
            return (`<div><img class="grade" src="${esc(p.src)}" style="object-position:${esc(p.pos || '50% 50%')}">` +
                `${p.label ? `<div class="lbl" style="--lt:${lt}px"><span class="box" data-fg data-ts>${esc(p.label)}</span></div>` : ''}</div>`);
        })
            .join('');
        html += `<div class="stack" style="--n:${n}">${panels}</div>`;
    }
    if (sl.type === 'tier') {
        const rows = (sl.rows ?? [])
            .map((row) => `<div class="row"><div class="k" style="background:${esc(row.c)}">${esc(row.k)}</div><div class="v">${(row.items ?? []).map((x) => `<span data-ts>${esc(x)}</span>`).join('')}</div></div>`)
            .join('');
        html += `<div class="tier" data-fg style="--top:${objTop(250)}px;${sl.ts ? `--ts:${Number(sl.ts)}px` : ''}">${sl.title ? `<h2 data-ts>${esc(sl.title)}</h2>` : ''}${rows}</div>`;
    }
    if (sl.type === 'week') {
        const days = (sl.days ?? [])
            .map((d) => `<div class="d ${d.fog ? 'fog' : ''}"><b>${esc(d.d)}</b><div class="im">${d.img ? `<img src="${esc(d.img)}">` : esc(d.icon ?? '?')}</div><span data-ts>${esc(d.t)}</span></div>`)
            .join('');
        html += `<div class="week" data-fg style="--top:${objTop(260)}px">${sl.title ? `<h3 data-ts>${esc(sl.title)}</h3>` : ''}${days}</div>`;
    }
    if (sl.type === 'receipt') {
        const body = (sl.sections ?? [])
            .map((s) => `<div class="a">${esc(s.h)}</div>${(s.items ?? []).map((x) => `<div class="r"><span>${esc(x)}</span></div>`).join('')}`)
            .join('');
        html +=
            `<div class="receipt" data-fg data-ts style="--top:${objTop(250)}px"><div class="c">${esc(sl.head ?? '')}</div><hr>${body}<hr>` +
                `<div class="r"><span>ITEMS</span><span>${esc(sl.count || '')}</span></div></div>`;
    }
    if (sl.type === 'placard') {
        html +=
            `<div class="placard" data-fg data-right style="--y:${yPx(sl.y ?? 52, ratio)}px"><div class="ex">${esc(sl.ex || '')}</div><h4 data-ts>${esc(sl.title)}</h4>` +
                `${(sl.lines ?? []).map((l) => `<p data-ts>${l.k ? `<i>${esc(l.k)}</i> ` : ''}${esc(l.v)}</p>`).join('')}</div>`;
    }
    if (sl.type === 'messages') {
        const bubbles = (sl.bubbles ?? []).map((b) => `<div class="b ${b.from === 'me' ? 'me' : 'them'}" data-fg data-ts>${esc(b.t)}</div>`).join('');
        html +=
            `<div class="msgs ${sl.bg || sl.bgColor ? 'clear' : ''}"><div class="in" style="--top:${objTop(300)}px">` +
                `${sl.title ? `<div class="ttl" data-fg data-ts>${esc(sl.title)}</div>` : ''}${bubbles}</div></div>`;
    }
    // texts without a y join the previous text's stack, so wrapped lines never collide
    const groups = [];
    for (const t of sl.texts ?? []) {
        if (t.y === undefined && groups.length)
            groups[groups.length - 1].items.push(t);
        else
            groups.push({ y: t.y, full: t.full, items: [t] });
    }
    for (const g of groups) {
        const inner = g.items
            .map((t) => {
            const kind = t.style ?? style.defaultText;
            const cls = kind === 'stroke' ? 'stroke' : kind === 'sub' ? 'sub' : `box ${kind === 'dark' ? 'dark' : ''}`;
            const lines = String(t.t).split('\n').map(esc).join('<br>');
            return `<div style="${t.fs ? `--fs:${Number(t.fs)}px;` : ''}${t.ss ? `--ss:${Number(t.ss)}px;` : ''}"><span class="${cls}" data-fg data-right data-ts>${lines}</span></div>`;
        })
            .join('');
        html += `<div class="txt ${g.full ? 'full' : ''}" style="top:${yPx(g.y ?? 0, ratio)}px">${inner}</div>`;
    }
    if (sl.grain || spec.grain)
        html += '<div class="grain"></div>';
    return html;
}
/**
 * The full HTML document for one slide. Pure: image sources are used as given (the composer resolves them
 * to file URLs first); `assetBase` is the URL, ending in "/", of the folder holding the bundled fonts.
 */
export function buildSlideHtml(spec, index, ratio, assetBase, style = resolveSlideshowStyle(spec.style)) {
    const sl = spec.slides[index];
    if (!sl)
        throw new Error(`No slide at index ${index}`);
    const lines = (sl.texts ?? []).flatMap((t) => String(t.t).split('\n'));
    // the family list is parsed once: the first non-keyword name drives the custom-font check and load (none when
    // every family is a generic/system keyword); the bundled TikTok Sans is checked whenever any text uses it
    const firstFamily = style.fontFamily ? (fontFamilyNames(style.fontFamily).find((f) => !isGenericFamily(f)) ?? null) : null;
    const needsTT = !style.fontFamily || sl.type === 'placard';
    const bundled = bundledFamiliesFor(sl);
    const meta = JSON.stringify({
        slide: index + 1,
        ratio,
        lines,
        fontFamily: style.fontFamily,
        firstFamily,
        needsTT,
        fontUrl: style.fontUrl,
        ...(bundled.length ? { bundled: bundled.map((family) => ({ family, weights: BUNDLED_FONTS[family].faces.map(([w]) => w) })) } : {}),
    }).replace(/</g, '\\u003c');
    const base = assetBase.endsWith('/') ? assetBase : `${assetBase}/`;
    return (`<!doctype html>\n<html style="--H:${FRAME_H[ratio]}px"><head><meta charset="utf-8">${style.fontUrl ? `<link rel="stylesheet" href="${esc(style.fontUrl)}">` : ''}<style>${slideCss(base, style, bundled)}</style></head>\n` +
        `<body><div id="s">${slideBody(sl, spec, ratio, style)}</div>\n` +
        `<script>window.__META=${meta};</script><script>${CHECK_SCRIPT}</script></body></html>\n`);
}
