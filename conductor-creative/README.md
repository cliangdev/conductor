# @cliangdev/creative-render

The render engine behind Conductor Creatives: the same code draws a live
preview in the web editor and (in a later tranche, T3) drives a headless
Playwright job that produces upload-ready PNGs. One codebase, so the preview
a marketer edits against and the frame that gets attached to a Post can never
drift apart.

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
  headline: string;      // exactly one *accent phrase* marked with asterisks
  body?: string;
  caption?: string;      // carried through, never rendered onto the board
  photoUrl?: string;     // an absolute URL (signed GCS URL, blob:, or data:)
  focal?: Record<string, string>;         // per-placement "x% y%", from the photo
  focalOverride?: Record<string, string>; // per-placement "x% y%", wins over focal
  placements?: string[]; // extra placement keys this creative opts into,
                          // beyond the brand kit's enabledPlacements
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
  default (layout, theme, focal, band, padBottom, type) so the rest of the
  code can assume a complete ad. `placements`/`layouts` are the registries
  (see below); pass the shipped ones or your own.
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
  guide (never for an export).
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

## For T3 (the headless render job)

`render.js` and `mount.js` deliberately keep the "build one board" concern
(`resolveAd`/`resolveSequence`/`renderBoard`/`fitBoard`) separate from the
"live in a container" concern (`mountBoard`). A Playwright job doesn't need a
container to scale into — it wants the true-pixel-size board, fitted, and
then a screenshot of exactly that element. The job (not built in this
tranche) is expected to:

1. Serve a minimal HTML page that imports `styles.css`, this package's
   `render.js`, and the creative + brand JSON for one render.
2. Call `resolveAd`/`resolveSequence` + `renderBoard` directly (skip
   `mount.js`'s container-scaling entirely — the job wants the board at its
   real pixel dimensions, unscaled).
3. Append the board, wait for `document.fonts.ready` and every image's
   `decode()`, call `fitBoard`, then screenshot the `.cc-board` element.
4. Run its own in-page assertions (spill, safe-zone intrusion, contrast,
   fonts loaded, background photo loaded) before accepting the frame — ported
   from nexus-marketing's `export-png.mjs`, not part of this package.

Nothing in `render.js` reaches for a Node built-in or assumes a bundler; it
only reaches for `document`, so it runs the same way under Playwright as it
does in the Next.js editor.

## Intentional behavior changes from `nexus-marketing/social/`

- **Brand values are parameters, not constants.** `TRIAL_CLAIM`, the Apple
  badge path/ratio, the coral token, and the DM Sans self-hosted font are all
  gone; they are the `brand` object's job now.
- **The lockup's "chip" variant (a white pill behind a busy photo's logo) was
  dropped.** It was not part of the creative shape this port targets (the
  backend's `creative` entity has no such field); a future tranche can add it
  back as brand or layout data if it turns out to be needed generally.
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
