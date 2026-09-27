# Creatives

How Conductor turns a brand kit and a photo into upload-ready frames on a Post — a port of
`nexus-marketing/social/`'s concept → render → upload flow, kept brand-agnostic (COND-24).

## Brand Kits

A **Brand Kit** (`brand_kit`, `com.conductor.creative.BrandKit`) is per-project brand truth: colour
tokens, font, logo/wordmark/badge object paths, a CTA claim, copy rules (regex + message + fields +
exception pattern), an accent-phrase requirement, approved lines and the enabled placements a Creative
renders at by default. A project may hold several kits; exactly one is `isDefault`. Nothing brand-specific
ever lives in product code — every rule a Creative is checked against is data on the kit it renders with.

## Creatives

A **Creative** (`creative`, `com.conductor.creative.Creative`) is a library object under the Marketing
Area — not a Work Item. It carries a photo, headline, body, caption, alt text, a layout and theme, an
optional story/carousel `sequence`, and opt-in `placements` beyond the kit's defaults. `number` +
`variantLetter` form nexus's display id (`12a`, `12b`); `version` is JPA's optimistic-lock column, giving
a 409 on a stale write exactly like nexus's sha1 store version did.

`lockup` (`plain`, the default, or `chip` — the logo lockup on a white pill, for busy photography) and
`layoutOverrides` (`{band?, padBottom?}`, each a per-placement pixel override of the stacked layout's
photo band height / the 9x16 panel's bottom safe-zone clearance) are the two nexus per-concept settings
this port initially dropped; `CreativeValidator` checks every `layoutOverrides` placement key against the
registry and a sane pixel bound, and `conductor-creative/render.js` applies them exactly like nexus's
`render.js` did.

`CreativeValidator` (`com.conductor.creative.CreativeValidator`) is a data-driven port of nexus's
`validate()`/`copyErrors()`: layout/placement keys must be real, the layout must support the requested
theme, the kit's accent-phrase and copy rules apply to every text field (including each sequence beat),
sequence bounds are enforced (a story is 2–7 beats, a carousel 2–10 cards, every beat after the first
needs its own headline), and every referenced photo must exist in the project. A separate, non-blocking
**readiness** read (`CreativeService.readiness`) checks caption, alt text, the photo's checked/provenance
verdicts and AI disclosure — useful for a pre-publish checklist, but never a reason to refuse a write.

Photos (`creative_photo`) carry provenance (source, licence, AI disclosure), a checked/blocked verdict and
per-placement focal points, and go through the same mint → signed PUT → confirm upload shape as a Post's
own file Assets — except a Creative photo has no owning Work Item, so it gets its own storage-backed row.

The placement/layout registry (`CreativeRegistry`, `creative/registry.json`) is the single source of truth
for which layout and placement keys a Creative may use: six placements (`9x16`, `4x5`, `1x1`, `story`,
`1.91x1`, `2x3`) today, four layouts. Adding one is a registry change, never a code change to the
validator or the render mapping below.

## Rendering — local, not a backend job

**Rendering happens on the user's own machine**, never in this backend. A Claude Code (or Claude Desktop)
session runs the render locally through the Conductor MCP server's `render_creative` tool or the CLI's
`conductor creative render <creativeId>`, using Playwright against an installed Chrome/Chromium and the
shared `conductor-creative/job` render core (brand-agnostic port of `nexus-marketing/social/render.js`).
The web editor's live preview needs no render at all — it re-renders in the browser on every keystroke.

The backend's role is narrow: it hands the local job a **render spec** (the resolved creative + brand
data it needs, with signed photo/logo URLs), and it records what the job did.

1. `POST .../creatives/{creativeId}/renders` creates a `creative_render` row (`RUNNING`) and returns the
   spec — the *only* place `spec` is ever returned. Refuses with 422 (the same shape as a Creative write)
   if the Creative lacks a photo or headline, or fails any T2 structural rule. The resolved `placements`
   the job must produce one frame per are the kit's enabled set unioned with the Creative's own opt-ins,
   intersected with the registry — except a story Creative, which renders only `["story"]`, and a
   carousel, which renders only `[carouselRatio]` with one indexed frame per beat.
2. The job `PUT`s each placement's frame to `.../renders/{renderId}/frames/{placementKey}` as it finishes
   — JPEG (`image/jpeg`, quality 92), since Instagram feed images and TikTok photo posts both refuse PNG
   (`MediaTargetValidator`'s AC-P0-3.3 gate would otherwise block the Post at approval). `sheet` names a
   preview-only contact sheet and stays PNG (`image/png`) — nothing downstream gates it, and PNG keeps a
   text-heavy grid crisp. The endpoint accepts either content type, 415 on anything else; the stored
   frame's extension and `content_type` match what was sent. Only accepted while the render is
   `RUNNING` — 409 once it has settled.
3. `POST .../renders/{renderId}/complete` or `.../fail` settles it. A failure **deletes every frame
   already written** — a failed render leaves nothing for attach to find.
4. There is no scheduler watching a render. A `RUNNING` row a dead local job never finished is only
   noticed — and flipped to `FAILED` "timed out" — the next time anyone reads it (a render older than 30
   minutes on a `GET`).

A self-hosted Workflow can run the identical CLI on the Docker-step daemon
(`mcr.microsoft.com/playwright:v1.63.0-noble`, `--renderer workflow --workflow-run-id ...`) when a project
wants renders produced on a schedule rather than by hand — same endpoints, same contract, just a different
caller.

`CreativeResponse.latestRender` (the last `SUCCEEDED`, non-preview render, with frames) appears on a
single-Creative `GET`; list reads carry only `latestRenderId` and `latestRenderThumbnailUrl` (the `4x5`
frame, or the first one), so a grid of creatives stays cheap.

## Attach — "Use in Post"

`POST .../creatives/{creativeId}/attach` copies a `SUCCEEDED` render's frames into a Post's own Assets and
sets each inheriting destination's media by placement:

| Placement | Goes to |
|---|---|
| `9x16` | TikTok (its only format), and an Instagram target in `REEL` format |
| `4x5` | an Instagram target in `FEED` format |
| `1x1` | a Facebook target in `FEED` format |
| `story` | any target in `STORY` format |

This mapping is one small, independently tested class, `CreativePlacementTargetMapper` — pure and
stateless, so a new placement or platform is a switch arm, not a service rewrite. A target the mapping has
no opinion about (a Facebook or Instagram Reel, since reels are video and a Creative's frames are always
still images) is left alone.

Only targets with `custom_media = false` are touched; a target that already chose its own media is
reported in `targetsSkipped` and never overwritten. Attach reconstructs the Post's *whole* target
selection and pushes it through `PublishTargetService#replaceSelection` — the same set-replace a person's
own edit in the UI goes through — so the approval gate reverts and the bundle hash changes exactly as if a
human had picked that media by hand. Sequence frames (a carousel's beats) attach in the order they
rendered, keyed by `sequenceIndex`; they are never re-sorted, because order is content.

## What this backend does not do

No image processing beyond the screenshot the local job takes: Playwright's own JPEG/PNG encoder does the
only "re-encoding" that happens, and there is no server-side cropping and no Cloud Run render job. Every
render is a well-known local (or self-hosted-Workflow) job PUTing frames it already produced; this
backend's job is bookkeeping, storage and the attach mapping above.
