-- Agent module, Phase 3: AgentRun observability. One row per ReAct execution of an agent — records
-- the redacted transcript, the tool calls, token usage, and outcome for debugging/cost tracking.
-- workflow_run_id is nullable (an agent run may originate outside a workflow: MCP/UI later).

CREATE TABLE agent_runs (
    id               VARCHAR(36)  PRIMARY KEY,
    agent_id         VARCHAR(36)  NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    project_id       VARCHAR(36)  NOT NULL,
    workflow_run_id  VARCHAR(36),
    status           VARCHAR(16)  NOT NULL,
    input_brief      TEXT,
    transcript_json  JSONB,
    tool_calls_json  JSONB,
    token_usage_json JSONB,
    error_reason     TEXT,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ
);

CREATE INDEX idx_agent_runs_agent ON agent_runs (agent_id);
