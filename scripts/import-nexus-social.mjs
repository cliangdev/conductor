#!/usr/bin/env node
/* import-nexus-social.mjs — one-off import of nexus-marketing/social/ into a Conductor
 * workspace's Brand Kit + Creative library (COND-24 T6).
 *
 * This is NOT product code — it is a throwaway migration script that reads the Rexipe ad
 * set (brand.json, photos.json, ads.json + the assets/ images) and calls the Conductor v2
 * REST API to recreate it as a Brand Kit, a set of Creative photos, and a set of Creatives
 * (with lettered variants and story/carousel sequences). Once it has run for real and been
 * eyeballed against social/golden/, nexus-marketing/social/ is archived by hand (see
 * scripts/README-import-nexus-social.md — that step is manual, in the nexus repo, and this
 * script never touches it).
 *
 * Usage:
 *   node scripts/import-nexus-social.mjs \
 *     --api-url https://api.example.com --api-key ck_xxx --project <projectId> \
 *     --source ~/repos/nexus/nexus-marketing/social [--kit-slug default] [--dry-run] [--render]
 *
 * --api-url / --api-key fall back to the CONDUCTOR_API_URL / CONDUCTOR_API_KEY env vars.
 * --dry-run prints every planned request (method, path, body) and does not touch the network
 * at all — no GETs either, so it works with no server running. It is also the only mode this
 * script has ever been run in against a real Conductor deployment; the live run is left to the
 * person operating it.
 *
 * Idempotent-ish on a real server: photos are matched and reused by `label` (the nexus photo
 * path); creatives are matched and skipped by `name`. Re-running after a partial import should
 * not create duplicates, though a half-created variant family (root created, variant failed)
 * needs the mapping report checked by hand.
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';

const HERE = dirname(fileURLToPath(import.meta.url));

/* ── CLI args ─────────────────────────────────────────────────────────────────────────── */

function parseArgs(argv) {
  const args = {
    apiUrl: process.env.CONDUCTOR_API_URL || null,
    apiKey: process.env.CONDUCTOR_API_KEY || null,
    project: null,
    source: null,
    kitSlug: 'default',
    kitName: 'Rexipe',
    dryRun: false,
    render: false,
    out: null,
  };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    const next = () => argv[++i];
    switch (a) {
      case '--api-url': args.apiUrl = next(); break;
      case '--api-key': args.apiKey = next(); break;
      case '--project': args.project = next(); break;
      case '--source': args.source = next(); break;
      case '--kit-slug': args.kitSlug = next(); break;
      case '--kit-name': args.kitName = next(); break;
      case '--dry-run': args.dryRun = true; break;
      case '--render': args.render = true; break;
      case '--out': args.out = next(); break;
      case '-h':
      case '--help':
        args.help = true;
        break;
      default:
        throw new Error(`Unknown argument: ${a}`);
    }
  }
  return args;
}

const USAGE = `Usage: node scripts/import-nexus-social.mjs --project <id> --source <path> [options]

  --api-url <url>     Conductor API base URL (or CONDUCTOR_API_URL)
  --api-key <key>     Conductor API key (or CONDUCTOR_API_KEY)
  --project <id>      Conductor project (workspace) id
  --source <path>     Path to nexus-marketing/social/
  --kit-slug <slug>   Brand Kit slug to create/update (default: "default")
  --kit-name <name>   Brand Kit display name (default: "Rexipe")
  --dry-run           Print every planned request; touch no network at all
  --render            After import, run "conductor creative render <id>" per creative
  --out <path>        Write the mapping report JSON here (default: stdout only)
`;

/* ── Image header dimension reader (no deps: PNG + WebP VP8/VP8L/VP8X, basic JPEG) ──────── */

export function readImageSize(buf) {
  // PNG: 8-byte signature, then IHDR chunk with width/height as big-endian uint32s.
  if (
    buf.length >= 24 &&
    buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4e && buf[3] === 0x47 &&
    buf.toString('ascii', 12, 16) === 'IHDR'
  ) {
    return { width: buf.readUInt32BE(16), height: buf.readUInt32BE(20) };
  }

  // WebP: RIFF....WEBP, then one of VP8 /VP8L/VP8X.
  if (buf.length >= 30 && buf.toString('ascii', 0, 4) === 'RIFF' && buf.toString('ascii', 8, 12) === 'WEBP') {
    const fourCc = buf.toString('ascii', 12, 16);
    if (fourCc === 'VP8 ') {
      // Lossy: 3-byte frame tag, 3-byte start code (9d 01 2a), then 2x uint16LE (14-bit + 2-bit scale).
      const w = buf.readUInt16LE(26) & 0x3fff;
      const h = buf.readUInt16LE(28) & 0x3fff;
      return { width: w, height: h };
    }
    if (fourCc === 'VP8L') {
      // Lossless: signature byte 0x2f, then a packed little-endian 32-bit value:
      // 14 bits width-1, 14 bits height-1, 1 bit alpha, 3 bits version.
      const bits = buf.readUInt32LE(21);
      return { width: (bits & 0x3fff) + 1, height: ((bits >> 14) & 0x3fff) + 1 };
    }
    if (fourCc === 'VP8X') {
      // Extended: 1 byte flags, 3 bytes reserved, 3-byte width-1 LE, 3-byte height-1 LE.
      const w = buf[24] | (buf[25] << 8) | (buf[26] << 16);
      const h = buf[27] | (buf[28] << 8) | (buf[29] << 16);
      return { width: w + 1, height: h + 1 };
    }
  }

  // JPEG: walk markers looking for an SOFn segment (0xC0-0xC3, 0xC5-0xC7, 0xC9-0xCB, 0xCD-0xCF).
  if (buf.length >= 4 && buf[0] === 0xff && buf[1] === 0xd8) {
    let offset = 2;
    while (offset + 9 < buf.length) {
      if (buf[offset] !== 0xff) { offset++; continue; }
      const marker = buf[offset + 1];
      if (marker === 0xd8 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd9)) { offset += 2; continue; }
      const segLen = buf.readUInt16BE(offset + 2);
      const isSof = (marker >= 0xc0 && marker <= 0xc3) || (marker >= 0xc5 && marker <= 0xc7) ||
        (marker >= 0xc9 && marker <= 0xcb) || (marker >= 0xcd && marker <= 0xcf);
      if (isSof) {
        const height = buf.readUInt16BE(offset + 5);
        const width = buf.readUInt16BE(offset + 7);
        return { width, height };
      }
      offset += 2 + segLen;
    }
  }

  return null;
}

function contentTypeForExt(path) {
  const ext = path.toLowerCase().split('.').pop();
  if (ext === 'webp') return 'image/webp';
  if (ext === 'png') return 'image/png';
  if (ext === 'jpg' || ext === 'jpeg') return 'image/jpeg';
  if (ext === 'svg') return 'image/svg+xml';
  throw new Error(`Unrecognised image extension: ${path}`);
}

/* ── HTTP client (fetch-based, dry-run aware) ────────────────────────────────────────────── */

class Client {
  constructor({ apiUrl, apiKey, dryRun, log }) {
    this.apiUrl = apiUrl ? apiUrl.replace(/\/+$/, '') : null;
    this.apiKey = apiKey;
    this.dryRun = dryRun;
    this.log = log;
    this.dryCounter = 0;
  }

  dryId(prefix) {
    this.dryCounter++;
    return `dry-${prefix}-${this.dryCounter}`;
  }

  async request(method, path, { body, rawBody, contentType, absoluteUrl } = {}) {
    if (this.dryRun) {
      const summary = rawBody
        ? `<${rawBody.length} raw bytes, ${contentType}>`
        : body !== undefined ? JSON.stringify(body) : '(no body)';
      this.log(`[dry-run] ${method} ${absoluteUrl || path}  ${summary}`);
      return null; // callers must not use the response in dry-run mode
    }
    const url = absoluteUrl || `${this.apiUrl}/api/v2${path}`;
    const headers = {};
    if (rawBody) {
      headers['Content-Type'] = contentType;
    } else if (body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    if (!absoluteUrl && this.apiKey) {
      headers['Authorization'] = `Bearer ${this.apiKey}`;
    }
    const res = await fetch(url, {
      method,
      headers,
      body: rawBody || (body !== undefined ? JSON.stringify(body) : undefined),
    });
    if (!res.ok) {
      let detail = '';
      try { detail = await res.text(); } catch { /* ignore */ }
      throw new Error(`${method} ${url} -> ${res.status}: ${detail}`);
    }
    if (res.status === 204) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }

  get(path) { return this.request('GET', path); }
  post(path, body) { return this.request('POST', path, { body }); }
  patch(path, body) { return this.request('PATCH', path, { body }); }
  putBytes(absoluteUrl, bytes, contentType) {
    return this.request('PUT', null, { absoluteUrl, rawBody: bytes, contentType });
  }
}

/* ── Brand Kit ────────────────────────────────────────────────────────────────────────────── */

const TOKEN_MAP = {
  '--coral': 'accent',
  '--coral-2': 'accent2',
  '--d-bg': 'darkBg',
  '--d-ink': 'darkInk',
  '--l-bg': 'lightBg',
  '--l-card': 'lightCard',
  '--ink': 'ink',
  '--ink-2': 'ink2',
};

const BUTTON_NAME = 'Add All to Shopping List';

/* One CopyRule per nexus copyErrors() check. `fields` is headline+body only, matching nexus's
 * own validate() (which never runs copyErrors against caption). */
function buildCopyRules() {
  return [
    {
      id: 'shopping-list-term',
      pattern: 'shopping list',
      flags: 'i',
      message: `the marketing term is "grocery list", not "shopping list" (the exact in-app button name "${BUTTON_NAME}" is allowed)`,
      fields: ['headline', 'body'],
      exceptPattern: BUTTON_NAME,
    },
    {
      id: 'no-dashes',
      pattern: '[\\u2014\\u2013]',
      message: 'no em or en dashes',
      fields: ['headline', 'body'],
    },
    {
      id: 'no-free-plan',
      pattern: 'free plan',
      flags: 'i',
      message: 'never sell the free plan as an entry point; the trial is the way in',
      fields: ['headline', 'body'],
    },
    {
      id: 'no-price',
      pattern: '\\$\\d',
      message: 'never print a price; Apple sets it per storefront',
      fields: ['headline', 'body'],
    },
    {
      id: 'no-emoji',
      pattern: '[\\u{1F300}-\\u{1FAFF}\\u{2600}-\\u{27BF}]',
      flags: 'u',
      message: 'no emoji',
      fields: ['headline', 'body'],
    },
    {
      id: 'no-exclamation',
      pattern: '!',
      message: 'no exclamation marks',
      fields: ['headline', 'body'],
    },
  ];
}

/* Brand Kit image slot -> nexus source file, and why. Only PNG/JPEG/WebP/SVG are accepted by
 * BrandKitService; every one of these is. Badge is Apple's own artwork and nexus always uses the
 * black variant regardless of theme (render.js: BADGE_SRC is a single constant) - there is no
 * separate "badge on dark" asset to lose, so one slot loses nothing here. Wordmark is genuinely
 * theme-dependent in nexus (render.js: "Dark artboards take the light wordmark"), so wordmarkDark
 * intentionally maps to the *-light.png file and vice versa - this is not a typo. */
const BRAND_IMAGE_SLOTS = [
  { slot: 'mark', file: 'assets/mascot/app-icon.png' },
  { slot: 'wordmark_dark', file: 'assets/brand/rexipe-wordmark-light.png' },
  { slot: 'wordmark_light', file: 'assets/brand/rexipe-wordmark.png' },
  { slot: 'badge', file: 'assets/appstore/app-store-badge-black-en-us.svg' },
];

async function ensureBrandKit(client, project, kitSlug, kitName, brand, sourceDir, notes) {
  let kit = null;
  if (!client.dryRun) {
    const kits = await client.get(`/projects/${project}/marketing/brand-kits`);
    kit = (kits || []).find((k) => k.slug === kitSlug) || null;
  }

  const tokens = {};
  for (const [nexusKey, conductorKey] of Object.entries(TOKEN_MAP)) {
    if (brand.tokens && brand.tokens[nexusKey] != null) tokens[conductorKey] = brand.tokens[nexusKey];
  }
  const droppedTokens = Object.keys(brand.tokens || {}).filter((k) => !(k in TOKEN_MAP));
  if (droppedTokens.length) {
    notes.push(`brand.json tokens with no Brand Kit destination, dropped: ${droppedTokens.join(', ')}`);
  }

  const copy = brand.copy || {};
  const approvedLines = [copy.trialClaim, copy.priceDisclosure, copy.autoRenew].filter(Boolean);

  const payload = {
    tokens,
    fontFamily: 'DM Sans',
    fontUrl: null,
    ctaClaim: copy.trialClaim || null,
    accentPhraseRequired: true,
    copyRules: buildCopyRules(),
    approvedLines,
    // The three placements nexus always exports regardless of a concept's own `placements` opt-ins
    // (schema.json: "The three default placements always export regardless of this list").
    enabledPlacements: ['9x16', '4x5', '1x1'],
  };
  notes.push('fontUrl left null: DM Sans ships as local .woff2 files in nexus (assets/fonts/), not a hostable URL. Host it (e.g. alongside the frontend) and PATCH fontUrl by hand if the render job needs self-hosted fonts.');

  if (!kit) {
    kit = await client.post(`/projects/${project}/marketing/brand-kits`, { slug: kitSlug, name: kitName, ...payload });
  } else {
    kit = await client.patch(`/projects/${project}/marketing/brand-kits/${kit.id}`, payload);
  }

  const kitId = kit ? kit.id : client.dryId('kit');
  for (const { slot, file } of BRAND_IMAGE_SLOTS) {
    const already = kit && kit[slot === 'wordmark_dark' ? 'wordmarkDarkUrl' : slot === 'wordmark_light' ? 'wordmarkLightUrl' : slot === 'mark' ? 'markUrl' : 'badgeUrl'];
    if (already) continue; // idempotent: slot already set on a real kit
    const path = join(sourceDir, file);
    if (!existsSync(path)) {
      notes.push(`Brand Kit image "${slot}" not uploaded: ${file} not found under ${sourceDir}`);
      continue;
    }
    const bytes = readFileSync(path);
    const contentType = contentTypeForExt(file);
    const ticket = await client.post(`/projects/${project}/marketing/brand-kits/${kitId}/images/${slot}`, {
      contentType,
      sizeBytes: bytes.length,
    });
    await client.putBytes(client.dryRun ? `dry:upload-url:${slot}` : ticket.uploadUrl, bytes, contentType);
    kit = await client.post(`/projects/${project}/marketing/brand-kits/${kitId}/images/${slot}/confirm`, {
      gcsPath: client.dryRun ? `dry/${slot}` : ticket.gcsPath,
    });
  }

  return { id: kitId };
}

/* ── Photos ───────────────────────────────────────────────────────────────────────────────── */

async function importPhotos(client, project, sourceDir, photosJson, notes) {
  const photoMap = new Map(); // nexus path -> { id }
  let existingByLabel = new Map();
  if (!client.dryRun) {
    const existing = await client.get(`/projects/${project}/marketing/photos?includeBlocked=true`);
    for (const p of existing || []) if (p.label) existingByLabel.set(p.label, p);
  }

  const entries = Object.entries(photosJson);
  for (const [path, meta] of entries) {
    if (existingByLabel.has(path)) {
      photoMap.set(path, { id: existingByLabel.get(path).id });
      continue;
    }

    const absPath = join(sourceDir, path);
    if (!existsSync(absPath)) {
      notes.push(`Photo not found on disk, skipped: ${path}`);
      continue;
    }
    const bytes = readFileSync(absPath);
    const size = readImageSize(bytes);
    if (!size) {
      notes.push(`Could not read dimensions from file header, skipped: ${path}`);
      continue;
    }
    const contentType = contentTypeForExt(path);

    const created = await client.post(`/projects/${project}/marketing/photos`, {
      label: path,
      contentType,
      sizeBytes: bytes.length,
      width: size.width,
      height: size.height,
      source: meta.source || null,
      licence: meta.licence || null,
      aiGenerated: Boolean(meta.ai),
    });
    const photoId = created ? created.id : client.dryId('photo');
    await client.putBytes(client.dryRun ? `dry:upload-url:${path}` : created.uploadUrl, bytes, contentType);
    await client.post(`/projects/${project}/marketing/photos/${photoId}/confirm`, { sizeBytes: bytes.length });

    const patch = {};
    if (meta.checked) patch.checked = true; // nexus stores a verdict STRING; Conductor only has a boolean, so the note text is lost here
    if (meta.blocked) { patch.blocked = true; patch.blockedReason = meta.blocked; }
    if (meta.focal) patch.focal = meta.focal;
    if (Object.keys(patch).length) {
      await client.patch(`/projects/${project}/marketing/photos/${photoId}`, patch);
    }
    if (meta.checked) notes.push(`Photo "${path}": nexus's checked verdict text ("${meta.checked}") collapses to checked=true; Conductor has no field to keep the note itself.`);
    if (meta.ai) notes.push(`Photo "${path}": nexus's ai description ("${meta.ai}") collapses to aiGenerated=true; Conductor has no field for the description text.`);

    photoMap.set(path, { id: photoId });
  }

  return photoMap;
}

/* ── Creatives ────────────────────────────────────────────────────────────────────────────── */

function parseId(id) {
  const m = /^(\d+)([a-z]+)$/.exec(id);
  if (!m) throw new Error(`Unrecognised concept id (expected e.g. "12a"): ${id}`);
  return { number: Number(m[1]), letter: m[2] };
}

function mapBeat(beat, photoMap, notes, context) {
  const out = {};
  if (beat.headline !== undefined) out.headline = beat.headline;
  if (beat.body !== undefined) out.body = beat.body;
  if (beat.cta !== undefined) out.cta = beat.cta;
  if (beat.photo) {
    const p = photoMap.get(beat.photo);
    if (p) out.photoId = p.id;
    else notes.push(`${context}: sequence beat photo not found in photo map: ${beat.photo}`);
  }
  if (beat.style) {
    notes.push(`${context}: sequence beat overrides "style" (${beat.style}); Conductor's SequenceBeat has no per-beat layout field, so this override is dropped.`);
  }
  return out;
}

/* Everything a Creative can hold, from one nexus concept. `photoId`/`focalOverride`/etc are
 * shared between the create call (root, letter "a") and the patch-after-variant call (any other
 * letter) - see importCreatives() for who calls this and how the result is split across calls. */
function mapConceptFields(concept, photoMap, notes) {
  const fields = {
    name: concept.name || concept.id,
    state: concept.draft ? 'DRAFT' : 'READY',
    layout: concept.style,
    theme: concept.theme || 'dark',
    headline: concept.headline,
  };
  if (concept.body !== undefined) fields.body = concept.body;
  if (concept.caption !== undefined) fields.caption = concept.caption;
  if (concept.alt !== undefined) fields.altText = concept.alt;
  if (concept.placements !== undefined) fields.placements = concept.placements;
  if (concept.carouselRatio !== undefined) fields.carouselRatio = concept.carouselRatio;
  if (concept.focal !== undefined) fields.focalOverride = concept.focal;

  if (concept.photo) {
    const p = photoMap.get(concept.photo);
    if (p) fields.photoId = p.id;
    else notes.push(`Concept ${concept.id}: photo not found in photo map: ${concept.photo}`);
  }

  if (concept.story) {
    fields.sequenceKind = 'story';
    fields.sequence = concept.story.map((b, i) => mapBeat(b, photoMap, notes, `Concept ${concept.id} story beat ${i + 1}`));
  } else if (concept.carousel) {
    fields.sequenceKind = 'carousel';
    fields.sequence = concept.carousel.map((b, i) => mapBeat(b, photoMap, notes, `Concept ${concept.id} carousel card ${i + 1}`));
  }

  if (concept.type !== undefined) fields.typeOverrides = concept.type;

  // band/padBottom -> layoutOverrides, lockup -> lockup: the three per-concept settings the
  // Conductor Creative model gained a field for in COND-24's fidelity-gap tranche (see
  // scripts/README-import-nexus-social.md's "Verifying against social/golden/").
  if (concept.band !== undefined || concept.padBottom !== undefined) {
    fields.layoutOverrides = {};
    if (concept.band !== undefined) fields.layoutOverrides.band = concept.band;
    if (concept.padBottom !== undefined) fields.layoutOverrides.padBottom = concept.padBottom;
  }
  if (concept.lockup !== undefined) fields.lockup = concept.lockup;

  return fields;
}

async function importCreatives(client, project, kitId, ads, photoMap, notes) {
  let existingByName = new Map();
  if (!client.dryRun) {
    const existing = await client.get(`/projects/${project}/marketing/creatives`);
    for (const c of existing || []) if (c.name) existingByName.set(c.name, c);
  }

  // Group by number, root ("a") first, then remaining letters ascending, and process number
  // groups in ascending numeric order for a legible, deterministic mapping report.
  const groups = new Map();
  for (const concept of ads) {
    const { number } = parseId(concept.id);
    if (!groups.has(number)) groups.set(number, []);
    groups.get(number).push(concept);
  }
  for (const list of groups.values()) list.sort((a, b) => parseId(a.id).letter.localeCompare(parseId(b.id).letter));
  const numbers = [...groups.keys()].sort((a, b) => a - b);

  const mapping = [];
  let created = 0;
  let variantsCreated = 0;
  let skipped = 0;

  for (const number of numbers) {
    let rootCreativeId = null;
    for (const concept of groups.get(number)) {
      const { letter } = parseId(concept.id);
      const fields = mapConceptFields(concept, photoMap, notes);
      const displayName = fields.name;

      const existing = existingByName.get(displayName);
      if (existing) {
        mapping.push({ nexusId: concept.id, creativeId: existing.id, name: displayName, action: 'skipped-existing' });
        if (letter === 'a') rootCreativeId = existing.id;
        skipped++;
        continue;
      }

      if (letter === 'a') {
        const body = {
          brandKitId: kitId,
          name: fields.name,
          state: fields.state,
          layout: fields.layout,
          theme: fields.theme,
          photoId: fields.photoId,
          focalOverride: fields.focalOverride,
          headline: fields.headline,
          body: fields.body,
          caption: fields.caption,
          altText: fields.altText,
          placements: fields.placements,
          sequenceKind: fields.sequenceKind,
          sequence: fields.sequence,
          carouselRatio: fields.carouselRatio,
          lockup: fields.lockup,
          layoutOverrides: fields.layoutOverrides,
        };
        const res = await client.post(`/projects/${project}/marketing/creatives`, body);
        const creativeId = res ? res.id : client.dryId(`creative-${concept.id}`);
        rootCreativeId = creativeId;

        // CreateCreativeRequest has no typeOverrides field - it must follow as a patch.
        if (fields.typeOverrides) {
          const version = res ? res.version : 1;
          await client.patch(`/projects/${project}/marketing/creatives/${creativeId}`, {
            version,
            typeOverrides: fields.typeOverrides,
          });
        }
        mapping.push({ nexusId: concept.id, creativeId, name: displayName, action: 'created' });
        created++;
      } else {
        if (!rootCreativeId) {
          notes.push(`Concept ${concept.id}: no root ("a") creative id available to cut a variant from — skipped.`);
          continue;
        }
        const variantRes = await client.post(`/projects/${project}/marketing/creatives/${rootCreativeId}/variants`, {
          headline: fields.headline,
          name: fields.name,
        });
        const creativeId = variantRes ? variantRes.id : client.dryId(`creative-${concept.id}`);
        const version = variantRes ? variantRes.version : 1;

        // "POST variants ... then PATCH the rest": the variant inherits photo/layout/theme/body/
        // caption/alt/placements/sequence/brandKit from the root, but a nexus variant is free to
        // differ on every one of those, so we patch the target's real values over the inherited copy.
        await client.patch(`/projects/${project}/marketing/creatives/${creativeId}`, {
          version,
          brandKitId: kitId,
          state: fields.state,
          layout: fields.layout,
          theme: fields.theme,
          photoId: fields.photoId,
          focalOverride: fields.focalOverride,
          body: fields.body,
          caption: fields.caption,
          altText: fields.altText,
          placements: fields.placements,
          sequenceKind: fields.sequenceKind,
          sequence: fields.sequence,
          carouselRatio: fields.carouselRatio,
          typeOverrides: fields.typeOverrides,
          lockup: fields.lockup,
          layoutOverrides: fields.layoutOverrides,
        });
        mapping.push({ nexusId: concept.id, creativeId, name: displayName, action: 'created-variant' });
        variantsCreated++;
      }
    }
  }

  return { mapping, counts: { creativesCreated: created, variantsCreated, skipped } };
}

/* ── Render (optional) ───────────────────────────────────────────────────────────────────── */

function runRender(mapping, { apiUrl, apiKey, project, dryRun, log }) {
  for (const m of mapping) {
    const cmd = ['npx', '-y', '@cliangdev/conductor', 'creative', 'render', m.creativeId,
      '--api-url', apiUrl || '<api-url>', '--project', project || '<project>'];
    if (dryRun) {
      log(`[dry-run] would run: ${cmd.join(' ')}`);
      continue;
    }
    log(`Rendering ${m.nexusId} (${m.creativeId})...`);
    const result = spawnSync(cmd[0], cmd.slice(1), {
      stdio: 'inherit',
      env: { ...process.env, CONDUCTOR_API_KEY: apiKey || process.env.CONDUCTOR_API_KEY || '' },
    });
    if (result.status !== 0) {
      log(`Render failed for ${m.nexusId} (${m.creativeId}), exit code ${result.status}`);
    }
  }
}

/* ── Main ─────────────────────────────────────────────────────────────────────────────────── */

export async function run(argv, { log = console.log } = {}) {
  const args = parseArgs(argv);
  if (args.help) {
    log(USAGE);
    return { exitCode: 0 };
  }
  if (!args.source) throw new Error('--source is required (path to nexus-marketing/social/)');
  if (!args.project) throw new Error('--project is required');
  if (!args.dryRun && (!args.apiUrl || !args.apiKey)) {
    throw new Error('--api-url and --api-key (or CONDUCTOR_API_URL / CONDUCTOR_API_KEY) are required unless --dry-run is set');
  }

  const sourceDir = resolve(args.source.replace(/^~(?=$|\/)/, process.env.HOME || ''));
  const brand = JSON.parse(readFileSync(join(sourceDir, 'brand.json'), 'utf8'));
  const photosJson = JSON.parse(readFileSync(join(sourceDir, 'photos.json'), 'utf8'));
  const adsJson = JSON.parse(readFileSync(join(sourceDir, 'ads.json'), 'utf8'));

  const client = new Client({ apiUrl: args.apiUrl, apiKey: args.apiKey, dryRun: args.dryRun, log });
  const notes = [];

  const kit = await ensureBrandKit(client, args.project, args.kitSlug, args.kitName, brand, sourceDir, notes);
  const photoMap = await importPhotos(client, args.project, sourceDir, photosJson, notes);
  const { mapping, counts } = await importCreatives(client, args.project, kit.id, adsJson.ads, photoMap, notes);

  if (args.render) {
    runRender(mapping, { apiUrl: args.apiUrl, apiKey: args.apiKey, project: args.project, dryRun: args.dryRun, log });
  }

  const summary = {
    kitId: kit.id,
    photos: { total: Object.keys(photosJson).length, mapped: photoMap.size },
    concepts: { total: adsJson.ads.length, ...counts },
    mapping,
    notes,
  };

  log('');
  log('── Summary ─────────────────────────────────────────────────────────');
  log(`Brand Kit: ${kit.id}`);
  log(`Photos: ${summary.photos.mapped}/${summary.photos.total} mapped`);
  log(`Concepts: ${summary.concepts.total} total, ${counts.creativesCreated} created, ${counts.variantsCreated} variants created, ${counts.skipped} skipped (already existed)`);
  if (notes.length) {
    log('');
    log('Notes (data that did not map cleanly):');
    for (const n of notes) log(`  - ${n}`);
  }
  log('');
  log('Mapping (nexus id -> creative id):');
  for (const m of mapping) log(`  ${m.nexusId.padEnd(5)} -> ${m.creativeId}  (${m.action})  ${m.name}`);

  if (args.out) {
    writeFileSync(args.out, JSON.stringify(summary, null, 2) + '\n');
    log('');
    log(`Wrote mapping report: ${args.out}`);
  }

  return { exitCode: 0, summary };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  run(process.argv.slice(2)).then(
    (r) => process.exit(r.exitCode),
    (err) => {
      console.error(`import-nexus-social: ${err.message}`);
      process.exit(1);
    }
  );
}
