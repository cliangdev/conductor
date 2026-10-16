-- Metric definitions declared by a product metrics feed (connector `product-metrics`), plus an optional
-- sample size on each daily value. Additive only.
--
-- A product backend names its own funnel: the definitions say what each metric is called, how a window
-- rolls up (SUM, LEVEL or RATIO of two other metrics), which way is good, where it sits in the funnel and an
-- optional target. They are replaced on every pull, so a metric the backend stops sending disappears.

CREATE TABLE channel_metric_definition (
    id               VARCHAR(36) PRIMARY KEY,
    connection_id    VARCHAR(36) NOT NULL REFERENCES connection(id) ON DELETE CASCADE,
    project_id       VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    metric           TEXT NOT NULL,
    label            TEXT NOT NULL,
    unit             TEXT NOT NULL CHECK (unit IN ('COUNT', 'CURRENCY', 'PERCENT')),
    aggregation      TEXT NOT NULL CHECK (aggregation IN ('SUM', 'LEVEL', 'RATIO')),
    numerator        TEXT,
    denominator      TEXT,
    good             TEXT NOT NULL CHECK (good IN ('UP', 'DOWN')),
    funnel_order     INTEGER,
    target_value     NUMERIC,
    target_direction TEXT CHECK (target_direction IN ('UP', 'DOWN')),
    target_window    TEXT,
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_channel_metric_definition UNIQUE (connection_id, metric)
);

CREATE INDEX idx_channel_metric_definition_project ON channel_metric_definition (project_id);

COMMENT ON TABLE channel_metric_definition IS
    'Metric definitions a product metrics feed declares; replaced on every pull.';

ALTER TABLE channel_metric_daily ADD COLUMN sample_size BIGINT NULL;

COMMENT ON COLUMN channel_metric_daily.sample_size IS
    'How many users or events the value rests on, when the source says; under 20 reads as insufficient data.';
