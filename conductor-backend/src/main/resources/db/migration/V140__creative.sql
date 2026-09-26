-- COND-24 T2: the Creative library — photos with provenance, and the creatives themselves.
--
-- A Creative is a library object under the Marketing Area, not a Work Item (see architecture.md "Why
-- creatives are not Work Items"): it is reusable across Posts and has no lifecycle beyond
-- draft/ready/archived. `assets` require a Work Item, so photos get their own storage-backed rows here
-- (same mint -> signed PUT -> confirm shape as AssetService) rather than living under one.

CREATE TABLE creative_photo (
    id             VARCHAR(36) PRIMARY KEY,
    project_id     VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    label          VARCHAR(200),
    gcs_path       TEXT NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT NOT NULL,
    -- Client-declared: WebP dimensions cannot be probed server-side (no image pipeline here, per
    -- CLAUDE.md's "no server-side media cropping" rule), so these are advisory, not verified.
    width          INT,
    height         INT,
    source         VARCHAR(200),
    licence        VARCHAR(200),
    ai_generated   BOOLEAN NOT NULL DEFAULT FALSE,
    checked        BOOLEAN NOT NULL DEFAULT FALSE,
    blocked        BOOLEAN NOT NULL DEFAULT FALSE,
    blocked_reason TEXT,
    -- Per-placement focal point, e.g. {"9x16": "50% 30%"} — validated against the placement registry
    -- and the "x% y%" shape by CreativePhotoService.
    focal          JSONB NOT NULL DEFAULT '{}',
    upload_status  VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_by     VARCHAR(36) REFERENCES users(id),
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_creative_photo_project ON creative_photo (project_id, created_at DESC);

COMMENT ON TABLE creative_photo IS
    'Photos with provenance a Creative renders with: source, licence, AI disclosure, checked/blocked '
    'verdicts and per-placement focal points. Mint -> signed PUT -> confirm, like AssetService.';

CREATE TABLE creative (
    id               VARCHAR(36) PRIMARY KEY,
    project_id       VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- Creatives reference the kit they render with, never the project directly — a project may hold
    -- several kits, and a Creative's brand choice is explicit, defaulted at creation to the project's
    -- default kit (architecture.md, "Data model").
    brand_kit_id     VARCHAR(36) NOT NULL REFERENCES brand_kit(id),
    -- Per-project display number, e.g. "12"; paired with variant_letter this is nexus's "12a"/"12b" id.
    number           INT NOT NULL,
    variant_letter   VARCHAR(1) NOT NULL DEFAULT 'a',
    parent_creative_id VARCHAR(36) REFERENCES creative(id),
    name             VARCHAR(200),
    state            VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    layout           VARCHAR(64) NOT NULL,
    theme            VARCHAR(16) NOT NULL DEFAULT 'dark',
    photo_id         VARCHAR(36) REFERENCES creative_photo(id),
    focal_override   JSONB,
    headline         TEXT,
    body             TEXT,
    caption          TEXT,
    alt_text         TEXT,
    -- Opt-in placements beyond the kit's `enabled_placements`.
    placements       JSONB NOT NULL DEFAULT '[]',
    sequence_kind    VARCHAR(16),
    -- Beats/cards, each {headline?, body?, photoId?, cta?} — story (2-7) or carousel (2-10).
    sequence         JSONB NOT NULL DEFAULT '[]',
    carousel_ratio   VARCHAR(16),
    -- Pinned per-placement headline fit ([fontSize, lineHeight, letterSpacing]); cleared whenever the
    -- headline or layout changes (CreativeService, port of nexus setConcept's clearedType).
    type_overrides   JSONB NOT NULL DEFAULT '{}',
    -- Optimistic-locking version, replacing nexus's sha1 store version; the same 409 on a stale PATCH.
    version          INT NOT NULL DEFAULT 0,
    created_by       VARCHAR(36) REFERENCES users(id),
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_creative_project_number_letter UNIQUE (project_id, number, variant_letter)
);

CREATE INDEX idx_creative_project ON creative (project_id, number DESC, variant_letter);
CREATE INDEX idx_creative_brand_kit ON creative (brand_kit_id);
CREATE INDEX idx_creative_parent ON creative (parent_creative_id);
CREATE INDEX idx_creative_photo_ref ON creative (photo_id);

COMMENT ON TABLE creative IS
    'A library object under the Marketing Area: photo, headline, body, layout, theme, caption, alt '
    'text, optional story/carousel sequence, and lettered variants (12a, 12b). Not a Work Item.';
