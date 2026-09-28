-- COND-24 T2: the Brand Kit a Creative renders with.
--
-- A workspace may hold several kits (a second brand, a regional variant later); one of them is always
-- the default a new Creative starts from. Nothing here is Rexipe-specific — every brand rule (colour
-- tokens, copy rules, the accent-phrase requirement, the CTA claim) is data on the row, never a
-- product-code constant. `BrandKitService` lazily creates the first kit (slug `default`) the first
-- time a project's kits are listed or its default is resolved, so this migration seeds nothing.

CREATE TABLE brand_kit (
    id                        VARCHAR(36) PRIMARY KEY,
    project_id                VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    slug                      VARCHAR(64) NOT NULL,
    name                      VARCHAR(200) NOT NULL,
    is_default                BOOLEAN NOT NULL DEFAULT FALSE,
    -- accent, accent2, darkBg, darkInk, lightBg, lightCard, ink, ink2 — the renderer's CSS custom
    -- properties. camelCase keys because the frontend and conductor-creative/ read this object as-is.
    tokens                    JSONB NOT NULL DEFAULT '{}',
    font_family               VARCHAR(200),
    font_url                  TEXT,
    mark_gcs_path             TEXT,
    wordmark_dark_gcs_path    TEXT,
    wordmark_light_gcs_path   TEXT,
    badge_gcs_path            TEXT,
    cta_claim                 TEXT,
    -- The "exactly one *phrase*" house rule (one accent phrase per headline). Off by default —
    -- a fresh, brand-free kit makes no claim about house style until a workspace opts in.
    accent_phrase_required    BOOLEAN NOT NULL DEFAULT FALSE,
    -- [{id, pattern, flags?, message, fields:[headline|body|caption], exceptPattern?}] — validated at
    -- write time by CreativeValidator against every regex compiling; enforced on every Creative write.
    copy_rules                JSONB NOT NULL DEFAULT '[]',
    approved_lines            JSONB NOT NULL DEFAULT '[]',
    -- Placement keys (from conductor-creative/placements.json) a Creative exports by default; a
    -- Creative can opt into more via its own `placements`.
    enabled_placements        JSONB NOT NULL DEFAULT '["9x16","4x5","1x1"]',
    knowledge_page_path       VARCHAR(500) NOT NULL DEFAULT 'marketing/brand.md',
    created_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_brand_kit_project_slug UNIQUE (project_id, slug)
);

-- Exactly one default kit per project: a partial unique index rather than a boolean column
-- elsewhere, since only the TRUE rows need to be unique — every project has any number of non-default
-- kits. Switching the default is a transaction that flips the old default off before the new one on,
-- so this index is never violated mid-request.
CREATE UNIQUE INDEX uq_brand_kit_project_default ON brand_kit (project_id) WHERE is_default;

CREATE INDEX idx_brand_kit_project ON brand_kit (project_id);

COMMENT ON TABLE brand_kit IS
    'Per-project brand truth a Creative renders with: tokens, logos, font, copy rules, approved lines '
    'and enabled placements. Several per project; exactly one default.';
