-- A workspace's target for one channel metric. Additive only.

CREATE TABLE channel_metric_target (
    id          VARCHAR(36) PRIMARY KEY,
    project_id  VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    channel     TEXT NOT NULL CHECK (channel IN ('WEB', 'SEARCH', 'SOCIAL', 'STORE', 'ADS', 'PRODUCT')),
    metric      TEXT NOT NULL,
    target      NUMERIC NOT NULL,
    direction   TEXT NOT NULL CHECK (direction IN ('UP', 'DOWN')),
    updated_by  VARCHAR(36),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_channel_metric_target UNIQUE (project_id, channel, metric)
);
