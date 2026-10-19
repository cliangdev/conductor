-- Additive and idempotent (IF NOT EXISTS) so preview deploys sharing the prod database are safe.
-- The default Media library list (GET /marketing/photos) reads one project's non-blocked items newest
-- first; this partial index serves it as an ordered index scan without a filter or a sort.
CREATE INDEX IF NOT EXISTS idx_creative_photo_project_visible
    ON creative_photo (project_id, created_at DESC) WHERE blocked = false;
