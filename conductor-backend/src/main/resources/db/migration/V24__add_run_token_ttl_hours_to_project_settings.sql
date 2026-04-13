-- Add run_token_ttl_hours to project_settings.
-- Controls how long workflow run tokens remain valid (1–168 hours).
-- Existing rows receive the default of 24 hours.
ALTER TABLE project_settings ADD COLUMN run_token_ttl_hours INT NOT NULL DEFAULT 24;
