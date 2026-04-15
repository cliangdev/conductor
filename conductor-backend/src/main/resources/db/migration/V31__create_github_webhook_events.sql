CREATE TABLE github_webhook_events (
    id                  VARCHAR(36)   PRIMARY KEY,
    project_id          VARCHAR(36)   NOT NULL REFERENCES projects(id),
    github_delivery_id  VARCHAR(128)  UNIQUE,
    event_type          VARCHAR(64)   NOT NULL,
    payload             JSONB         NOT NULL,
    status              VARCHAR(32)   NOT NULL DEFAULT 'PENDING',
    attempts            INT           NOT NULL DEFAULT 0,
    last_attempted_at   TIMESTAMP,
    error_message       TEXT,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_github_webhook_events_project_status
    ON github_webhook_events (project_id, status);
