-- Preflight-verification state for provider_credentials: "Connected" today just means a row exists
-- (see ProviderCredentialService); these columns let it mean "a row exists AND the last real probe
-- against the provider succeeded" (ProviderVerificationService). last_verification_report stores the
-- full VerificationReport (checks[] with per-check pass/fail/warn + message) as JSON for the UI's
-- collapsible detail view. No history is kept -- one credential <-> one verification state.
ALTER TABLE provider_credentials
    ADD COLUMN last_verified_at TIMESTAMPTZ,
    ADD COLUMN last_verification_status VARCHAR(16),
    ADD COLUMN last_verification_report JSONB;
