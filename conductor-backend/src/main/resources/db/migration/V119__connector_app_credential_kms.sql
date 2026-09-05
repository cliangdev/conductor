-- Move connector_app_credential onto the same envelope encryption the rest of Integrations uses.
--
-- V118 encrypted client_secret_encrypted with WorkflowSecretsEncryptionService: a single AES-256-GCM
-- key for the whole deployment, held in memory from WORKFLOW_SECRETS_KEY. Connection secrets are
-- stronger than that -- a per-row AES-256 DEK, wrapped by the KMS KEK and stored Base64 in that row's
-- kms_key_reference (CredentialService / GcpKmsCredentialService). This column brings app credentials
-- onto that same envelope, encrypted by that same implementation rather than a second copy of it, so
-- one compromised key can no longer open every project's platform app secret.
--
-- Additive on purpose: V118 is already committed on this branch and applied to developer databases,
-- so amending it would break them on a Flyway checksum mismatch.
ALTER TABLE connector_app_credential
    -- Null means exactly one thing: the row was written under the pre-envelope V118 scheme. No such
    -- row exists in any deployed environment (V118 has never shipped), but a developer database may
    -- hold one. ConnectorAppCredentialService#resolve refuses to read those and says why, rather than
    -- handing a null or garbage client secret to an OAuth provider; a project admin re-enters the
    -- secret in Settings -> Integrations and the row is rewritten under the envelope.
    ADD COLUMN kms_key_reference TEXT,
    -- The catalog and settings views show only the last four characters of the secret. Storing them
    -- means those read paths never decrypt at all -- structurally, not by convention -- which also
    -- keeps a catalog load from making one KMS round trip per configured connector, a cost the
    -- envelope would otherwise introduce. Null for a V118 row, whose secret cannot be read to derive
    -- them.
    ADD COLUMN client_secret_last4 VARCHAR(4);
