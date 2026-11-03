-- Editable slideshow text, T3: when a slide-text edit is applied to a Creative, every unpublished Post that uses it
-- is moved onto the re-rendered slides, and each such move is recorded here so the Post page can show what changed
-- since the last review. One row per (Post, revision); the unique index makes the propagation idempotent.
-- Additive only (preview deploys share the prod DB). Ids are VARCHAR(36) throughout this schema.
CREATE TABLE IF NOT EXISTS post_content_change (
    id                  VARCHAR(36) PRIMARY KEY,
    project_id          VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    work_item_id        VARCHAR(36) NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    creative_id         VARCHAR(36) NOT NULL REFERENCES creative(id) ON DELETE CASCADE,
    revision_id         VARCHAR(36) NOT NULL REFERENCES creative_revision(id) ON DELETE CASCADE,
    -- EDITOR | REVIEWER | MCP (the revision's source)
    source              VARCHAR(16) NOT NULL,
    actor_user_id       VARCHAR(36) REFERENCES users(id) ON DELETE SET NULL,
    from_status         VARCHAR(64),
    to_status           VARCHAR(64),
    review_round_before INT NOT NULL,
    review_round_after  INT NOT NULL,
    -- A copy of the revision's text_diff: [{slide, path, old, new}].
    text_diff           jsonb NOT NULL DEFAULT '[]',
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_post_content_change_item ON post_content_change (work_item_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_post_content_change_item_revision ON post_content_change (work_item_id, revision_id);
