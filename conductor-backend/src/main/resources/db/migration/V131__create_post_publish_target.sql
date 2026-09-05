-- COND-23 Marketing Publishing Pipeline: the durable per-(post, target) row the whole pipeline
-- anchors on. One row per (Work Item, platform, account) the post is going out to, carrying the lane
-- it publishes through, its scheduled fire time, and -- once the platform has it -- the platform post
-- id and permalink. It is both the at-most-once anchor for publishing and the record of what a
-- revocation has to undo, so it is written before anything is handed to a platform, never after.
--
-- lane is NATIVE (the platform's own scheduler owns the fire time once we hand the post off) or
-- APP_MANAGED (we hold the post and publish it ourselves when fire_time comes due). The two lanes
-- read this table through different pollers, which is why the (state, fire_time) index exists.
--
-- platform is deliberately its own column rather than being derived from connector_id: a single
-- connection can yield two platforms (one Meta connection publishes to both facebook and instagram),
-- so the "never double-target the same account" guarantee has to be keyed on the triple
-- (work_item_id, platform, connection_id), not on (work_item_id, connection_id).
--
-- idempotency_key is globally unique (like action_invocation's in V88): a given logical publish must
-- resolve to exactly one row regardless of work item or connection, so a retried scheduling pass
-- collides on insert instead of creating a second post.
--
-- No ON DELETE CASCADE on connection_id (unlike work_item_id): disconnecting an account that still
-- has published or in-flight targets must fail loudly rather than silently erase the revocation
-- record for posts that are live on the platform.
CREATE TABLE post_publish_target (
    id                     VARCHAR(36) PRIMARY KEY,
    work_item_id           VARCHAR(36) NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    connector_id           VARCHAR(64) NOT NULL,
    connection_id          VARCHAR(36) NOT NULL REFERENCES connection(id),
    platform               VARCHAR(32) NOT NULL,  -- facebook | instagram | youtube | tiktok
    platform_account_label VARCHAR(255),
    lane                   VARCHAR(16) NOT NULL,  -- NATIVE | APP_MANAGED
    state                  VARCHAR(24) NOT NULL,  -- PENDING | HANDED_OFF | PUBLISHING | PUBLISHED | FAILED | REVOKED
    fire_time              TIMESTAMP WITH TIME ZONE,
    platform_post_id       VARCHAR(255),
    permalink              TEXT,
    error_message          TEXT,
    attempts               INT NOT NULL DEFAULT 0,
    caption_override       TEXT,
    -- Opaque JSON resume state for a chunked media upload (resumable session URI, byte offset, chunk
    -- index). TEXT and uninterpreted by SQL -- only the media-upload code parses it.
    resume_checkpoint      TEXT,
    idempotency_key        VARCHAR(255) NOT NULL,
    created_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_post_publish_target_item_platform_connection UNIQUE (work_item_id, platform, connection_id),
    CONSTRAINT uq_post_publish_target_idempotency_key UNIQUE (idempotency_key)
);

-- Backs both pollers: the APP_MANAGED due poll and the NATIVE hand-off window sweep, each of which
-- filters PENDING rows by fire_time.
CREATE INDEX idx_post_publish_target_due ON post_publish_target (state, fire_time);
