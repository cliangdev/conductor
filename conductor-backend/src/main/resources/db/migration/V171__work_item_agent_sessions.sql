-- Liveness of an agent run on a Work Item: one row per (item, session_key), upserted as the agent reports.
-- Distinct from work_item_step_runs, which records what a transition's Step produced for a Review gate.
CREATE TABLE work_item_agent_sessions (
    id                 VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id         VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    work_item_id       VARCHAR(36)  NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    session_key        VARCHAR(128) NOT NULL,
    agent_label        VARCHAR(128) NOT NULL,
    mode               VARCHAR(16)  NOT NULL DEFAULT 'DELEGATE',
    activity           VARCHAR(16)  NOT NULL,
    note               TEXT,
    step_run_id        VARCHAR(36)  REFERENCES work_item_step_runs(id) ON DELETE SET NULL,
    started_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    last_heartbeat_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    ended_at           TIMESTAMP WITH TIME ZONE,
    CONSTRAINT chk_work_item_agent_session_mode CHECK (mode IN ('DELEGATE', 'ASSIGNEE')),
    CONSTRAINT chk_work_item_agent_session_activity
        CHECK (activity IN ('WORKING', 'NEEDS_INPUT', 'DONE', 'ERRORED')),
    CONSTRAINT uq_work_item_agent_session UNIQUE (work_item_id, session_key)
);

CREATE INDEX idx_work_item_agent_sessions_open
    ON work_item_agent_sessions (work_item_id, last_heartbeat_at DESC) WHERE ended_at IS NULL;
