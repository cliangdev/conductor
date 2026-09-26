# import-nexus-social.mjs

One-off migration script for COND-24 T6. Reads `nexus-marketing/social/` (`brand.json`,
`photos.json`, `ads.json` + `assets/`) and recreates it in a Conductor workspace as a Brand
Kit, a set of Creative photos, and a set of Creatives (with lettered variants and
story/carousel sequences). Not product code — run it once, verify, then archive the source.

## What it does

1. **Brand Kit** (`--kit-slug`, default `default`): maps `brand.json`'s nine CSS tokens to the
   eight the kit has a field for (`--sky` has no destination and is dropped, with a note), sets
   `fontFamily: "DM Sans"` (no `fontUrl` — nexus ships DM Sans as local `.woff2` files, not a
   hostable URL), `ctaClaim` from `copy.trialClaim`, `accentPhraseRequired: true`, the six
   `copyErrors` checks from `core.mjs` as data-driven copy rules (with the "Add All to Shopping
   List" exception), `approvedLines` from `copy.trialClaim`/`priceDisclosure`/`autoRenew`, and
   `enabledPlacements: ["9x16", "4x5", "1x1"]` (nexus's three always-on placements). Uploads the
   app icon (`mark`), both wordmark colours (`wordmark_dark`/`wordmark_light` — note nexus's
   dark-background artboards use the *light-coloured* wordmark file and vice versa, so the
   mapping is intentionally crossed), and the black App Store badge (nexus always uses the black
   variant, regardless of theme).
2. **Photos** (`photos.json`): one Creative photo per entry, dimensions read directly from the
   image file's own header bytes (PNG/WebP VP8, VP8L, VP8X/JPEG — photos.json itself has no
   width/height field), plus `source`, `licence`, `aiGenerated`, `checked`, `blocked` and
   `focal`. Reused across runs by matching on `label` (the nexus path).
3. **Creatives** (`ads.json`): one Creative per concept id. A root (`a`) is created directly;
   a lettered sibling (`b`, `c`, ...) is cut with `POST .../variants` off the root and then
   patched with its own real values, since a nexus variant can differ on photo/layout/theme, not
   just headline. `story`/`carousel` map to `sequenceKind`/`sequence`; `type` overrides can only
   be set via a follow-up PATCH (the create endpoint has no `typeOverrides` field); a `draft`
   concept becomes `state: DRAFT`, everything else `READY`. Reused across runs by matching on
   `name`.

## Running it

```bash
node scripts/import-nexus-social.mjs \
  --api-url https://your-conductor-api \
  --api-key <api key with ADMIN/CREATOR on the project> \
  --project <projectId> \
  --source ~/repos/nexus/nexus-marketing/social \
  [--kit-slug default] [--kit-name Rexipe] [--render] [--out report.json]
```

`--api-url`/`--api-key` fall back to `CONDUCTOR_API_URL`/`CONDUCTOR_API_KEY`. Always dry-run
first:

```bash
node scripts/import-nexus-social.mjs --dry-run --project <projectId> --source ~/repos/nexus/nexus-marketing/social
```

`--dry-run` prints every planned request and touches no network at all (works with no server
running, no `--api-url`/`--api-key` needed). It prints a summary (photo/creative/variant counts)
and, at the end, the full nexus-id -> Conductor-creative-id mapping, plus a "did not map
cleanly" notes list — read that list before treating the import as done.

`--render`, after import, runs `npx @cliangdev/conductor creative render <id>` for every
creative in the mapping (or just prints the commands under `--dry-run`).

## Verifying against `social/golden/`

The nexus repo's `social/golden/` holds a handful of previously-exported PNGs. After a real
import + render, open the rendered frames for those same concept ids in the Conductor Creative
editor (or the render output) side by side with the golden PNGs and eyeball them — same photo
crop, same headline placement and size, same lockup position, same CTA row. This is a by-eye
check, not a pixel diff: known gaps from this import (see the mapping report's notes) are the
per-concept `band`/`padBottom` panel-height overrides and the `lockup: "chip"` choice, neither of
which the current Creative model has a field for, so a handful of concepts will render with the
model's default panel height and the plain lockup instead of the chip. If a golden concept looks
materially off beyond that, stop and check the mapping before importing the rest.

## Archiving `nexus-marketing/social/`

Once the import is verified, archive the source **in the nexus repo** (this script never
touches it): add a short `README.md` to `nexus-marketing/social/` pointing at the Conductor
workspace and this script, and move or tag the directory however that repo prefers (e.g. a
`social-archived/` rename, or a tag + deletion in a follow-up PR). This step is manual and
outside this script's scope.

The Knowledge page `marketing/brand.md` for the Rexcipe workspace is seeded separately, by hand,
quoting (never paraphrasing) `nexus-landing`'s marketing context doc — this script does not
touch the Knowledge Center.

## Tests

```bash
node --test scripts/import-nexus-social.test.mjs
```

Runs against a fake `node:http` server and the fixtures under `scripts/fixtures/nexus-social/`
(tiny generated PNGs — see `scripts/fixtures/make-fixture-pngs.mjs`). Covers the request
sequence (brand kit before photos, variant `POST .../variants` before its `PATCH`), the mapping
(variant cut with differing fields, story sequence -> beats, `draft` -> `DRAFT`), dry-run
touching no network, and idempotent re-runs (skip by photo label / creative name).
