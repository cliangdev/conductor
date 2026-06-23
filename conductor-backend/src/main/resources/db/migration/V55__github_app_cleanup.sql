-- GitHub moves from manual per-repo webhooks (AuthType.WEBHOOK) to a GitHub App (AuthType.APP).
-- Remove the now-obsolete manual github connections (e.g. backfilled NEEDS_SETUP repo rows);
-- App installations are created fresh via the install flow.
DELETE FROM connection WHERE connector_id = 'github' AND auth_type = 'WEBHOOK';

-- Speed up app-level webhook routing: resolve a github connection by its installation id in config_json.
CREATE INDEX IF NOT EXISTS idx_connection_github_installation
    ON connection ((config_json->>'installationId'))
    WHERE connector_id = 'github';
