-- Publishing follow-up: what happened to a post after it went out.
--
-- Until now the pipeline's knowledge of a post ended at its permalink. Views, likes, comments and
-- shares are what the people who scheduled it actually want to know, and the connectors that
-- published it can read them back through the same connection. Each publishing connector now
-- declares a `post_metrics` ingest feed; the feed pulls the numbers for every PUBLISHED target on
-- its connection, on a cadence, and files one snapshot per target per period here.
--
-- A dedicated table rather than the Work Item's outcome_metric JSONB: outcome_metric is one scalar
-- per observation, keyed to whatever metric the Workflow declares (MARKETING declares none), and
-- cannot be indexed for "which posts did best this month?". Per-target rows can, and keep a Post
-- that went to three accounts legible as three series.

CREATE TABLE post_publish_target_metric (
    id                 VARCHAR(36) PRIMARY KEY,
    target_id          VARCHAR(36) NOT NULL REFERENCES post_publish_target(id) ON DELETE CASCADE,
    -- Denormalised from the target so the Post-level and project-level reads never join through it.
    work_item_id       VARCHAR(36) NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    project_id         VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    platform           VARCHAR(32) NOT NULL,
    -- The pull period this snapshot belongs to (an ISO-8601 hour, e.g. 2026-09-04T06). One row per
    -- target per period: a retried pull in the same period overwrites rather than duplicates.
    period_key         VARCHAR(32) NOT NULL,
    observed_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    views              BIGINT,
    likes              BIGINT,
    comments           BIGINT,
    shares             BIGINT,
    saves              BIGINT,
    reach              BIGINT,
    impressions        BIGINT,
    watch_time_seconds BIGINT,
    -- The platform no longer returns this post (deleted there, or the account lost it). Recorded, not
    -- treated as a failed pull: the other posts on the feed still count.
    unavailable        BOOLEAN NOT NULL DEFAULT FALSE,
    -- Platform-specific leftovers a column does not cover. Never queried.
    extra              JSONB,
    CONSTRAINT uq_post_publish_target_metric_period UNIQUE (target_id, period_key)
);

-- The Post page: every snapshot for one Work Item, newest first.
CREATE INDEX idx_post_publish_target_metric_work_item ON post_publish_target_metric (work_item_id, observed_at DESC);
-- The project view: "top posts on Instagram this month".
CREATE INDEX idx_post_publish_target_metric_project ON post_publish_target_metric (project_id, platform, observed_at DESC);

COMMENT ON TABLE post_publish_target_metric IS
    'One performance snapshot per publish target per pull period, read back from the platform by the '
    'connection''s post_metrics feed.';
