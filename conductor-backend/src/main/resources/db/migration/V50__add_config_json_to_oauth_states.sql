ALTER TABLE integration_oauth_states
    ADD COLUMN config_json JSONB NOT NULL DEFAULT '{}';
