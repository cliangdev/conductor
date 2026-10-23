-- Current state of a channel dimension member (a keyword's bid, status and match type, an ad's serving
-- status), upserted on every pull. Keyed like channel_metric_daily.dimension_key; the daily rows hold the flow.
CREATE TABLE channel_dimension_state (
    connection_id VARCHAR(36)  NOT NULL REFERENCES connection (id) ON DELETE CASCADE,
    dimension_key VARCHAR(190) NOT NULL,
    state         JSONB        NOT NULL,
    observed_at   TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (connection_id, dimension_key)
);
