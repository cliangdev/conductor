-- Domain registry for the Knowledge Center: one row per top-level wiki area (engineering, product,
-- marketing, finance, people, ...), each pointing at its own <slug>/_schema.md schema page and owning
-- an optional specialist agent. `state` distinguishes seeded/admin-managed domains (ACTIVE) from
-- librarian-raised gap reports (SUGGESTED, DISMISSED) -- included now so Phase 3 (gap reports) needs
-- no second migration. No FK on owning_agent_slug: agents are deletable and dispatch falls back to the
-- generalist librarian, so a dangling slug is an expected, harmless state rather than an integrity error.
CREATE TABLE knowledge_domains (
    id                    VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id            VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    slug                  VARCHAR(64) NOT NULL,
    display_name          VARCHAR(255) NOT NULL,
    description           TEXT,
    path_prefix           VARCHAR(255) NOT NULL,
    schema_page_path      VARCHAR(512) NOT NULL,
    source_type_patterns  JSONB NOT NULL DEFAULT '[]'::jsonb,
    owning_agent_slug     VARCHAR(100),
    state                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | SUGGESTED | DISMISSED
    suggested_by          VARCHAR(100),
    suggestion_reason     TEXT,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    UNIQUE (project_id, slug)
);

CREATE INDEX idx_knowledge_domains_project_state ON knowledge_domains (project_id, state);
