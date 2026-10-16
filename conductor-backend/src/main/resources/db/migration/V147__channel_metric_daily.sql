-- Daily per-channel metrics, filled by scheduled connector feeds (sink CHANNEL_METRICS).
--
-- One row per (connection, metric, dimension, day). The feed re-pulls a trailing window every run, so rows
-- are upserted: a restated day updates in place. Days still inside a source's restatement window are
-- flagged provisional. dimension_key is '' for a total and e.g. a referring domain, a query or a campaign
-- for a breakdown row. Additive only.

CREATE TABLE channel_metric_daily (
    id             VARCHAR(36) PRIMARY KEY,
    project_id     VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connection_id  VARCHAR(36) NOT NULL REFERENCES connection(id) ON DELETE CASCADE,
    connector_id   TEXT NOT NULL,
    channel        TEXT NOT NULL CHECK (channel IN ('WEB', 'SEARCH', 'SOCIAL', 'STORE', 'ADS', 'PRODUCT')),
    metric         TEXT NOT NULL,
    dimension_key  TEXT NOT NULL DEFAULT '',
    day            DATE NOT NULL,
    value          NUMERIC NOT NULL,
    provisional    BOOLEAN NOT NULL DEFAULT FALSE,
    fetched_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_channel_metric_daily UNIQUE (connection_id, metric, dimension_key, day)
);

CREATE INDEX idx_channel_metric_daily_project_channel_day ON channel_metric_daily (project_id, channel, day);

COMMENT ON TABLE channel_metric_daily IS
    'Daily metric values per channel, upserted by the connection''s channel_metrics feed.';
