-- Per-project platform OAuth app credentials, with the deployment env var as the fallback.
--
-- Today the platform OAuth apps (META_APP_ID/META_APP_SECRET, GOOGLE_OAUTH_CLIENT_ID/SECRET,
-- TIKTOK_CLIENT_KEY/SECRET) exist only as deployment environment variables, read by
-- OAuthFlowService via Environment#getProperty. They cannot be set, rotated, or inspected from the
-- product. This table lets a workspace bring its own platform app without touching the deployment
-- and without affecting any other workspace.
--
-- Resolution order (ConnectorAppCredentialService#resolve): this table's row for the project, else
-- the deployment env var, else "not configured". The table is therefore purely additive -- a
-- deployment with no rows resolves exactly what it resolves today.
--
-- connector_id is the ConnectorRegistry id ('meta', 'tiktok', 'gsc', ...), not a platform: two
-- connectors that happen to share a platform each carry their own app credential row.
--
-- client_secret_encrypted holds AES-256-GCM ciphertext (WorkflowSecretsEncryptionService -- the
-- same envelope workflow_secrets uses); the plaintext secret never reaches this table. client_id is
-- not a secret -- it travels in the consent URL the browser is redirected to -- so it stays clear.
CREATE TABLE connector_app_credential (
    id                      VARCHAR(36) PRIMARY KEY,
    project_id              VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connector_id            VARCHAR(64) NOT NULL,
    client_id               TEXT        NOT NULL,
    client_secret_encrypted TEXT        NOT NULL,
    -- Who last wrote the row, for the "set by X" byline in Settings. ON DELETE SET NULL rather than
    -- CASCADE: removing a member must never silently delete the platform app credential their
    -- project's OAuth flows depend on -- only the byline is theirs to lose.
    updated_by              VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- One app credential per connector per project; the upsert in ConnectorAppCredentialService#put
    -- relies on this, and its index is also the lookup path for the per-project resolve.
    CONSTRAINT uq_connector_app_credential_project_connector UNIQUE (project_id, connector_id)
);
