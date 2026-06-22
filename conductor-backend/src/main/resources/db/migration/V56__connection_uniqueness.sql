-- Restore the uniqueness guarantees the unified `connection` table lost vs. the old
-- integration_credentials / project_repositories model.
--
-- Two fixes, both enforced at the DB level (the only reliable defense against the
-- read-then-write race in ConnectionService.getOrCreateSingle):
--
--   #9 single-instance connectors (e.g. posthog, gcp-billing) must have at most one
--      row per (project, connector). Previously enforced only in service code.
--   #4 a github App installation must not be connected twice WITHIN one project (which
--      would make per-project webhook routing ambiguous). Cross-project sharing of the
--      same installationId stays allowed (multi-project fan-out).

-- #9 — per-row single-instance flag + backfill for the known single-instance connectors.
ALTER TABLE connection ADD COLUMN single_instance BOOLEAN NOT NULL DEFAULT false;
UPDATE connection SET single_instance = true WHERE connector_id IN ('posthog', 'gcp-billing');

-- #9 — at most one single-instance connection per (project, connector).
CREATE UNIQUE INDEX uq_connection_single_instance
    ON connection (project_id, connector_id)
    WHERE single_instance;

-- #4 — a project cannot double-connect the same github installation. Cross-project
-- sharing of an installationId remains allowed (uniqueness is on the per-project pair).
-- The non-unique routing index idx_connection_github_installation (V55) stays as-is.
CREATE UNIQUE INDEX uq_connection_github_installation_per_project
    ON connection (project_id, (config_json->>'installationId'))
    WHERE connector_id = 'github' AND config_json->>'installationId' IS NOT NULL;
