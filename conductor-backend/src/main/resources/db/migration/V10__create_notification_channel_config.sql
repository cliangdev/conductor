CREATE TABLE notification_channel_config (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    webhook_url VARCHAR(512),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_notification_project_event UNIQUE (project_id, event_type)
);
