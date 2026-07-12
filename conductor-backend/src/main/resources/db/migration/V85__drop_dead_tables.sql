-- Drop tables and columns with zero JPA entity mappings, unused since the integration-connector
-- rework and the retirement of the legacy v1 GitHub-webhook surface.
DROP TABLE IF EXISTS integration_credentials;
DROP TABLE IF EXISTS integration_data_cache;
DROP TABLE IF EXISTS project_repositories;
DROP TABLE IF EXISTS github_webhook_events;

-- Superseded by the assets table (V61); never mapped by an entity.
ALTER TABLE work_items DROP COLUMN IF EXISTS github_pr_url;
