-- Industry benchmarks a workspace compares its own marketing numbers against. Every row names its source and
-- the day it was checked, so a figure is never recorded from memory. Additive only.

CREATE TABLE marketing_benchmark (
    id          VARCHAR(36) PRIMARY KEY,
    project_id  VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    -- CHANNEL.metric (e.g. STORE.conversion_rate, ADS.tap_through_rate) or PRODUCT.<key>
    metric_ref  VARCHAR(96) NOT NULL,
    label       TEXT,
    value       NUMERIC NOT NULL,
    range_text  TEXT,
    segment     TEXT,
    source_name TEXT NOT NULL,
    source_url  TEXT NOT NULL,
    checked_on  DATE NOT NULL,
    notes       TEXT,
    created_by  VARCHAR(36),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_marketing_benchmark UNIQUE (project_id, metric_ref)
);
