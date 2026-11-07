-- Weekly provider-call counter for the AI-assistant visibility check (RE-99 T7.4 follow-up). The cap of provider
-- calls per workspace per ISO week counts every call a run attempts, failed or not. A failed call stores no
-- reading (a stored row always means the assistant answered), so the cap cannot be counted from
-- marketing_ai_visibility_check alone: reruns after failures would each get a fresh allowance. One row per
-- (project, week); week_start is that week's Monday, UTC. Additive only; never read as a result.
CREATE TABLE marketing_ai_call_budget (
    project_id VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    week_start DATE        NOT NULL,
    calls      INT         NOT NULL CHECK (calls >= 0),
    PRIMARY KEY (project_id, week_start)
);
