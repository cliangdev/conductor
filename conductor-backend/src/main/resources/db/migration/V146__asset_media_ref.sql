-- One media library: a Work Item's file is a link to a Media library item (creative_photo), never a copy.
-- The Asset row stays as the per-Work-Item link (publish targets, the approval bundle hash and the publishers
-- key on it) and reuses the Media item's stored object, so gcs_path is shared and no bytes move.
--
-- NO ACTION (not RESTRICT) so a project delete, which cascades to both tables in one statement, still works;
-- CreativePhotoService refuses to delete Media a Work Item still uses before the FK would ever fire.

ALTER TABLE assets
    ADD COLUMN media_id VARCHAR(36) REFERENCES creative_photo(id);

CREATE INDEX idx_assets_media_id ON assets(media_id) WHERE media_id IS NOT NULL;

COMMENT ON COLUMN assets.media_id IS
    'The Media library item (creative_photo) this file Asset links to; gcs_path is that item''s object. '
    'Null for link Assets and for a Creative''s rendered frame (creative_frame_id).';

-- Backfill: every uploaded image/video file a person put on a Work Item becomes a Media item on the same
-- stored object. Frames attached from a Creative (creative_frame_id) are render output, not library media.
CREATE TEMP TABLE asset_media_backfill ON COMMIT DROP AS
SELECT a.id AS asset_id, gen_random_uuid()::text AS media_id
FROM assets a
WHERE a.kind = 'file'
  AND a.upload_status = 'UPLOADED'
  AND a.gcs_path IS NOT NULL
  AND a.creative_frame_id IS NULL
  AND a.media_id IS NULL
  AND (a.content_type LIKE 'image/%' OR a.content_type LIKE 'video/%');

INSERT INTO creative_photo (id, project_id, label, gcs_path, content_type, size_bytes, width, height,
                            media_kind, duration_seconds, upload_status, created_at)
SELECT b.media_id,
       w.project_id,
       LEFT(a.label, 200),
       a.gcs_path,
       a.content_type,
       COALESCE(a.size_bytes, 0),
       a.width,
       a.height,
       CASE WHEN a.content_type LIKE 'video/%' THEN 'VIDEO' ELSE 'IMAGE' END,
       a.duration_seconds,
       'UPLOADED',
       a.created_at
FROM asset_media_backfill b
JOIN assets a ON a.id = b.asset_id
JOIN work_items w ON w.id = a.work_item_id;

UPDATE assets a
SET media_id = b.media_id
FROM asset_media_backfill b
WHERE a.id = b.asset_id;
