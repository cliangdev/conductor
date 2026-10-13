-- CAROUSEL creatives: finished images used as-is, ordered per placement. No creative.kind change is needed
-- (kind is VARCHAR(8); 'CAROUSEL' fits).

ALTER TABLE creative
    ADD COLUMN slides         JSONB,
    ADD COLUMN slide_alt_text JSONB;

COMMENT ON COLUMN creative.slides IS
    '{"default": [mediaId...], "<placementKey>": [mediaId...], ...} for a CAROUSEL creative — ordered IMAGE rows in '
    'creative_photo. "default" is used for every targeted placement the map does not otherwise name.';
COMMENT ON COLUMN creative.slide_alt_text IS
    'Index-aligned JSON array of strings for a CAROUSEL creative: entry N describes slide N in every placement.';
