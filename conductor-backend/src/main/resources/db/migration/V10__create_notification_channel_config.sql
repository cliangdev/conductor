CREATE TABLE notification_channel_config (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    event_type      VARCHAR(50) NOT NULL,
    provider        VARCHAR(20) NOT NULL DEFAULT 'DISCORD',
    webhook_url     VARCHAR(512) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_notification_project_event UNIQUE (project_id, event_type)
);

INSERT INTO notification_channel_config (id, project_id, event_type, provider, webhook_url, enabled, created_at, updated_at)
SELECT
    gen_random_uuid(),
    project_id,
    'ISSUE_SUBMITTED',
    'DISCORD',
    discord_webhook_url,
    TRUE,
    NOW(),
    NOW()
FROM project_settings
WHERE discord_webhook_url IS NOT NULL AND discord_webhook_url != '';
