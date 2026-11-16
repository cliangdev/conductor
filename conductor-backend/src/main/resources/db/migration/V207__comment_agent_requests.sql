-- @agent in a comment thread: one row per (thread, agent) request to revise the document and reply.
-- Status is not stored: it is derived from the linked workflow run (see CommentAgentRequestService), and
-- `error` records only a failure to dispatch in the first place (no AI provider, agent inactive, ...).
-- Purely additive, so the previous release keeps working.
CREATE TABLE comment_agent_requests (
    id              VARCHAR(36)  PRIMARY KEY,
    project_id      VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    comment_id      VARCHAR(36)  NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    reply_id        VARCHAR(36)  NULL REFERENCES comment_replies(id) ON DELETE CASCADE,
    agent_id        VARCHAR(36)  NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    workflow_run_id VARCHAR(36)  NULL REFERENCES workflow_runs(id) ON DELETE CASCADE,
    error           TEXT         NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_comment_agent_requests_comment ON comment_agent_requests(comment_id);
CREATE INDEX idx_comment_agent_requests_run ON comment_agent_requests(workflow_run_id) WHERE workflow_run_id IS NOT NULL;

-- "One in flight per thread and agent" means: no run yet, or the run is not terminal. An index cannot look at
-- another table's status, so this only closes the window before a run exists (two mentions racing to dispatch);
-- the rest is enforced in CommentAgentRequestService under a lock on the comment row.
CREATE UNIQUE INDEX uq_comment_agent_requests_pending
    ON comment_agent_requests(comment_id, agent_id)
    WHERE workflow_run_id IS NULL AND error IS NULL;
