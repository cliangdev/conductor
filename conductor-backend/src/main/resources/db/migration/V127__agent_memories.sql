-- Agent memory subsystem (Phase 1): a workspace-scoped store for facts/decisions/preferences/events an
-- agent accumulates across conversations, distinct from knowledge_pages (the human-facing wiki) and
-- agent_runs (per-turn transcripts). A memory is "live" iff valid_to IS NULL; supersession closes the old
-- row (valid_to set, superseded_by pointing at the replacement) rather than mutating content in place, so
-- history is always reconstructible. RAW rows are agent-authored, unreviewed extractions; a later
-- consolidation pass (Phase 4) promotes durable ones to ACTIVE. No controllers/tools yet -- those land in
-- later phases.

CREATE TABLE agent_memories (
    id                      VARCHAR(36) PRIMARY KEY DEFAULT gen_random_uuid()::text,
    project_id              VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    agent_id                VARCHAR(36) NULL REFERENCES agents(id) ON DELETE SET NULL,
    source_conversation_id  VARCHAR(36) NULL,
    memory_type             VARCHAR(20) NOT NULL,
    status                  VARCHAR(20) NOT NULL DEFAULT 'RAW',
    content                 TEXT NOT NULL,
    importance              INT NOT NULL DEFAULT 5,
    valid_from              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    valid_to                TIMESTAMPTZ NULL,
    superseded_by           VARCHAR(36) NULL REFERENCES agent_memories(id) ON DELETE SET NULL,
    consolidation_attempts  INT NOT NULL DEFAULT 0,
    promoted_at             TIMESTAMPTZ NULL,
    last_accessed_at        TIMESTAMPTZ NULL,
    access_count            INT NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    search_vector           tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED
);

CREATE INDEX idx_agent_memories_search ON agent_memories USING GIN (search_vector);
CREATE INDEX idx_agent_memories_project_live ON agent_memories (project_id, valid_to);
CREATE INDEX idx_agent_memories_consolidation ON agent_memories (status, created_at);
