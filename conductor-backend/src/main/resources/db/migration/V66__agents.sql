-- Agent module, Phase 2: user-managed named Agents (provider personas) per project.
-- An Agent is to a model provider what a connection is to a connector: a configured, named,
-- project-scoped instance. config_json holds generation guardrails (temperature/maxTokens/
-- maxToolTurns); tool_ids holds namespaced tool ids the agent may call (resolved at run time).

CREATE TABLE agents (
    id            VARCHAR(36)  PRIMARY KEY,
    project_id    VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name          VARCHAR(160) NOT NULL,
    slug          VARCHAR(64)  NOT NULL,
    description   TEXT,
    provider      VARCHAR(32)  NOT NULL,
    model         VARCHAR(128),
    system_prompt TEXT,
    config_json   JSONB        NOT NULL DEFAULT '{}',
    tool_ids      JSONB        NOT NULL DEFAULT '[]',
    state         VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_agents_project_slug UNIQUE (project_id, slug),
    CONSTRAINT ck_agents_state CHECK (state IN ('DRAFT', 'ACTIVE'))
);

CREATE INDEX idx_agents_project ON agents (project_id);
