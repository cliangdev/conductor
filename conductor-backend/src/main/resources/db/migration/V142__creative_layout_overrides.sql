-- COND-24 fidelity gap: two per-concept layout tweaks and a lockup variant nexus-marketing/social/
-- has and this port dropped (see scripts/README-import-nexus-social.md's "Verifying against
-- social/golden/" section) — needed to render a handful of imported golden concepts recognisably
-- identically (AC-P1-2.1).
--
--   layout_overrides: { band?: {placementKey: px}, padBottom?: {placementKey: px} } — per-placement
--   overrides of the stacked layout's photo band height and the 9x16 panel's bottom safe-zone
--   clearance (nexus schema.json's `band`/`padBottom`; render.js applies them as the `--cc-band-h` /
--   `--cc-pad-b` CSS custom properties already read by conductor-creative/layouts/stacked/layout.css).
--
--   lockup: 'plain' (default) or 'chip' — puts the logo lockup on a white pill instead of bare on the
--   photo, for busy photography (nexus schema.json's `lockup`).
--
-- Both are generic render-engine data, not brand values — no brand-specific constant is added here.

ALTER TABLE creative ADD COLUMN layout_overrides JSONB NULL;
ALTER TABLE creative ADD COLUMN lockup VARCHAR(16) NOT NULL DEFAULT 'plain';

COMMENT ON COLUMN creative.layout_overrides IS
    'Per-placement overrides of layout-derived numbers: {"band": {"9x16": 1200}, "padBottom": '
    '{"9x16": 500}}. Null/absent placement keys fall back to the layout''s own defaults. Validated '
    'against the placement registry and a sane pixel bound by CreativeValidator.';

COMMENT ON COLUMN creative.lockup IS
    '"plain" (default) or "chip" (logo lockup on a white pill, for busy photography) — '
    'CreativeValidator/the CreativeLockup enum are the source of truth for allowed values.';
