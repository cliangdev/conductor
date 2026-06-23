-- Backfill the unified `connection` model from the two legacy tables it supersedes.
-- Pure SQL: encrypted credential ciphertext is reused losslessly (same KMS DEK + key reference);
-- GitHub webhook secrets were plaintext and are intentionally NOT carried over — under the clean
-- cutover, repos are seeded as NEEDS_SETUP and users re-register to get a fresh generated secret.

-- 1) Pull connectors (PostHog, GCP Billing, ...) — copy the single credential row verbatim.
INSERT INTO connection (
    id, project_id, connector_id, display_label, auth_type, status,
    encrypted_access_token, encrypted_refresh_token, encrypted_webhook_secret,
    kms_key_reference, token_expires_at, config_json, visibility_policy,
    created_at, updated_at, connected_by
)
SELECT
    ic.id, ic.project_id, ic.connector_id, ic.connector_id, ic.auth_type, 'ACTIVE',
    ic.encrypted_access_token, ic.encrypted_refresh_token, NULL,
    ic.kms_key_reference, ic.token_expires_at, ic.config_json, ic.visibility_policy,
    ic.created_at, ic.updated_at, NULL
FROM integration_credentials ic;

-- 2) GitHub repositories — one connection per repo, seeded for re-setup (fresh secret on re-register).
INSERT INTO connection (
    id, project_id, connector_id, display_label, auth_type, status,
    config_json, created_at, updated_at, connected_by
)
SELECT
    pr.id, pr.project_id, 'github', pr.label, 'WEBHOOK', 'NEEDS_SETUP',
    jsonb_build_object('repoFullName', pr.repo_full_name, 'repoUrl', pr.repo_url),
    pr.connected_at, pr.connected_at, pr.connected_by
FROM project_repositories pr;

-- 3) Existing cached fetch results — re-key from (project, connector) to the new connection id.
INSERT INTO connection_data_cache (id, connection_id, data_json, health_status, fetched_at)
SELECT idc.id, c.id, idc.data_json, idc.health_status, idc.fetched_at
FROM integration_data_cache idc
JOIN connection c
  ON c.project_id = idc.project_id
 AND c.connector_id = idc.connector_id;

-- github_webhook_events rows are an audit log replaced by webhook_event; not migrated.
