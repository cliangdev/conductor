# Creatives

How Conductor turns a brand kit and a photo into upload-ready frames on a Post — a port of
`nexus-marketing/social/`'s concept → render → upload flow, kept brand-agnostic (COND-24).

## Brand Kits

A **Brand Kit** (`brand_kit`, `com.conductor.creative.BrandKit`) is per-project brand truth: colour
tokens, font, logo/wordmark/badge object paths, a CTA claim, copy rules (regex + message + fields +
exception pattern), an accent-phrase requirement, approved lines and the enabled placements a Creative
renders at by default. A project may hold several kits; exactly one is `isDefault`. Nothing brand-specific
ever lives in product code — every rule a Creative is checked against is data on the kit it renders with.
A kit's accent phrase always renders in italic, so its `fontUrl` must include that font's italic face
(for Google Fonts, the `ital` axis, e.g. `family=Poppins:ital,wght@0,400;0,800;1,800`) — without it the
browser fakes the slant and the space after the accent phrase visually closes up.

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

## Draft, approve, commit — nothing is saved before a person has looked

Creating a Creative used to write a row, and upload its photos, before anyone saw an image. Every
Claude-driven flow is now **draft → look → approve → commit**, and the backend writes nothing until the
last step. Two MCP tools (`conductor-tools/src/mcp/tools/creative-drafts.ts`) carry it:

- **`preview_creative_draft`** takes the `create_creative` fields plus local files (`photoPath`,
  `clipPath`, `audioPath`, a story/carousel beat's own `photoPath`), an optional `baseCreativeId` (preview
  edits on top of a saved Creative) and `full` (every placement at full size instead of one contact
  sheet). It reads each local file's metadata the way `upload_creative_media` does, then calls
  `POST .../creatives/draft-spec` — the create body plus `previewOnly`, `baseCreativeId` and
  `localMedia` (`{ key: { kind, width, height, durationSeconds, hasAudio } }`). A local file travels as the
  media id `local:<key>` (`photo`, `clip`, `audio`, `beat-<n>`; keys match `^[a-z0-9][a-z0-9-]{0,63}$`).
  The endpoint runs the same validators as a create, persists nothing, and returns `{ spec, readiness }`
  (or a 422 naming the field, which the tool returns as a structured error without rendering). The spec
  is the shape `getSpec()` returns, with `local:<key>` left in place of every local media URL.
  `typeOverrides` (`{"<placementKey>": [fontSize, lineHeight?, letterSpacing?]}`) is accepted on create,
  update and draft-spec alike, validated the same way (real placement keys, sane numbers; else a 422).
  A contact-sheet preview also returns `checks` and `passed`: the sheet never runs the render's
  assertions, so `run({ checkPlacements: true })` renders each placement's `frame.html` in the same browser
  (a MOTION creative at its end card), keeps only the findings and drops the frame bytes. They are written
  to `manifest.checks` / `manifest.passed`; an `error` severity is exactly what the full render would fail
  on (e.g. `safeZone`), so the agent fixes the design and re-previews before asking for approval.
- **`commit_creative_draft`** runs only after the person approved the image: it uploads each local file
  (the `upload_creative_media` code path), swaps `local:<key>` for the returned ids, calls
  `create_creative` (or `update_creative` at the current `version` when the draft has a
  `baseCreativeId`), then runs the full local render. It refuses a draft that is already committed.

The local render uses `conductor-creative/job/file-transport.mjs`, a fourth transport beside the API one:
`run()` is unchanged. `getSpec()` rewrites each `local:<key>` to a loopback URL served by a small
Range-capable server (`job/server.mjs`) rooted at a temp dir holding links to the files — so the headless
browser and ffmpeg can load them — and `putFrame`/`putPoster`/`complete` write `sheet.jpg`,
`<placement>[-<index>].jpg|.mp4`, `poster-<placement>.jpg` and `manifest.json` into the draft folder.

Drafts live in `<project>/.conductor/drafts/<YYYYMMDD-HHMMSS>-<slug>/` (with a `.gitignore` that ignores
them) beside a `draft.json`: the request fields, the local-file map, `baseCreativeId`, a sha256 of the
spec, and `committed`. Both tools prune that folder (and only that folder) of drafts that are committed
or older than 14 days. A CLIP Creative has nothing to render, so it has no draft preview; a headline
variant is previewed with `baseCreativeId` but saved with `create_creative` and `variantOf`, never
committed.

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

## Video (COND-24 PR1)

The media library (`creative_photo` — the table/entity name stays, the API calls it "media") now takes
video and audio alongside photos: `mediaKind` is `IMAGE` (the original photo library), `VIDEO` or `AUDIO`.
A `VIDEO` also carries `durationSeconds` (required), an optional `hasAudio`, an optional codec hint, and an
optional JPEG poster — minted through its own `POST .../photos/{photoId}/poster` (signed PUT) →
`POST .../poster/confirm` pair, exactly like the photo's own mint → PUT → confirm shape. `width`/`height`
are required for `IMAGE`/`VIDEO` and unused for `AUDIO`. Size ceilings are per kind (100 MB image, 1 GB
video, 50 MB audio); a video longer than 180 seconds warns ("longer than most platforms take") but never
refuses — there is no length cap here, platform caps are enforced at publish time by
`MediaTargetValidator`.

A Creative's `kind` — `STILL` (default, everything above), `MOTION` or `CLIP` — picks what it is. `STILL`
is unchanged. `MOTION` (a branded animated video) is accepted by the write API today, but a render for one
currently refuses with 422 ("Motion creatives render in the next release") — PR2 fills this in. `CLIP` is a
finished video used as-is, with no brand layout at all: `clipMedia` is a map of `{"default": mediaId,
"<placementKey>": mediaId, ...}`, where `default` covers every placement the map doesn't otherwise name.
Every referenced id must be a `VIDEO` in the media library that has finished uploading, is not blocked, and
belongs to the same project — checked on every write, the same way a STILL Creative's `photoId` is. A CLIP
needs no layout, theme, headline or photo; going `READY` needs a caption and at least one clip instead of a
headline and a photo.

**CLIP renders are assembled by this backend, not by a local job.** `POST .../creatives/{id}/renders` for a
CLIP creative settles `SUCCEEDED` in the same call: one frame per placement the clip set covers. An
explicit `clipMedia` key (other than `default`) is that frame's own placement; `default`'s media resolves
to whichever registry placement's aspect ratio is closest to its own — among every registry placement, a
story's aspect counts as `9x16` (so a 9:16 default lands on `9x16`, not `story`) — and only fills that
placement in when nothing more specific already claims it. Each frame is a server-side `StorageService.copy`
of the source media (and its poster, if it has one) into the render's own path; `spec` is always null for a
CLIP response, since there is no local job to hand a spec to. `previewOnly` is refused with 422 — there is
no contact sheet to assemble from a single finished file. A frame carries `durationSeconds`/`hasAudio`/
`posterUrl`/`contentType` alongside the usual width/height/size — null for a STILL frame, set for a CLIP
one.

**Attach is video-aware.** `CreativePlacementTargetMapper#matchesVideo` extends the STILL mapping: a
`9x16` video also fills TikTok (its one format), an Instagram or Facebook reel, YouTube when the set has no
`16x9` cut (it goes out as a Short), Facebook's feed when there is no `1x1` cut (Facebook publishes a lone Page
video as a Reel), and any story-format target left unclaimed by a dedicated `story` frame; a `16x9` video
fills YouTube, and Facebook's feed only when the set has neither a `1x1` nor a `9x16` cut. `CreativeAttachService`
copies the video bytes into the Post's own asset prefix and records `durationSeconds` on the resulting
Asset (via `AssetService.StoredObjectInput`), so `MediaTargetValidator`'s reel/story/TikTok duration rules
run against it exactly as they would a directly-uploaded video.

The `16x9` placement (1920×1080, YouTube and landscape video) was added to the registry for this — it is
not in any Brand Kit's default enabled set, since it means nothing to a STILL Creative's brand layout.

## Motion (COND-24 PR2)

`MOTION` is a branded animated video: the same copy and look as a STILL Creative (layout, theme, headline
with an accent phrase, body, `photoId`, lockup, `layoutOverrides`, placements opt-ins), plus two JSON
columns nothing else touches, `creative.motion` and `creative.audio`.

`motion` is `{preset, durationSec, background: {source, motion, clipMediaId?, clipStartSec?}, endCard}` —
`preset` (`fade-up`/`word-by-word`/`accent-pop`/`none`) and `background.source`/`background.motion` are
plain strings, not OpenAPI enums, so an invalid value comes back from `CreativeValidator` as a 422 with a
field path like `motion.preset`, the same shape every other Creative violation uses, rather than a generic
400 from Jackson rejecting an unknown enum literal. A `photo` background reuses the Creative's own
`photoId`; a `clip` background points `clipMediaId` at a `VIDEO` in the media library (uploaded, unblocked,
same project, `clipStartSec` less than the clip's own duration). `audio` is
`{source: clip|track|none, trackId?, volume?, fadeOutSec?}` — `track` needs an `AUDIO` media id; `clip`
needs a `clip` background whose media actually has an audio track (`hasAudio=true`).

**Defaults are applied on every write**, by `CreativeService#resolveMotion` — never left for the client to
supply or the renderer to guess: `preset` "fade-up", `durationSec` 8, `background.source` "photo",
`background.motion` "zoom-in" (photo backgrounds only), `endCard` true, and `audio.source` "clip" when the
resolved background is a clip whose media has audio, else "none" (`audio.volume` 0.8, `audio.fadeOutSec` 1).
A `PATCH` that omits `motion`/`audio` keeps the Creative's current (already-defaulted) value, exactly like
every other field patch semantics here — whole-field replace, not a deep merge.

**READY-state rules mirror STILL's, conditionally**: a headline is always required; a photo is required
only when `background.source` is `"photo"` (the default) — a `clip` background needs no `photoId` at all,
since the video itself is the background.

**Rendering is a local job, exactly like STILL** — `POST .../renders` no longer refuses MOTION with 422;
the render spec (`CreativeRenderSpecCreative`) gains `kind`, `motion` (with `background.clipUrl`, a signed
GET of the background clip, only when the background is a clip), `audio` (with `trackUrl`, a signed GET,
only when the source is a track) and `clipHasAudio` (whether the resolved background clip's media has an
audio track — null when the background is a photo). `previewOnly` is allowed for MOTION (a contact-sheet
preview of key animation moments), unlike CLIP, which has no local job to preview.

**The frame PUT accepts `video/mp4`** (`durationSeconds`/`hasAudio` query params, stored on the frame — at
most 500 MB, well above a STILL/`sheet` frame's 20 MB) alongside the existing JPEG/PNG. A second endpoint,
`PUT .../renders/{renderId}/frames/{placementKey}/poster`, uploads the frame's poster JPEG (the end-card
frame) once the frame itself is stored — same 20 MB ceiling as an image frame, only accepted while the
render is `RUNNING` (409 once it has settled), refused with a plain 400 if no frame is stored yet for that
placement/index to attach a poster to. A frame's `posterUrl` is populated either this way (MOTION) or by
copying the source media's own poster (CLIP) — the response shape does not distinguish which.

**Attach treats a MOTION frame exactly like a CLIP one** — `CreativeAttachService`'s video/image branch
keys off the frame's own `content_type` (`video/` prefix), not the Creative's `kind`, so
`CreativePlacementTargetMapper#matchesVideo` needed no changes at all: a MOTION render's `9x16` frame fills
TikTok and an Instagram/Facebook reel exactly as a CLIP's would.

**Readiness for MOTION** is STILL's photo-verdict checklist when the background is a photo, or the
background clip's own source/licence when it is a clip (`clipProvenance`); plus the audio track's own
source/licence when `audio.source` is `"track"` (`audioProvenance`) — a licensed music track needs the same
provenance discipline as a photo or a clip. Alt text is advisory (non-blocking) for MOTION, like CLIP,
since both are video.

## What this backend does not do

No image processing beyond the screenshot the local job takes: Playwright's own JPEG/PNG encoder does the
only "re-encoding" that happens, and there is no server-side cropping and no Cloud Run render job. Every
render is a well-known local (or self-hosted-Workflow) job PUTing frames it already produced; this
backend's job is bookkeeping, storage and the attach mapping above.

## Performance and experiments (COND-24 T5)

**Attribution** (`CreativeAttributionResolver`) answers "which published destinations actually sent this
Creative": `creative_render_frame` → `assets.creative_frame_id` → the owning Post's publish targets,
resolved through the same `PublishTargetMediaResolver` the approval gate uses — an inheriting target
counts only while the frame's asset is still part of the Post's shared media, and a target with its own
explicit selection counts only while that frame's asset is among it. A frame later replaced on its Post
stops counting from that point on; nothing is backfilled.

`CreativePerformanceService` rolls this up per variant: posts, views, engagement rate
`(likes+comments+shares+saves)/views`, average view percentage where a platform reports it (YouTube), and
`views72h` — the *snapshot nearest at or after `fireTime + 72h`*, deliberately a different read of the
metric history than `views`/`engagementRate`/`avgViewPct`, which come from each destination's absolute
latest snapshot. `GET .../marketing/creatives/{creativeId}/performance` returns the whole family (every
lettered sibling); `GET .../marketing/insights` carries the top ten variants by views inside its window as
`creatives`, the same way it carries top/bottom posts.

**`creative_experiment`** (V143) is a hook experiment: two or more variants of one family, each published
as its own Post, decided by `CreativeExperimentService`. `create` needs the family to have at least two
variants and refuses a second `RUNNING` experiment on the same family with 409 (`uq_creative_experiment_
running_per_family`). `decide` — called on demand via `POST .../experiments/{id}/decide`, or by the
`conductor-marketing` weekly feed pull for every `RUNNING` experiment in the project — settles once every
variant has at least one published, attributed destination with a snapshot at or after `fireTime +
windowHours` (default 72): the comparison metric is `avg_view_pct` when every variant reports it, else the
metric requested at creation (default `views`); an exact tie among the leaders settles `INCONCLUSIVE`
immediately (the window has, by definition, already fully elapsed for everyone at that point). While any
variant is still short of its own deadline, `decide` is a safe no-op that leaves the experiment `RUNNING`
— unless the last outstanding variant's deadline (its own `fireTime + windowHours`, or the experiment's
`createdAt + windowHours` for a variant never published at all) is more than seven days past, in which case
the experiment gives up as `INCONCLUSIVE` rather than wait forever. `summary` records the per-variant
numbers used (or why a decision couldn't be made) and is never recomputed after `decidedAt` is set.

**Confirming a winner** is the one human action in this loop: `POST .../experiments/{id}/confirm-winner`
appends the winning variant's headline to its family's Brand Kit `approved_lines` (deduplicated) and
stamps `winnerLineConfirmedAt`/`By`. It requires ADMIN or CREATOR, requires a `DECIDED` experiment with a
winner, and is idempotent — `decide` never calls it, and calling it twice never appends the line twice. No
experiment ever changes brand copy on its own.

The weekly `conductor-marketing` digest (`docs/knowledge.md`'s "Metrics digests") gets a **Hook winners**
section: `MarketingInsightsConnector.fetchData` decides every `RUNNING` experiment for the project, then
reads every experiment `DECIDED` since the start of that pull's trailing window and adds it to the raw
payload as `hookWinners` (creative, headline, metric, per-variant numbers). `DigestPayloadBuilder` copies
that key onto the narrator's payload verbatim when non-empty — the one narrow exception to "the narrator
never sees raw numbers", since a hook winner is already a finished decision, not a raw series — and
`MetricsDigestService` treats a non-empty `hookWinners` as material on its own, so a decided experiment is
narrated even on an otherwise-flat week.
