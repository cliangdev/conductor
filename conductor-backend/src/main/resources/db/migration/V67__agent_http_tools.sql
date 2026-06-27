-- Agent module, Phase 3: reusable, project-scoped HTTP tool definitions (the generic escape hatch).
-- Backs HttpToolProvider — a named REST endpoint an agent may call. url_template/headers_json/
-- body_template are rendered at invoke time with the model-supplied args plus the project's
-- workflow secrets; input_schema_json is the JSON Schema advertised to the model.

CREATE TABLE agent_http_tools (
    id                VARCHAR(36)  PRIMARY KEY,
    project_id        VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name              VARCHAR(160) NOT NULL,
    slug              VARCHAR(64)  NOT NULL,
    description       TEXT,
    method            VARCHAR(10)  NOT NULL DEFAULT 'GET',
    url_template      TEXT         NOT NULL,
    headers_json      JSONB        NOT NULL DEFAULT '{}',
    body_template     TEXT,
    input_schema_json JSONB        NOT NULL DEFAULT '{}',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_agent_http_tools_project_slug UNIQUE (project_id, slug)
);

CREATE INDEX idx_agent_http_tools_project ON agent_http_tools (project_id);
