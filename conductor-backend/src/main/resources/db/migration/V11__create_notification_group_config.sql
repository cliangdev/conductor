-- Group-based notification channel configuration
-- Replaces per-event-type configs with per-group configs (ISSUES, MEMBERS)
-- Each group shares one webhook URL; individual event types within a group can be toggled.

CREATE TABLE notification_group_config (
    id              VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    channel_group   VARCHAR(20) NOT NULL,
    provider        VARCHAR(20) NOT NULL DEFAULT 'DISCORD',
    webhook_url     VARCHAR(512) NOT NULL,
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_notification_group_project UNIQUE (project_id, channel_group)
);

-- Stores which event types are enabled within a group config (absence = disabled)
CREATE TABLE notification_group_config_event (
    config_id   VARCHAR(36) NOT NULL REFERENCES notification_group_config(id) ON DELETE CASCADE,
    event_type  VARCHAR(50) NOT NULL,
    PRIMARY KEY (config_id, event_type)
);
