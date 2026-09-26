-- The redirect_uri an authorization was started with, replayed at the token exchange.
--
-- OAuth requires the exchange to send exactly the redirect_uri the authorize request used. Conductor
-- now accepts the callback at two addresses: the backend's own URL, and conductor.rexipe.io, whose
-- frontend proxies /api/v1/oauth/callback to the backend. Behind that proxy the backend cannot tell
-- which address a callback arrived through, so it records the one it chose when it built the consent
-- URL and uses that. Nullable: a flow started before this column existed falls back to the backend's
-- own callback URL, which is what every flow used until now.
ALTER TABLE integration_oauth_states ADD COLUMN redirect_uri TEXT;
