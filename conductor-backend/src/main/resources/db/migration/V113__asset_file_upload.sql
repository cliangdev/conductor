-- COND-23: Assets could record a kind of 'link' or 'file', but nothing about a stored file --
-- neither where the bytes live nor whether they ever arrived. A file asset is created before its
-- upload completes (the client gets a signed URL, then PUTs to it), so the row needs an explicit
-- upload lifecycle: PENDING on creation, UPLOADED once the object is confirmed in the bucket.
--
-- All four columns are NULLABLE on purpose: every pre-existing row is kind='link' and has no upload
-- of any kind, so the migration must not require a backfill or a default. Links simply leave them null.
ALTER TABLE assets
    ADD COLUMN upload_status VARCHAR(16),   -- PENDING | UPLOADED (null for link assets)
    ADD COLUMN content_type  VARCHAR(128),
    ADD COLUMN size_bytes    BIGINT,
    ADD COLUMN gcs_path      TEXT,
    -- An UPLOADED asset is one whose bytes are known to exist and be servable, so it must carry both
    -- the storage location and the type needed to serve it. IS DISTINCT FROM keeps the constraint
    -- trivially true for every other row -- link assets (upload_status NULL) and PENDING uploads alike
    -- -- so no existing row is touched and an in-flight upload is still free to have neither.
    ADD CONSTRAINT chk_assets_uploaded_has_storage
        CHECK (upload_status IS DISTINCT FROM 'UPLOADED'
               OR (gcs_path IS NOT NULL AND content_type IS NOT NULL));

-- Supports the cleanup sweep for abandoned PENDING uploads (rows whose signed URL was never used).
CREATE INDEX idx_assets_upload_status ON assets(upload_status);
