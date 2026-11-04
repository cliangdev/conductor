-- Slide-text render job: Media minted through POST .../revisions/{id}/media is tied to that revision, so the
-- confirm step and the revision's complete only accept what the revision itself minted, a mint cap can be counted,
-- and what never made it into the Creative can be cleaned up. Additive and nullable only (preview deploys share the
-- prod DB). No foreign key: a deleted revision just leaves the marker behind.
ALTER TABLE creative_photo ADD COLUMN IF NOT EXISTS minted_for_revision_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_creative_photo_minted_for_revision
    ON creative_photo (minted_for_revision_id) WHERE minted_for_revision_id IS NOT NULL;
