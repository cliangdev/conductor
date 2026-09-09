-- Where a platform that fetches media by URL (TikTok photo posts) is sent for a Post's images: a host the
-- workspace owns and has verified with the platform, fronting Conductor's storage. Null means the platform
-- is handed the storage link, which TikTok refuses (url_ownership_unverified).
ALTER TABLE project_settings ADD COLUMN public_media_base_url VARCHAR(512);
