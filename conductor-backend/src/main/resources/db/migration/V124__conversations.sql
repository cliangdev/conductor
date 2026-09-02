-- Addressable agents (e.g. the CEO agent) need a place to hold a running back-and-forth with a human
-- or an external channel (Discord thread) rather than the one-shot AgentRunRequest -> AgentRunResult
-- shape agent_runs was built for. A conversation is that container; conversation_messages is its
-- ordered turn log. Each turn that goes to the model also produces an agent_runs row (soft-linked via
-- agent_run_id, no FK -- agent_runs already outlives its originating workflow_run the same way).
--
-- channel_key follows the connector-framework convention of a single opaque per-channel identity
-- string; for Discord it packs '<guild_id>:<thread_id>' so a project can have at most one live
-- conversation per Discord thread. It is optional (api-channel conversations have none), so the
-- uniqueness guard is a partial index rather than a table-wide UNIQUE.
--
-- created_by_user_id / created_by_label follows project_docs' attribution pattern (see the V109
-- migration): the FK is nullable and a label carries a machine actor's identity, with a CHECK ensuring
-- a byline is always present one way or the other.

CREATE TABLE conversations (
    id                 VARCHAR(36)  PRIMARY KEY,
    project_id         VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    agent_id           VARCHAR(36)  NOT NULL REFERENCES agents(id),
    channel            VARCHAR(20)  NOT NULL,
    channel_key        VARCHAR(200),
    title              VARCHAR(200),
    created_by_user_id VARCHAR(36)  REFERENCES users(id),
    created_by_label   VARCHAR(255),
    metadata_json      JSONB,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_message_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_conversations_channel CHECK (channel IN ('api', 'discord')),
    CONSTRAINT ck_conversations_created_attribution
        CHECK (created_by_user_id IS NOT NULL OR created_by_label IS NOT NULL)
);

-- One live conversation per (project, channel, channel_key); api-channel rows have no channel_key and
-- are excluded from the guard.
CREATE UNIQUE INDEX uq_conversations_project_channel_key
    ON conversations (project_id, channel, channel_key) WHERE channel_key IS NOT NULL;

-- Project-scoped, most-recent-first listing.
CREATE INDEX idx_conversations_project_last_message ON conversations (project_id, last_message_at DESC);

CREATE TABLE conversation_messages (
    id                   VARCHAR(36)  PRIMARY KEY,
    conversation_id      VARCHAR(36)  NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role                 VARCHAR(20)  NOT NULL,
    content              TEXT         NOT NULL,
    status               VARCHAR(20)  NOT NULL DEFAULT 'COMPLETED',
    agent_run_id         VARCHAR(36),
    external_message_id  VARCHAR(100),
    author_label         VARCHAR(100),
    error_reason         VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_conversation_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_conversation_messages_status CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

-- Ordered turn log per conversation.
CREATE INDEX idx_conversation_messages_conversation ON conversation_messages (conversation_id, created_at);
