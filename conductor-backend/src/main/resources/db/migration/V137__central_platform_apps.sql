-- TikTok and Meta move from a per-workspace OAuth app to Conductor's own central app.
--
-- Before this migration, ConnectorAppCredentialService resolved a project's stored
-- connector_app_credential row for 'tiktok'/'meta' (or told the admin to enter one). After it, both
-- connectors are AppOwnership.DEPLOYMENT_ONLY: ConnectorAppCredentialService never looks up a
-- project row for them at all, resolving straight from the deployment's own TIKTOK_CLIENT_KEY/
-- TIKTOK_CLIENT_SECRET and META_APP_ID/META_APP_SECRET env vars instead. Any row already stored for
-- these two connectors is therefore dead weight. It is unreachable through the product, and
-- misleading if a future reader assumes a row here still means anything, so it is deleted outright
-- rather than left to rot.
--
-- A token a member's browser obtained by consenting to the *workspace's* app cannot be refreshed
-- against Conductor's app: refresh_token grants are scoped to the client_id/client_secret pair that
-- minted them, so every existing tiktok/meta connection's refresh token is now unusable even though
-- the row itself is untouched. Rather than let that surface as a confusing failure the next time a
-- scheduled post tries to refresh, every affected connection is marked UNHEALTHY up front with a
-- message that tells the member what to do: reconnect, which runs a fresh consent through the new
-- central app and replaces the token.
--
-- health_status/health_message are the columns V114 added; NULL there means "never checked", which
-- is why this migration writes an explicit UNHEALTHY rather than leaving the column alone.
DELETE FROM connector_app_credential WHERE connector_id IN ('tiktok', 'meta');

UPDATE connection
SET health_status  = 'UNHEALTHY',
    health_message = 'Conductor now publishes through its own TikTok/Meta app. Reconnect this '
                      || 'account to keep publishing.',
    health_checked_at = now()
WHERE connector_id IN ('tiktok', 'meta');
