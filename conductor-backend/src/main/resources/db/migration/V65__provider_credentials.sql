-- Agent module, Phase 1: per-(project, provider) BYO model-provider API keys.
-- Isolated from connector credentials (own table/entity); same KMS-envelope crypto scheme:
-- a per-row DEK wrapped in kms_key_reference encrypts encrypted_api_key with AES/GCM.

CREATE TABLE provider_credentials (
    id                VARCHAR(36)  PRIMARY KEY,
    project_id        VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    provider          VARCHAR(32)  NOT NULL,
    kms_key_reference TEXT,
    encrypted_api_key TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_credentials_project_provider UNIQUE (project_id, provider)
);

CREATE INDEX idx_provider_credentials_project ON provider_credentials (project_id);
