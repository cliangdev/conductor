# @cliangdev/creative-render

The render engine behind Conductor Creatives: the same code draws a live
preview in the web editor and (since T3) drives a headless Playwright job
that produces upload-ready JPEG frames (Instagram feed images and TikTok
photo posts both refuse PNG) — the preview-only contact sheet stays PNG. One
codebase, so the preview a marketer edits against and the frame that gets
attached to a Post can never drift apart.

It is a brand-agnostic port of `nexus-marketing/social/`'s static-ad render
engine (`render.js`, `layouts/`, `placements.json`). Everything Rexipe-specific
in that system — its palette, its logos, its trial-claim copy, its six copy
rules — is gone from this package. **Nothing here may hardcode a brand
constant.** A workspace's Brand Kit is passed in at render time as a plain
`brand` object; a kit with no logo images renders no lockup and no CTA badge,
and a kit with no copy rules configured never fails a save. See
`docs/cli-assets.md`'s domain-agnostic-guidance principle in the main
Conductor repo for why this matters beyond just this package.

Plain ESM (`"type": "module"`), no build step, no runtime dependencies. Runs
unmodified in a browser bundle and in plain Node (`node --test`). The only
devDependency is `linkedom`, used solely to give this package's own tests a
DOM to render into.

## Install (frontend)

There is no monorepo workspace today, so the frontend depends on this package
as a sibling directory:

```json
{ "dependencies": { "@cliangdev/creative-render": "file:../conductor-creative" } }
```

Next.js needs to know to transpile it (it ships plain `.js`, not pre-built):

```js
// next.config.js
module.exports = { transpilePackages: ['@cliangdev/creative-render'] };
```

Import the stylesheet once, globally (e.g. in a root layout or `_app`):

```js
import '@cliangdev/creative-render/styles.css';
```

`styles.css` is `tokens.css` + `frame.css` + every `layouts/<name>/layout.css`
concatenated into one file, so a consumer only ever needs the one import. If
you would rather import layouts individually (e.g. to code-split), the
individual files are also published: `tokens.css`, `frame.css`,
`layouts/<name>/layout.css`. Every class this package renders is prefixed
`cc-` (`cc-board`, `cc-headline`, `cc-lockup`, ...) specifically so it cannot
collide with the host app's own Tailwind classes.

## The creative shape

Every function that takes a "creative" expects this shape, which mirrors the
backend's Work Item-adjacent `creative` API (camelCase):

```ts
type Creative = {
  layout: 'stacked' | 'bleed' | 'card' | 'split'; // default: 'stacked'
  theme?: 'dark' | 'light';                        // default: 'dark'
  lockup?: 'plain' | 'chip';                       // default: 'plain'; 'chip' puts the
                                                    // logo lockup on a white pill, for busy photography
  headline: string;      // exactly one *accent phrase* marked with asterisks
  body?: string;
  caption?: string;      // carried through, never rendered onto the board
  photoUrl?: string;     // an absolute URL (signed GCS URL, blob:, or data:)
  focal?: Record<string, string>;         // per-placement "x% y%", from the photo
  focalOverride?: Record<string, string>; // per-placement "x% y%", wins over focal
  placements?: string[]; // extra placement keys this creative opts into,
                          // beyond the brand kit's enabledPlacements
  layoutOverrides?: {
    band?: Record<string, number>;      // per-placement override of the stacked layout's photo band height (px)
    padBottom?: Record<string, number>; // per-placement override of the 9x16 panel's bottom safe-zone clearance (px)
  };
  typeOverrides?: Record<string, [size: number, leading?: number, tracking?: number]>;
  sequenceKind?: 'story' | 'carousel' | null;
  sequence?: Array<{
    headline?: string;   // inherits the creative's headline when a first beat omits it
    body?: string;       // per-frame opt-in; NOT inherited from the creative
    photoUrl?: string;
    focal?: Record<string, string>;
    cta?: boolean;       // forces the CTA row on/off for this frame; default:
                          // only the LAST frame in the sequence shows it
  }>;

  // MOTION only (creative.kind === 'MOTION'; everything above still applies for copy/look — a MOTION
  // creative is a STILL creative plus an animation timeline). See motion.js's Public API section below.
  kind?: 'STILL' | 'MOTION' | 'CLIP'; // this package only branches on 'MOTION'; CLIP is raw video,
                                       // assembled server-side with no rendering (see PR 1)
  motion?: Motion;
  audio?: Audio;
  backgroundVideoUrl?: string;  // a clip background's signed URL (motion.background.source === 'clip')
  clipStartSec?: number;        // where that clip starts, in seconds (motion.background.clipStartSec)
};

type Motion = {
  preset?: 'fade-up' | 'word-by-word' | 'accent-pop' | 'none'; // default 'fade-up'
  durationSec?: number;                                        // default 8 (3-60)
  background?: {
    source?: 'photo' | 'clip';                                              // default 'photo'
    motion?: 'zoom-in' | 'zoom-out' | 'pan-left' | 'pan-right' | 'none';    // default 'zoom-in'; photo only
  };
  endCard?: boolean; // default true: the last 2s hold the full composition, CTA included
};

type Audio = {
  source?: 'clip' | 'track' | 'none'; // default depends on the background (see motion.js)
  trackUrl?: string;   // a library track's signed URL (source === 'track')
  volume?: number;     // 0-1, default 0.8 (track only — a clip's own sound plays as recorded)
  fadeOutSec?: number; // default 1 (track only)
};
```

## The brand shape

```ts
type Brand = {
  tokens?: {
    accent?: string; accent2?: string;
    darkBg?: string; darkInk?: string;
    lightBg?: string; lightCard?: string;
    ink?: string; ink2?: string;
  };
  fontFamily?: string;   // CSS font-family value, e.g. "Inter"
  fontUrl?: string;      // a stylesheet URL (Google Fonts, or a self-hosted @font-face sheet)
  logos?: {
    mark?: string;           // square icon URL
    wordmarkDark?: string;   // dark-ink wordmark, for a light-ground lockup
    wordmarkLight?: string;  // light-ink wordmark, for a dark-ground lockup
    badge?: string;          // e.g. an app store badge; any aspect ratio
  };
  ctaClaim?: string;      // short claim under the badge, e.g. a trial line
  enabledPlacements?: string[]; // placement keys that render by default
};
```

Every key is optional. Missing tokens fall back to `tokens.css`'s neutral
defaults (a plain gray accent, not any brand's color). Missing logos mean no
lockup and/or no badge render at all — there is no generic placeholder logo.
A brand's tokens are applied as **inline custom properties on the board's own
root element**, never on `:root`, so two different kits can render side by
side on one page (e.g. comparing two workspaces) without one overwriting the
other's colors.

## Public API

### `render.js` — pure engine (also the package's main entry point)

```js
import {
  resolveAd, resolveSequence, layoutFor, renderBoard, fitBoard, fitAll,
  enabledPlacements, tokensToCssVars, applyBrandTokens,
  leadingFor, trackingFor,
} from '@cliangdev/creative-render';
```

- **`resolveAd(creative, placements, layouts) -> Ad`** — fills in every
  default (layout, theme, lockup, focal, band, padBottom, type) so the rest
  of the code can assume a complete ad. `creative.layoutOverrides.band` /
  `.padBottom` win over the layout's own per-placement defaults.
  `placements`/`layouts` are the registries (see below); pass the shipped
  ones or your own.
- **`resolveSequence(creative, placements, layouts) -> Ad[]`** — resolves
  `creative.sequence` into one `Ad` per beat/card, each inheriting the
  creative and overriding what it sets. Returns `[]` when
  `creative.sequenceKind` is unset or `sequence` is empty. The CTA row
  defaults to the last frame only; a frame's own `cta: true|false` wins.
- **`layoutFor(ad, placementKey, layouts) -> { kind, panel }`** — which
  structural layout and panel variant a creative + placement combination uses
  (a layout's `perPlacement` map can swap in a different layout for one
  placement key, e.g. `stacked` at `1x1` becomes `bleed`).
- **`renderBoard(ad, placementKey, placements, layouts, brand, opts?) -> HTMLElement`**
  — builds and returns one `.cc-board` element, fully painted with `brand`'s
  tokens, logos and CTA claim. `opts.safe: true` draws the safe-zone review
  guide (never for an export). When `ad.backgroundVideoUrl` is set (a MOTION
  clip background — `resolveAd` carries this through from `creative.backgroundVideoUrl`), also renders
  a muted `<video class="cc-bg-video" playsinline preload="auto">` covering the photo area, honoring the
  same focal point/object-fit every layout already gives its photo — one generic rule in `frame.css`
  handles all four layouts (see motion.js's header comment for how). It layers AFTER the still photo
  (a same-frame fallback while the video loads) and BEFORE the copy, so plain DOM order keeps the
  stacking right with no z-index anywhere. This element is muted in every case — the render job mixes
  audio into the exported MP4 itself; the browser preview never plays clip/track audio out loud.
- **`fitBoard(board, placements) -> number`** — steps the headline size down
  from the placement's max until the board's copy fits without spilling or
  intruding on the safe zone. Must run after the board is attached to a live
  document and after `document.fonts.ready`.
- **`fitAll(root, placements) -> Promise<void>`** — awaits `document.fonts.ready`
  then calls `fitBoard` on every `.cc-board` under `root`.
- **`enabledPlacements(creative, brandEnabledPlacements, placements) -> string[]`**
  — the brand's `enabledPlacements` (or the registry's `default: true` keys
  when the kit sets none) unioned with `creative.placements`' opt-in extras.
- **`tokensToCssVars(tokens) -> Record<string,string>`** / **`applyBrandTokens(el, brand)`**
  — the brand-token-to-CSS-custom-property mapping, exposed separately so a
  caller can compute or apply it without going through `renderBoard`.

### `motion.js` — the MOTION animation timeline (pure function of time)

```js
import {
  applyMotion, motionKeyTimes, seekBackgroundVideo, backgroundMotionState, easeOutCubic,
} from '@cliangdev/creative-render/motion';
```

Everything here is deterministic: `applyMotion(board, motion, tSec, opts)` computes the exact inline
styles/CSS vars a `.cc-board` (from `renderBoard`) should show at `tSec`, with no `setTimeout`, no CSS
`@keyframes`, and no `Date.now()`. The SAME function drives `mount.js`'s real-time `play()` (via
`requestAnimationFrame`) and the render job's frame-stepped capture (`job/render.mjs`, many discrete
`t` values) — that purity is what keeps a MOTION creative's live editor preview and its exported MP4
from ever drifting apart, the same guarantee `render.js` already gives a STILL frame vs. its preview.

- **`applyMotion(board, motion, tSec, opts?) -> { preset, durationSec, inEndCard, background }`** —
  `board` is a `renderBoard()` result, already `fitBoard`-ed. `opts.durationSec` overrides
  `motion.durationSec` when the caller has already resolved it. Sets fade/fade-up opacity+translateY on
  `.cc-lockup` (0.2-0.6s, fade only), `.cc-headline` (0.3-0.9s, preset-dependent — see below), `.cc-body`
  (1.2-1.7s) and `.cc-cta` (1.8-2.3s); each easeOutCubic. Word-by-word wraps the headline into
  `<span class="cc-word">` (idempotently — safe to call every frame; the accent phrase's own words stay
  nested inside `<em>`, keeping its accent color) and staggers each word's own fade over 0.3-1.5s.
  Accent-pop fades the whole headline 0.3-0.8s, then scales the accent `<em>` 1.18→1 and brightens it
  0.9→1.3 over 0.8-1.1s. `preset: 'none'` shows everything at every `t`. Independently of `preset`, the
  background photo/clip motion (`motion.background.motion`) runs the WHOLE duration, eased, via two CSS
  vars every layout already knows how to consume with no code change: `--cc-bg-transform` (a `scale()
  translateX()`, read by `.cc-board__band img` / `.cc-board__card img` / `.cc-bg-video`) and
  `--cc-bg-size`/`--cc-bg-pos` (read by a bleed layout's own `background-image`, which has no
  `transform`). `motion.endCard !== false` (the default) forces every text/lockup/cta element to its
  finished, fully-visible state for the last 2 seconds regardless of where its own intro window would
  otherwise put it — a 3s creative's CTA (window 1.8-2.3s) still needs to be showing throughout a 1-3s
  hold. The background motion is never affected by the hold.
- **`motionKeyTimes(motion) -> [number, number, number]`** — three representative moments
  (`[0.6, durationSec/2, durationSec-0.5]`) for the `previewOnly` key-moments contact sheet
  (`sheet.html`'s `times`).
- **`seekBackgroundVideo(board, tSec, clipStartSec) -> Promise<void>`** — finds `board`'s
  `.cc-bg-video` (if any) and sets its `currentTime` to `clipStartSec + tSec`, resolving once the
  browser reports `seeked` (or immediately if there is no clip background). Shared by `mount.js`'s
  `seek()` and `frame.js`'s frame-stepped capture driver, so a clip background is frame-accurate in
  both places.
- **`backgroundMotionState(motion, tSec, durationSec) -> { scale, panPct }`** / **`easeOutCubic(x)`** —
  the pure numbers behind the background motion and every fade, exposed for anything that wants them
  without touching the DOM (mostly tests).

### `mount.js` — the browser API for the editor

```js
import { mountBoard, attachFocalDrag, loadFont, enabledPlacements } from '@cliangdev/creative-render/mount';
```

- **`mountBoard(container, options) -> { ready, board, shell, update(next), destroy() }`**
  Mounts one placement of one creative into `container` at its true pixel
  size (e.g. 1080×1920), then visually scales it down with a CSS transform to
  fit whatever size `container` happens to be — auto-fit sizing always
  measures the board unscaled first, so the CSS transform never throws off
  `fitBoard`'s math. No network call happens beyond loading `photoUrl`, any
  logo URLs, and `brand.fontUrl` — everything else is local, so every
  keystroke re-renders live.

  ```ts
  mountBoard(container: HTMLElement, options: {
    creative: Creative;
    brand: Brand;
    placementKey: string;       // a key from the placements registry, e.g. '9x16'
    scale?: number;             // force a display scale instead of auto-fit-to-container
    sequenceIndex?: number;     // which story/carousel frame to show (0-based)
    placements?: object;        // registry override (default: placements.js)
    layouts?: object;           // registry override (default: layouts/index.js)
  })
  ```

  `update(next)` shallow-merges `next` onto the current options (so
  `update({ creative: patched })` alone re-renders with the same brand and
  placement) and re-fits. `destroy()` removes everything this call added to
  `container`.

  For a MOTION creative (`creative.motion` set), `mountBoard` also applies `motion.js`'s `applyMotion`
  at `t=0` right after the first `fitBoard`, so the preview opens on the animation's starting frame
  rather than its (visually different) finished-composition look; `update()` to a new creative resets
  the scrub position back to `0` the same way. The handle also gains:

  - **`seek(tSec) -> Promise<void>`** — applies motion at `tSec` (clamped to `[0, durationSec]`) to the
    CURRENT board without rebuilding it, and awaits the clip background's own `seek` (via
    `seekBackgroundVideo`) when one is present. A no-op for a STILL creative (no `creative.motion`) —
    STILL behavior is unchanged.
  - **`play()`** / **`pause()`** — real-time playback via `requestAnimationFrame`, looping back to `0`
    at `durationSec`. `play()` is a no-op for a STILL creative or before the board is ready.
  - **`onTime(cb) -> unsubscribe`** — `cb(tSec)` fires on every `seek()` (from `play()` or a manual
    scrub), for a scrub-bar UI to stay in sync.

  Audio is never mixed into this live preview (only the exported MP4 has it — see the render job
  below); a MOTION editor panel that lets someone audition a chosen library track does so separately.
- **`attachFocalDrag(handle, onChange)`** — wires a pointer-drag interaction
  on a `mountBoard()` handle; `onChange(value)` fires with an `"x% y%"`
  string as the pointer moves. This module never persists the value — decide
  whether it becomes `creative.focalOverride[placementKey]` or the photo's
  own `focal[placementKey]`, then call `handle.update({ creative: patched })`
  so the board reflects it immediately. Returns `{ detach() }`.
- **`loadFont(brand) -> Promise<void>`** — idempotently loads `brand.fontUrl`
  as a stylesheet `<link>`; resolves immediately if there is no `fontUrl`.
  `mountBoard` calls this itself; exported for a caller that wants to
  preload a font before the editor opens.

### `copy-rules.js` — pure copy checks, no DOM

```js
import {
  checkCopyRules, checkAccentPhrase, accentPhraseCount, checkCreativeCopy,
} from '@cliangdev/creative-render/copy-rules';
```

A brand kit's `copyRules` is data:

```ts
type CopyRule = {
  id: string;
  pattern: string;        // regex source, no literal slashes
  flags?: string;         // default 'i'
  message: string;        // shown verbatim on a violation
  fields: Array<'headline' | 'body' | 'caption'>;
  exceptPattern?: string; // matches of this are stripped from the text BEFORE `pattern` is tested
};
```

- **`checkCopyRules(rules, fields) -> Array<{id, field, message}>`** — runs
  every rule in `rules` against `fields` (`{ headline?, body?, caption? }`).
  Only the fields a rule lists are checked; `exceptPattern` matches are
  removed from the text first (this is how a kit can forbid a phrase
  everywhere except one exact, cleared string).
- **`accentPhraseCount(headline) -> number`** — counts complete `*phrase*`
  pairs in a headline.
- **`checkAccentPhrase(headline, required) -> errors`** — the one structural
  rule beyond regex data: when `required` is true, a headline must carry
  exactly one accent phrase (0 or 2+ both fail). No-op when `required` is
  falsy.
- **`checkCreativeCopy(kit, fields) -> errors`** — the combined check a
  save-time validator or a live-typing feedback panel runs: `checkAccentPhrase`
  (using `kit.accentPhraseRequired`) followed by `checkCopyRules(kit.copyRules, fields)`.

This is the module the frontend imports for live inline feedback as someone
types, and for the brand settings page's "test a line" box. The backend's
`CreativeValidator` ports the same semantics in Java for the save-time gate —
this file's tests (`test/copy-rules.test.mjs`) are the semantics of record;
keep the two in sync by matching a case here whenever the Java validator
changes.

### Registries

```js
import { placements } from '@cliangdev/creative-render/placements';
import { layouts, layoutNames } from '@cliangdev/creative-render/layouts/index.js';
```

`placements.json` / `placements.js` (identical data, kept in sync by
`test/registry.test.mjs`) hold the six artboard sizes: `9x16` (TikTok/Reels),
`4x5` (Instagram feed), `1x1` (Facebook feed) — the three defaults — plus
`story`, `1.91x1` (link ad) and `2x3` (Pinterest), opt-in. Each entry carries
pixel size, a platform label, the safe zone a layout must not intrude on, the
headline auto-fit bounds, badge height, and the per-placement scale numbers
`renderBoard` turns into CSS custom properties. `layouts/index.json` /
`layouts/index.js` register the four layouts (`stacked`, `bleed`, `card`,
`split`) the same way. Adding a placement or a layout is a data change: a new
key/folder plus a registry entry, no code change. The backend keeps a copy of
`placements.json` and `layouts/index.json` for its own validation; a sync
test on that side reads these files directly to make sure the two never
drift.

## The headless render job (`job/`)

Renders run **locally**: the Conductor CLI and MCP server (both part of `@cliangdev/conductor`,
running on the user's own machine — under Claude Code or Claude Desktop) drive this job with a
local Playwright/Chromium, talking to the backend over its ordinary external v2 API with the
user's own API key. There is no backend-launched Cloud Run render job and no render token; the
backend only records what a render produces (frames, warnings, state) and serves them back with
signed URLs. A Workflow can run the same job on a self-hosted runner by shelling out to the CLI
(`npx -y @cliangdev/conductor creative render ...`) — see
[`docs/workflows.md`](../docs/workflows.md)'s Creatives section in the main repo.

`render.js` and `mount.js` deliberately keep the "build one board" concern
(`resolveAd`/`resolveSequence`/`renderBoard`/`fitBoard`) separate from the
"live in a container" concern (`mountBoard`). The render job doesn't need a
container to scale into — it wants the true-pixel-size board, fitted, and a
screenshot of exactly that element. Nothing in `render.js` reaches for a Node
built-in or assumes a bundler; it only reaches for `document`, so it runs the
same way under Playwright as it does in the Next.js editor.

**Pages.** `frame.html` + `frame.js` render one placement (or one sequence
beat) at true size: `resolveAd`/`resolveSequence` → `renderBoard`, append,
await `document.fonts.ready` and every image's `decode()`, `fitBoard`, then
`assertions.js`'s `runAssertions`. `sheet.html` + `sheet.js` render a contact
sheet of every placement (or every sequence beat) scaled down, for a
`previewOnly` render — fits every board at true size first, exactly like
`mount.js`, then applies the display scale, so the two never disagree on
whether type fits. Both pages take their spec off `window.__RENDER_SPEC__`,
which the job sets via Playwright's `addInitScript()` before navigation —
never a query string or a `fetch`, so an arbitrarily large creative/brand
payload never hits a URL length limit. Both report
`window.__RENDER_RESULT = { ok, errors?, warnings?, width, height }` and set
`window.__ready = true` exactly once, success or failure, for the job's
`page.waitForFunction` to key off.

**MOTION.** When the spec carries `motion` (and `frame.html`'s `time`, default 0), `frame.js` applies
`motion.js`'s `applyMotion` at that time (and awaits the clip background's own seek) once, right after
`fitBoard`, BEFORE running any assertions — then, instead of finishing immediately, it exposes a small
driver API and reports `{ ok: true, width, height }` (whether the board itself *built*, not yet whether
it passes assertions):

```js
window.__seekMotion(t) -> Promise<true>                              // re-applies motion + re-seeks the clip
window.__assertBoard() -> Promise<{ errors, warnings, width, height }> // runs runAssertions on demand
```

This exists because a MOTION render needs many frames (8s at 30fps is 240) off of ONE page load — a
fresh navigation per frame would be far too slow — so `job/render.mjs` opens `frame.html` exactly once
per placement, then drives `__seekMotion` in a loop via repeated `page.evaluate()` calls against that
same page, calling `__assertBoard()` only once, on the very last (end-card) frame. `sheet.html`/
`sheet.js` similarly accept a `times: number[]` (typically `motion.js`'s `motionKeyTimes()`) for a
`previewOnly` MOTION render: one ROW per time, each row holding every enabled placement's board at that
moment, so a human can see how the animation progresses without downloading the full MP4.

**`assertions.js`** is the in-page safety net, ported from
`nexus-marketing/social/export-png.mjs` and made brand-agnostic: text spill,
bottom safe-zone intrusion (mirrors `render.js`'s own `fitBoard`/`fits()`
bottom check, so fitting and this check can never disagree), fonts loaded
(reads `brand.fontFamily` instead of a hardcoded face), every `<img>` loaded,
a bleed/card layout's CSS background photo probed directly (invisible to a
plain `<img>` check), the accent colour actually resolving on the headline's
`<em>` (reads the board's own `--cc-accent`, not a hardcoded brand color),
headline contrast ≥ 3:1, and the rendered box matching the placement's pixel
size. Its pure math (`relativeLuminance`, `parseRgb`, `contrastRatio`,
`spillDetect`, `safeZoneIntrusion`) has no DOM dependency and is unit-tested
directly with fixture rects/colors in `test/assertions.test.mjs`;
`runAssertions` itself needs a real browser and is exercised end-to-end by
`test/job.test.mjs`.

**`job/`** is a separate npm package (its own `package.json`) so `playwright`
is not a dependency of `conductor-creative/` itself — the frontend consumes
that package via a `file:` dependency and must stay dependency-free.

- `job/render.mjs` — the rendering core, `run({ transport, ffmpegPath?, fps?, ... })`: fetches
  the spec, serves this package's own files over `job/server.mjs`'s tiny
  static server, opens one Playwright page per placement (or per sequence
  beat, or the one contact-sheet page for `previewOnly`) at
  `deviceScaleFactor: 2`, screenshots the `.cc-board` element as JPEG
  (quality 92) or the `#sheet` element as PNG, and calls
  `transport.putFrame`/`complete`/`fail`. Exits 0 on success, 1 on
  any failure — a bad assertion, a load error, or a timeout on any network
  step. Run directly: `node job/render.mjs` with `CONDUCTOR_API_URL`,
  `CONDUCTOR_API_KEY`, `CONDUCTOR_PROJECT_ID` and `CREATIVE_ID` set
  (`PREVIEW_ONLY`, `RENDERER`, `WORKFLOW_RUN_ID`, `FFMPEG_PATH`, `FPS`
  optional). This standalone
  bootstrap is a convenience for running the job directly; `@cliangdev/conductor`'s
  CLI and MCP server call `run()` themselves with their own Playwright-Core
  browser discovery instead of shelling out to this file.

  **MOTION** (`creative.kind === 'MOTION'`, not `previewOnly`) takes a different path per placement,
  at `deviceScaleFactor: 1` (the placement's true pixel size — doubling a video's resolution for no
  playback benefit is not worth quadrupling the encode): one `frame.html` page load, then for
  `i` in `0..N-1` (`N = round(durationSec * fps)`, `fps` defaults to 30) it calls the page's
  `__seekMotion(i / fps)` driver and screenshots the board as JPEG (quality 90), streaming each frame
  into a spawned `ffmpeg` child process's stdin (`-f image2pipe -framerate fps -i -`). An optional
  audio input is downloaded to a temp file first (`creative.audio.source === 'clip'` reuses
  `creative.backgroundVideoUrl` itself, trimmed `-ss clipStartSec -t durationSec`;
  `'track'` uses `audio.trackUrl`, trimmed `-t durationSec` with `-af volume=…,afade=t=out:…` — a
  clip's own recorded sound is never volume/fade-adjusted, only a library track is). Output is
  `-c:v libx264 -pix_fmt yuv420p -crf 20 -preset veryfast -r fps [-c:a aac -b:a 128k -shortest]
  -movflags +faststart`, written to a temp file (faststart needs a seekable output, so never stdout)
  and read back for the upload. The layout assertions (`__assertBoard()`) run exactly ONCE, on the
  final (end-card) frame; a failure throws before any upload — the ffmpeg process is killed and
  nothing is written. On success, that same end-card JPEG becomes the poster:
  `transport.putFrame(key, { bytes: mp4, contentType: 'video/mp4', width, height, durationSeconds,
  hasAudio })` then `transport.putPoster(key, posterBytes)`. Progress logs roughly every 25%.
  `ffmpegPath` is required for a MOTION render — `run()` throws a clear, actionable error without it
  (a STILL/CLIP or `previewOnly` render never needs one). A `previewOnly` MOTION render is unaffected —
  it renders the normal `sheet.html` contact sheet, just with `times: motionKeyTimes(motion)` added to
  the spec (see the MOTION paragraph above).
- `job/transport.mjs` — the render core's ONLY knowledge of how it talks to
  the backend (`getSpec`/`putFrame`/`putPoster`/`complete`/`fail`), against today's
  external v2 `/projects/{projectId}/marketing/creatives/{creativeId}/renders`
  contract, authenticated with a plain API key (`Authorization: Bearer
  <apiKey>`) — the same key `conductor login`/a project API key already
  provides, no separate render token. `putFrame` sends `durationSeconds`/`hasAudio` query params
  when given (a MOTION upload); `putPoster(key, bytes, { index? })` PUTs a MOTION frame's poster JPEG
  to `.../frames/{key}/poster`. Both are upserts and retry once on a 5xx or a timeout. Deliberately
  isolated: how this job is
  launched and reports back is expected to keep evolving; when it does, only
  this file and `test/transport.test.mjs` change, never `render.mjs`'s
  rendering core or its own tests.
- `job/file-transport.mjs` — a transport that talks to no backend: `createFileTransport({ spec,
  localFiles, outDir })` renders a DRAFT spec (the backend's `draft-spec` response, which persists
  nothing) into a local folder. `getSpec()` swaps every `local:<key>` in the spec for a loopback URL
  served from a temp dir of links to `localFiles[key]`; `putFrame`/`putPoster` write `sheet.jpg`,
  `<placement>[-<index>].jpg|.mp4` and `poster-<placement>.jpg`; `complete`/`fail` write
  `manifest.json` (frames, sizes, warnings, or the error). It is how a Creative is previewed before
  anything is uploaded or saved. Tested in `test/file-transport.test.mjs`.
- `job/server.mjs` — a dependency-free `node:http` static server (ported from
  nexus-marketing's `social/server.mjs`) scoped to this package's root, so
  `frame.html`/`sheet.html`'s `type="module"` imports and stylesheet
  `<link>`s resolve (both are blocked under `file://`). Honours `Range` requests (a `<video>` cannot
  seek without them) and, with `{ cors: true }`, serves the file transport's local media.

**Running it from `@cliangdev/conductor`.** `conductor-tools`' build copies this
package's runtime files (everything above, minus `mount.js`, `copy-rules.js`,
tests and `job/package.json`/`node_modules`) into its own `dist/creative/` so
the published npm package needs no `file:` dependency on this sibling
directory. Its CLI (`conductor creative render <creativeId>`) and its MCP tool
(`render_creative`) both import `job/render.mjs`'s `run()` and
`job/transport.mjs`'s `createApiTransport()` from that copy, supplying their
own browser factory built on `playwright-core` (system Chrome, then system
Edge, then a Playwright-managed Chromium if one was installed with `npx
playwright install chromium`) — `job/package.json`'s own `playwright`
dependency (a full, browser-bundling install) is only for running this job
directly out of this repo, e.g. inside a Docker image on a self-hosted
Workflow runner.

**Tests.** `test/assertions.test.mjs` (pure math, no browser),
`test/transport.test.mjs` (the HTTP contract, against a fake `node:http`
backend, including `putPoster` and `putFrame`'s `durationSeconds`/`hasAudio`
params), `test/job.test.mjs` (the STILL/CLIP/sequence/previewOnly rendering
core end to end against a real
Chromium, with an in-memory fake transport — no HTTP, no coupling to the
current backend contract; covers single/multi-placement, a story sequence,
`previewOnly`, an unknown-placement failure with no frames uploaded, and a
spec-fetch failure; one test asserts a placement frame's actual JPEG (SOF
marker) pixel dimensions match the placement × 2, and another does the same
for the `sheet` contact sheet's PNG `IHDR` dimensions). These need a local
Chromium
(`cd conductor-creative/job && npx playwright install chromium`); every
Playwright-dependent test skips itself when one is not available rather than
failing a machine that never ran that install step.

`test/motion.test.mjs` covers `motion.js`'s pure math (every preset's element
states at representative key times, word-by-word staggering, the end-card
hold, `backgroundMotionState`, `motionKeyTimes`) and `seekBackgroundVideo`,
against `linkedom` — no browser needed. `test/mount.test.mjs` covers
`seek`/`play`/`pause`/`onTime` against `linkedom` with a hand-driven fake
`requestAnimationFrame` (records the scheduled callback; the test invokes it
with a chosen timestamp, so playback timing is deterministic rather than
racing a real animation frame). `test/render-dom.test.mjs` covers
`.cc-bg-video`'s placement/DOM order across all four layouts.
`test/job-motion.test.mjs` exercises the MOTION render path end to end
against a real Chromium AND a real local `ffmpeg`/`ffprobe` (skips itself
when either is missing): a 3s photo creative at 10fps for one placement
produces an MP4 whose `ffprobe` output shows `h264`, the placement's exact
pixel size, ~3s duration, and a JPEG poster upload; a `track` audio source
produces an MP4 with an AAC stream; a generated `testsrc` clip background
(`ffmpeg -f lavfi -i testsrc`) renders successfully; a deliberately broken
photo URL fails the end-card assertions specifically (not an early boot
failure) and uploads nothing; a MOTION render with no `ffmpegPath` fails with
a clear, actionable error. Fixture audio/video files are generated with
`ffmpeg -f lavfi` and served from a throwaway local `node:http` server (the
job's `fetch()`-based downloader has no `file://` support), never checked in.

## Intentional behavior changes from `nexus-marketing/social/`

- **Brand values are parameters, not constants.** `TRIAL_CLAIM`, the Apple
  badge path/ratio, the coral token, and the DM Sans self-hosted font are all
  gone; they are the `brand` object's job now.
- **The CTA badge is sized by height only**, preserving its own intrinsic
  aspect ratio, instead of Apple's specific badge ratio — a brand's badge is
  not assumed to be any one store's artwork. Clear space (a quarter of the
  badge's height) is kept as a reasonable general default.
- **The badge's `alt` text is empty** (decorative) rather than Apple's
  specific "Download on the App Store" wording, since the badge is now
  arbitrary brand artwork, not necessarily that one badge.
- **All CSS classes and custom properties are prefixed `cc-`** so the
  package's styles cannot collide with a host app's own Tailwind setup.
- **The `1.91x1` placement's label lost the word "Meta"** (now "1.91:1 link
  ad") since it renders for any link-ad surface, not only Meta's.
- **No photo/focal registry ships in this package.** nexus-marketing kept a
  separate `photos.json` mapping a photo path to its own focal points;
  here a creative simply carries `focal` (read from the backend's
  `creative_photo.focal`) and `focalOverride` directly, since photo storage
  and provenance are backend concerns (`creative_photo`), not this package's.
- **No golden-image test, no CLI, no MCP server, no photo pipeline** — all
  out of scope for this package (see the PRD; golden-image comparison and the
  Rexipe import are T6 work against the real render job).
- **Placement frames export as JPEG, not PNG** (`export-png.mjs`'s namesake
  format) — Instagram feed images and TikTok photo posts both refuse PNG, so
  a PNG frame would fail the publishing approval gate. Only the preview-only
  `sheet` contact sheet, never attached to a Post, still exports PNG.
