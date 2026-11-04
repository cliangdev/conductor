-- Editable slideshow text: a CAROUSEL Creative may keep the spec its slides were composed from
-- (creative.slideshow_spec), and every change to it is recorded as a revision (creative_revision).
-- Additive and nullable only (preview deploys share the prod DB); null means "no spec stored".
-- Ids are VARCHAR(36) throughout this schema.
ALTER TABLE creative ADD COLUMN IF NOT EXISTS slideshow_spec jsonb;

CREATE TABLE IF NOT EXISTS creative_revision (
    id               VARCHAR(36) PRIMARY KEY,
    project_id       VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    creative_id      VARCHAR(36) NOT NULL REFERENCES creative(id) ON DELETE CASCADE,
    -- 1.. per Creative.
    revision_no      INT NOT NULL,
    -- The Creative's optimistic-lock version AFTER this revision applied.
    creative_version INT NOT NULL,
    -- PENDING | APPLIED | FAILED
    status           VARCHAR(16) NOT NULL,
    -- EDITOR | REVIEWER | MCP | IMPORT | COMMIT
    source           VARCHAR(16) NOT NULL,
    spec             jsonb,
    slides           jsonb,
    slide_alt_text   jsonb,
    slide_style      jsonb,
    -- [{slide, path, old, new}] — the editable text this revision changed.
    text_diff        jsonb NOT NULL DEFAULT '[]',
    checks           jsonb,
    author_user_id   VARCHAR(36) REFERENCES users(id),
    post_id          VARCHAR(36),
    review_round     INT,
    workflow_run_id  VARCHAR(36),
    render_id        VARCHAR(36),
    error            TEXT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    applied_at       TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uq_creative_revision_no UNIQUE (creative_id, revision_no)
);

CREATE INDEX IF NOT EXISTS idx_creative_revision_creative ON creative_revision (creative_id, revision_no DESC);
CREATE INDEX IF NOT EXISTS idx_creative_revision_project ON creative_revision (project_id);
