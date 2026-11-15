-- Re-anchoring on save: whether a comment's anchor was found again in the latest saved content.
-- NULL = never evaluated (comments written before this column, and every comment until its document is next
-- saved). Nullable with no default so the previous release, which neither reads nor writes it, keeps working.
ALTER TABLE comments ADD COLUMN anchor_status TEXT NULL;
ALTER TABLE comments ADD CONSTRAINT comments_anchor_status_check
    CHECK (anchor_status IS NULL OR anchor_status IN ('ANCHORED', 'ORPHANED'));
