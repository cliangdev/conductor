import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

import { run, readImageSize } from './import-nexus-social.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const FIXTURE_SOURCE = join(HERE, 'fixtures', 'nexus-social');

/* ── A tiny fake Conductor v2 server ─────────────────────────────────────────────────────────
 * Enough of brand-kits/photos/creatives to drive the whole import once: list, create, patch,
 * mint+PUT+confirm image/photo uploads, and cut a variant. Every request that reaches it is
 * appended to `requests` in order, so tests can assert on the exact sequence the script sent. */
function startFakeServer() {
  const requests = [];
  const state = {
    kits: [],
    photos: [],
    creatives: [],
    nextId: 1,
  };
  const uploads = new Map(); // token -> { kind, id, slot }

  function id(prefix) {
    return `${prefix}-${state.nextId++}`;
  }

  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (c) => chunks.push(c));
    req.on('end', () => {
      const rawBody = Buffer.concat(chunks);
      const contentType = req.headers['content-type'] || '';
      let jsonBody;
      if (rawBody.length && contentType.includes('application/json')) {
        jsonBody = JSON.parse(rawBody.toString('utf8'));
      }
      const url = new URL(req.url, 'http://localhost');
      const path = url.pathname;
      requests.push({ method: req.method, path, body: jsonBody });

      const send = (status, body) => {
        const text = body === undefined ? '' : JSON.stringify(body);
        res.writeHead(status, { 'Content-Type': 'application/json' });
        res.end(text);
      };

      // Raw byte upload endpoint minted by both brand-kit images and photos.
      const uploadMatch = path.match(/^\/upload\/(.+)$/);
      if (uploadMatch) {
        uploads.set(uploadMatch[1], { bytes: rawBody.length });
        res.writeHead(204);
        res.end();
        return;
      }

      const m = (re) => path.match(re);
      let mm;

      // Brand kits
      if (req.method === 'GET' && m(/\/marketing\/brand-kits$/)) return send(200, state.kits);
      if (req.method === 'POST' && m(/\/marketing\/brand-kits$/)) {
        const kit = { id: id('kit'), slug: jsonBody.slug, name: jsonBody.name, isDefault: false, ...jsonBody };
        state.kits.push(kit);
        return send(201, kit);
      }
      if (req.method === 'PATCH' && (mm = m(/\/marketing\/brand-kits\/([^/]+)$/))) {
        const kit = state.kits.find((k) => k.id === mm[1]);
        Object.assign(kit, jsonBody);
        return send(200, kit);
      }
      if (req.method === 'POST' && (mm = m(/\/marketing\/brand-kits\/([^/]+)\/images\/([^/]+)$/))) {
        const token = id('upload');
        uploads.set(token, { kind: 'kit-image', kitId: mm[1], slot: mm[2] });
        return send(200, { uploadUrl: `http://127.0.0.1:${server.address().port}/upload/${token}`, gcsPath: `kits/${mm[1]}/${mm[2]}.bin` });
      }
      if (req.method === 'POST' && (mm = m(/\/marketing\/brand-kits\/([^/]+)\/images\/([^/]+)\/confirm$/))) {
        const kit = state.kits.find((k) => k.id === mm[1]);
        const field = { mark: 'markUrl', wordmark_dark: 'wordmarkDarkUrl', wordmark_light: 'wordmarkLightUrl', badge: 'badgeUrl' }[mm[2]];
        kit[field] = `https://signed.example/${mm[2]}`;
        return send(200, kit);
      }

      // Photos
      if (req.method === 'GET' && m(/\/marketing\/photos$/)) return send(200, state.photos);
      if (req.method === 'POST' && m(/\/marketing\/photos$/)) {
        const token = id('upload');
        const photo = {
          id: id('photo'),
          label: jsonBody.label,
          contentType: jsonBody.contentType,
          sizeBytes: jsonBody.sizeBytes,
          width: jsonBody.width,
          height: jsonBody.height,
          source: jsonBody.source ?? null,
          licence: jsonBody.licence ?? null,
          aiGenerated: Boolean(jsonBody.aiGenerated),
          checked: false,
          blocked: false,
          blockedReason: null,
          focal: {},
          uploadStatus: 'PENDING',
          warnings: [],
          createdAt: new Date().toISOString(),
        };
        uploads.set(token, { kind: 'photo', photoId: photo.id });
        state.photos.push(photo);
        return send(201, { ...photo, uploadUrl: `http://127.0.0.1:${server.address().port}/upload/${token}` });
      }
      if (req.method === 'POST' && (mm = m(/\/marketing\/photos\/([^/]+)\/confirm$/))) {
        const photo = state.photos.find((p) => p.id === mm[1]);
        photo.uploadStatus = 'UPLOADED';
        photo.url = 'https://signed.example/photo';
        return send(200, photo);
      }
      if (req.method === 'PATCH' && (mm = m(/\/marketing\/photos\/([^/]+)$/))) {
        const photo = state.photos.find((p) => p.id === mm[1]);
        Object.assign(photo, jsonBody);
        return send(200, photo);
      }

      // Creatives
      if (req.method === 'GET' && m(/\/marketing\/creatives$/)) return send(200, state.creatives);
      if (req.method === 'POST' && m(/\/marketing\/creatives$/)) {
        const creative = {
          id: id('creative'),
          projectId: 'proj',
          brandKitId: jsonBody.brandKitId,
          number: state.creatives.filter((c) => !c.parentCreativeId).length + 1,
          variantLetter: 'a',
          version: 1,
          parentCreativeId: null,
          ...jsonBody,
        };
        creative.displayId = `${creative.number}${creative.variantLetter}`;
        state.creatives.push(creative);
        return send(201, creative);
      }
      if (req.method === 'POST' && (mm = m(/\/marketing\/creatives\/([^/]+)\/variants$/))) {
        const root = state.creatives.find((c) => c.id === mm[1]);
        const letters = state.creatives
          .filter((c) => c.number === root.number)
          .map((c) => c.variantLetter);
        let letter = 'b';
        while (letters.includes(letter)) letter = String.fromCharCode(letter.charCodeAt(0) + 1);
        const variant = {
          ...root,
          id: id('creative'),
          version: 1,
          variantLetter: letter,
          parentCreativeId: root.id,
          headline: jsonBody?.headline ?? root.headline,
          name: jsonBody?.name ?? null,
        };
        variant.displayId = `${variant.number}${variant.variantLetter}`;
        state.creatives.push(variant);
        return send(201, variant);
      }
      if (req.method === 'PATCH' && (mm = m(/\/marketing\/creatives\/([^/]+)$/))) {
        const creative = state.creatives.find((c) => c.id === mm[1]);
        if (!creative) return send(404, { message: 'not found' });
        if (jsonBody.version !== creative.version) return send(409, { message: 'stale version' });
        Object.assign(creative, jsonBody, { version: creative.version + 1 });
        return send(200, creative);
      }

      send(404, { message: `no fake handler for ${req.method} ${path}` });
    });
  });

  return new Promise((resolvePromise) => {
    server.listen(0, '127.0.0.1', () => resolvePromise({ server, requests, state, uploads }));
  });
}

function closeServer(server) {
  return new Promise((r) => server.close(r));
}

/* ── readImageSize ────────────────────────────────────────────────────────────────────────── */

test('readImageSize reads PNG dimensions from the header', () => {
  const bytes = readFileSync(join(FIXTURE_SOURCE, 'assets', 'lifestyle', 'fixture-hero.png'));
  assert.deepEqual(readImageSize(bytes), { width: 2200, height: 1400 });
});

test('readImageSize reads a lossy WebP (VP8 ) header', () => {
  // A hand-built minimal RIFF/WEBP/"VP8 " header: real width/height at the documented byte
  // offsets, no real VP8 bitstream after it (readImageSize never decodes pixels). Byte offsets
  // were derived from, and cross-checked against `sips`, on the real nexus-marketing/social
  // WebP photos (see the task report); 18/18 real files there parse with this same code path.
  const width = 800; // 0x0320 -> LE bytes 0x20, 0x03
  const height = 600; // 0x0258 -> LE bytes 0x58, 0x02
  const bytes = Buffer.from([
    0x52, 0x49, 0x46, 0x46, /* RIFF */ 0x00, 0x00, 0x00, 0x00,
    0x57, 0x45, 0x42, 0x50, /* WEBP */
    0x56, 0x50, 0x38, 0x20, /* "VP8 " */ 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, /* frame tag */ 0x9d, 0x01, 0x2a, /* start code */
    0x20, 0x03, /* width LE */ 0x58, 0x02, /* height LE */
    0x00, 0x00,
  ]);
  assert.deepEqual(readImageSize(bytes), { width, height });
});

test('readImageSize reads a lossless WebP (VP8L) header', () => {
  // Signature byte 0x2f then a packed LE uint32: 14 bits width-1, 14 bits height-1, 1 bit alpha,
  // 3 bits version. width=400 (399), height=300 (299): 399 | (299 << 14) = 0x4AC38F.
  const bits = (399 & 0x3fff) | ((299 & 0x3fff) << 14);
  const packed = Buffer.alloc(4);
  packed.writeUInt32LE(bits, 0);
  const bytes = Buffer.concat([
    Buffer.from([0x52, 0x49, 0x46, 0x46, 0x00, 0x00, 0x00, 0x00, 0x57, 0x45, 0x42, 0x50]),
    Buffer.from([0x56, 0x50, 0x38, 0x4c, /* "VP8L" */ 0x00, 0x00, 0x00, 0x00]),
    Buffer.from([0x2f]),
    packed,
    Buffer.alloc(5), // pad past the header's minimum length check
  ]);
  assert.deepEqual(readImageSize(bytes), { width: 400, height: 300 });
});

/* ── Full import against the fake server ─────────────────────────────────────────────────── */

test('import-nexus-social: full run against a fake server', async () => {
  const { server, requests, state } = await startFakeServer();
  const port = server.address().port;
  const logs = [];
  try {
    const result = await run([
      '--api-url', `http://127.0.0.1:${port}`,
      '--api-key', 'test-key',
      '--project', 'proj-1',
      '--source', FIXTURE_SOURCE,
      '--kit-slug', 'default',
    ], { log: (m) => logs.push(m) });

    assert.equal(result.exitCode, 0);
    const { summary } = result;

    // Brand kit created with the mapped tokens, six copy rules, and the dropped --sky note.
    assert.equal(state.kits.length, 1);
    const kit = state.kits[0];
    assert.equal(kit.tokens.accent, '#FF5A5F');
    assert.equal(kit.tokens.ink2, '#717171');
    assert.equal(kit.tokens.sky, undefined);
    assert.equal(kit.copyRules.length, 6);
    assert.equal(kit.ctaClaim, '7 days free, then Pro or Pro Max.');
    assert.equal(kit.fontFamily, 'DM Sans');
    assert.equal(kit.fontUrl, null);
    assert.ok(summary.notes.some((n) => n.includes('--sky')));
    assert.ok(summary.notes.some((n) => n.includes('fontUrl left null')));

    // All four brand image slots confirmed.
    assert.equal(kit.markUrl, 'https://signed.example/mark');
    assert.equal(kit.wordmarkDarkUrl, 'https://signed.example/wordmark_dark');
    assert.equal(kit.wordmarkLightUrl, 'https://signed.example/wordmark_light');
    assert.equal(kit.badgeUrl, 'https://signed.example/badge');

    // Photos: every photos.json entry imported, dimensions read from the file header (not
    // present anywhere in photos.json itself), checked/blocked/focal patched in afterwards.
    assert.equal(state.photos.length, 5);
    const hero = state.photos.find((p) => p.label === 'assets/lifestyle/fixture-hero.png');
    assert.equal(hero.width, 2200);
    assert.equal(hero.height, 1400);
    assert.equal(hero.checked, true);
    assert.equal(hero.aiGenerated, true);
    assert.deepEqual(hero.focal, { '9x16': '50% 30%', '4x5': '50% 30%', '1x1': '50% 34%' });
    const blocked = state.photos.find((p) => p.label === 'assets/lifestyle/fixture-blocked.png');
    assert.equal(blocked.blocked, true);
    assert.match(blocked.blockedReason, /Burned-in text/);

    // Creatives: 4 concepts -> 3 created (roots) + 1 variant.
    assert.equal(state.creatives.length, 4);
    assert.equal(summary.concepts.creativesCreated, 3);
    assert.equal(summary.concepts.variantsCreated, 1);
    assert.equal(summary.concepts.skipped, 0);

    const root = state.creatives.find((c) => c.id === summary.mapping.find((m) => m.nexusId === '1a').creativeId);
    assert.equal(root.headline, 'This is *fixture* copy.');
    assert.equal(root.layout, 'stacked');
    assert.equal(root.state, 'READY');
    assert.equal(root.typeOverrides['9x16'][0], 76);
    // band/padBottom/lockup map onto layoutOverrides/lockup, not dropped (COND-24 fidelity-gap tranche).
    assert.deepEqual(root.layoutOverrides, { band: { '9x16': 1200 }, padBottom: { '9x16': 500 } });
    assert.equal(root.lockup, 'chip');

    // Variant cut: 1b is a variant of 1a's family, with its own headline/layout/photo/theme -
    // i.e. the POST variants + PATCH the rest sequence, not a plain create.
    const variant = state.creatives.find((c) => c.id === summary.mapping.find((m) => m.nexusId === '1b').creativeId);
    assert.equal(variant.parentCreativeId, root.id);
    assert.equal(variant.variantLetter, 'b');
    assert.equal(variant.number, root.number);
    assert.equal(variant.headline, 'This is the *variant* hook.');
    assert.equal(variant.layout, 'bleed');
    assert.notEqual(variant.photoId, root.photoId);

    // Sequence mapping: story beats map to headline/body/photoId/cta, second beat's photo
    // resolved through the same photo map as the root.
    const storyCreative = state.creatives.find((c) => c.id === summary.mapping.find((m) => m.nexusId === '2a').creativeId);
    assert.equal(storyCreative.sequenceKind, 'story');
    assert.equal(storyCreative.sequence.length, 2);
    assert.equal(storyCreative.sequence[0].headline, 'Beat one, *hooked*.');
    assert.equal(storyCreative.sequence[0].photoId, undefined);
    assert.equal(storyCreative.sequence[1].cta, true);
    const story2Photo = state.photos.find((p) => p.label === 'assets/lifestyle/fixture-story-2.png');
    assert.equal(storyCreative.sequence[1].photoId, story2Photo.id);

    // draft -> DRAFT state.
    const draftCreative = state.creatives.find((c) => c.id === summary.mapping.find((m) => m.nexusId === '3a').creativeId);
    assert.equal(draftCreative.state, 'DRAFT');

    // Request sequence sanity: brand kit created before any photo, every photo mint precedes its
    // confirm, and the variant's POST .../variants call precedes its PATCH.
    const kitCreateIdx = requests.findIndex((r) => r.method === 'POST' && r.path === '/api/v2/projects/proj-1/marketing/brand-kits');
    const firstPhotoIdx = requests.findIndex((r) => r.method === 'POST' && r.path === '/api/v2/projects/proj-1/marketing/photos');
    assert.ok(kitCreateIdx >= 0 && kitCreateIdx < firstPhotoIdx);

    const variantPostIdx = requests.findIndex((r) => r.method === 'POST' && /\/variants$/.test(r.path));
    const variantPatchIdx = requests.findIndex(
      (r, i) => i > variantPostIdx && r.method === 'PATCH' && r.path === `/api/v2/projects/proj-1/marketing/creatives/${variant.id}`
    );
    assert.ok(variantPostIdx >= 0);
    assert.ok(variantPatchIdx > variantPostIdx);
  } finally {
    await closeServer(server);
  }
});

test('import-nexus-social: re-run skips photos and creatives that already exist', async () => {
  const { server, state } = await startFakeServer();
  const port = server.address().port;
  const opts = [
    '--api-url', `http://127.0.0.1:${port}`,
    '--api-key', 'test-key',
    '--project', 'proj-1',
    '--source', FIXTURE_SOURCE,
  ];
  try {
    await run(opts, { log: () => {} });
    const photosAfterFirst = state.photos.length;
    const creativesAfterFirst = state.creatives.length;

    const second = await run(opts, { log: () => {} });

    assert.equal(state.photos.length, photosAfterFirst, 'no duplicate photos on re-run');
    assert.equal(state.creatives.length, creativesAfterFirst, 'no duplicate creatives on re-run');
    assert.equal(second.summary.concepts.skipped, 4);
    assert.equal(second.summary.concepts.creativesCreated, 0);
    assert.equal(second.summary.concepts.variantsCreated, 0);
  } finally {
    await closeServer(server);
  }
});

test('CLI requires --project and --source', async () => {
  await assert.rejects(() => run(['--dry-run', '--source', FIXTURE_SOURCE]), /--project is required/);
  await assert.rejects(() => run(['--dry-run', '--project', 'p']), /--source is required/);
});

test('--dry-run never touches the network', async () => {
  const logs = [];
  const result = await run(['--dry-run', '--project', 'proj-1', '--source', FIXTURE_SOURCE], { log: (m) => logs.push(m) });
  assert.equal(result.exitCode, 0);
  assert.ok(logs.some((l) => l.includes('[dry-run]')));
  assert.equal(result.summary.concepts.creativesCreated, 3);
  assert.equal(result.summary.concepts.variantsCreated, 1);
  assert.equal(result.summary.photos.total, 5);
});
