-- Scheduled Knowledge Center feeds pulled from a connector's declarative IngestSpec (see
-- com.conductor.integration.IngestSpec) -- shipping JSON only (no Java, no migration, no workflow
-- YAML) is what makes a connector's feed provisionable, and connector_feed is the per-connection
-- binding that turns a declared feed into something that actually runs on a schedule.
--
-- connector_feed is the pull binding: one row per (connection, ingest id), carrying both the
-- connector-owned cursor and the platform-owned scheduling/backoff state. connector_feed_digest is a
-- separate per-(feed, period) lifecycle table -- keeping digest narration out of connector_feed itself
-- means a failed narration (e.g. the narrator agent run errors out) never blocks the feed's next pull,
-- and every period's history is retained for audit/replay instead of being overwritten in place.
--
-- cursor_state (not "cursor" -- a reserved word in the SQL standard) is TEXT and deliberately opaque:
-- the platform persists whatever IngestBatch#nextCursor() returns and hands it back verbatim on the
-- next pull, never parsing or interpreting it (see IngestBatch's javadoc). last_stats is the exact
-- opposite -- a platform-owned, parsed JSONB statistical baseline (rolling mean/stddev per metric) the
-- change-detector reads and writes every run.
CREATE TABLE connector_feed (
    id                     VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id             VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connection_id          VARCHAR(36) NOT NULL REFERENCES connection(id) ON DELETE CASCADE,
    -- NOT NULL on connector_id/ingest_id/mode is load-bearing, not decoration: Postgres treats NULLs as
    -- DISTINCT in a UNIQUE constraint, so a nullable ingest_id would silently defeat
    -- UNIQUE (connection_id, ingest_id) and let the provisioner create unbounded duplicate feeds for the
    -- same connection.
    connector_id           VARCHAR(64) NOT NULL,
    ingest_id              VARCHAR(64) NOT NULL,
    enabled                BOOLEAN NOT NULL DEFAULT true,
    mode                   VARCHAR(16) NOT NULL DEFAULT 'SNAPSHOT', -- SNAPSHOT | WINDOW
    interval_minutes       INT NOT NULL DEFAULT 1440,
    cursor_state           TEXT,
    cursor_updated_at      TIMESTAMP WITH TIME ZONE,
    last_window_start      TIMESTAMP WITH TIME ZONE,
    last_window_end        TIMESTAMP WITH TIME ZONE,
    last_stats             JSONB,
    quiet_periods          INT NOT NULL DEFAULT 0,
    next_run_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_run_at            TIMESTAMP WITH TIME ZONE,
    last_success_at        TIMESTAMP WITH TIME ZONE,
    consecutive_failures   INT NOT NULL DEFAULT 0,
    status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | PAUSED | SETUP_REQUIRED | DEAD
    last_error             TEXT,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (connection_id, ingest_id)
);

CREATE INDEX idx_connector_feed_due ON connector_feed (status, enabled, next_run_at);
CREATE INDEX idx_connector_feed_project_connector ON connector_feed (project_id, connector_id);

-- No FK on knowledge_source_id: KnowledgeRetentionService hard-deletes DEAD knowledge_sources rows, so
-- a dangling id here is an expected, harmless state rather than an integrity error -- same reasoning
-- V94 uses for knowledge_domains.owning_agent_slug.
CREATE TABLE connector_feed_digest (
    id                     VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id             VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    feed_id                VARCHAR(36) NOT NULL REFERENCES connector_feed(id) ON DELETE CASCADE,
    -- NOT NULL for the same reason as ingest_id above: a null period_key would defeat
    -- UNIQUE (feed_id, period_key) and allow the same period to be digested repeatedly.
    period_key             VARCHAR(32) NOT NULL,
    window_start           TIMESTAMP WITH TIME ZONE,
    window_end             TIMESTAMP WITH TIME ZONE,
    change_report          JSONB NOT NULL,
    material               BOOLEAN NOT NULL DEFAULT false,
    dedup_key              VARCHAR(128) NOT NULL,
    status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING', -- PENDING | NARRATING | SUBMITTED | SKIPPED | DEAD
    attempts               INT NOT NULL DEFAULT 0,
    next_attempt_at        TIMESTAMP WITH TIME ZONE,
    narrating_run_id       VARCHAR(36),
    knowledge_source_id    VARCHAR(36),
    error_message          TEXT,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (feed_id, period_key)
);

CREATE INDEX idx_connector_feed_digest_status_next_attempt ON connector_feed_digest (status, next_attempt_at);
