-- Direct platform reads for the marketing verification ledger: a person (or an agent) looks at a platform's own
-- UI or API for an explicit range of days and records the figure. Append-only: a newer read supersedes an older
-- one in the ledger, nothing is updated or deleted. The ledger itself (verdicts, mismatches) is computed on read;
-- conductor_value, backend_value and verdict are only what was true at the moment of recording. Additive only.
--
-- metric       a channel metric key (installs, spend, clicks, ...), the application validates it against the catalog
-- day_from/to  the days the platform figure covers, inclusive; a level (followers) is read on day_to
-- source_name  where it was read, e.g. "Apple Ads console" or "Search Console UI"

CREATE TABLE marketing_platform_read (
    id              VARCHAR(36) PRIMARY KEY,
    project_id      VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    channel         VARCHAR(16) NOT NULL,
    metric          VARCHAR(64) NOT NULL,
    day_from        DATE NOT NULL,
    day_to          DATE NOT NULL,
    value           NUMERIC NOT NULL,
    source_name     VARCHAR(120) NOT NULL,
    note            VARCHAR(500),
    recorded_by     VARCHAR(36),
    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    conductor_value NUMERIC,
    backend_value   NUMERIC,
    verdict         VARCHAR(16),
    CONSTRAINT ck_marketing_platform_read_range CHECK (day_from <= day_to),
    CONSTRAINT ck_marketing_platform_read_value CHECK (value >= 0)
);

CREATE INDEX idx_marketing_platform_read_metric
    ON marketing_platform_read (project_id, channel, metric, recorded_at DESC);

COMMENT ON TABLE marketing_platform_read IS
    'Append-only direct platform reads for the marketing verification ledger. Never updated or deleted.';
COMMENT ON COLUMN marketing_platform_read.conductor_value IS
    'Conductor''s figure over day_from..day_to when the read was recorded; NULL when it could not be said.';
COMMENT ON COLUMN marketing_platform_read.backend_value IS
    'The product backend''s figure over the same days when the read was recorded; NULL when it had none.';
COMMENT ON COLUMN marketing_platform_read.verdict IS
    'BOTH, CONNECTOR, BACKEND, PLATFORM or UNCHECKED at the moment of recording.';
