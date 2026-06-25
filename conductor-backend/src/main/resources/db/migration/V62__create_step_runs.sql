-- COND-18 E3 / P0-6: step-run records — what an agent-run Step was asked to do and produced, so a human
-- can judge it at a Review gate. Reported by the local skill via MCP (the engine does not run skills).

CREATE TABLE step_runs (
    id           VARCHAR(36) PRIMARY KEY,
    issue_id     VARCHAR(36)  NOT NULL REFERENCES issues(id) ON DELETE CASCADE,
    workflow     VARCHAR(64),
    from_status  VARCHAR(48),
    to_status    VARCHAR(48),
    step_kind    VARCHAR(32)  NOT NULL,
    skill        VARCHAR(128),
    status       VARCHAR(24)  NOT NULL,
    input_brief  TEXT         NOT NULL,
    reported_by  VARCHAR(128) NOT NULL,
    started_at   TIMESTAMP,
    finished_at  TIMESTAMP,
    produced     JSONB,
    before_after JSONB,
    flags        JSONB,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_step_runs_issue_id ON step_runs(issue_id);
