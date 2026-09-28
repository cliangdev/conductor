-- COND-24 T3: renders and rendered frames. Rendering happens on the user's machine (Playwright via the
-- Conductor MCP server / CLI, or a self-hosted Workflow) — the backend never launches anything; it only
-- records a render, stores the frames a local job PUTs, and later copies them into a Post's own assets on
-- attach. See docs/creatives.md.

CREATE TABLE creative_render (
    id               VARCHAR(36) PRIMARY KEY,
    project_id       VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    creative_id      VARCHAR(36) NOT NULL REFERENCES creative(id) ON DELETE CASCADE,
    -- The Creative's optimistic-lock version at request time, so a render can always be traced back to
    -- exactly the concept it rendered, even after the Creative has since changed.
    creative_version INT NOT NULL,
    state            VARCHAR(16) NOT NULL DEFAULT 'RUNNING',
    preview_only     BOOLEAN NOT NULL DEFAULT FALSE,
    -- Free text naming what ran the job: mcp, cli, workflow. Never validated against a fixed list — a new
    -- local runner needs no migration.
    renderer         VARCHAR(32),
    workflow_run_id  VARCHAR(36),
    requested_by     VARCHAR(36) REFERENCES users(id),
    requested_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    finished_at      TIMESTAMP WITH TIME ZONE,
    error            TEXT,
    log              TEXT
);

CREATE INDEX idx_creative_render_creative ON creative_render (creative_id, requested_at DESC);
CREATE INDEX idx_creative_render_project ON creative_render (project_id);
-- Supports the lazy timeout-on-read sweep: "every RUNNING render older than 30 minutes" without a full scan.
CREATE INDEX idx_creative_render_running ON creative_render (state, requested_at) WHERE state = 'RUNNING';

COMMENT ON TABLE creative_render IS
    'One local render of a Creative (COND-24 T3): RUNNING while frames are being PUT, then SUCCEEDED or '
    'FAILED. No scheduler ever launches or watches this row — a stale RUNNING row is only ever noticed '
    'and flipped to FAILED the next time it is read.';

CREATE TABLE creative_render_frame (
    id             VARCHAR(36) PRIMARY KEY,
    render_id      VARCHAR(36) NOT NULL REFERENCES creative_render(id) ON DELETE CASCADE,
    creative_id    VARCHAR(36) NOT NULL REFERENCES creative(id) ON DELETE CASCADE,
    -- A placement key from the registry (9x16, 4x5, 1x1, story, ...), or the literal "sheet" for a
    -- preview-only render's contact sheet.
    placement_key  VARCHAR(32) NOT NULL,
    -- Derived from the registry at PUT time; null for "sheet", which is not a real placement.
    platform       VARCHAR(32),
    -- Position within a story/carousel sequence; null for a single-frame placement. NULL is a value here
    -- (two different sequence beats are two different frames), so the unique index below coalesces it.
    sequence_index INT,
    gcs_path       TEXT NOT NULL,
    content_type   VARCHAR(100) NOT NULL,
    width          INT NOT NULL,
    height         INT NOT NULL,
    size_bytes     BIGINT NOT NULL,
    warnings       JSONB NOT NULL DEFAULT '[]',
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_creative_render_frame_position
    ON creative_render_frame (render_id, placement_key, COALESCE(sequence_index, -1));
CREATE INDEX idx_creative_render_frame_render ON creative_render_frame (render_id);
CREATE INDEX idx_creative_render_frame_creative ON creative_render_frame (creative_id);

COMMENT ON TABLE creative_render_frame IS
    'One rendered PNG (COND-24 T3): a placement, an optional sequence index, and where it lives in '
    'storage. Frame order is content and is never re-sorted — see CreativePlacementTargetMapper.';

ALTER TABLE assets ADD COLUMN creative_frame_id VARCHAR(36) NULL REFERENCES creative_render_frame(id) ON DELETE SET NULL;

COMMENT ON COLUMN assets.creative_frame_id IS
    'Set when this Post asset was copied from a Creative render frame on attach (COND-24 T3), so the '
    'asset remembers the frame — and therefore the Creative and placement — it came from.';
