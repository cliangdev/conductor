-- Durable row for one outbound ActionConnector invocation. Mirrors webhook_event's (V8/V53/V57)
-- idempotency + attempt/retry-sweep/dead-letter shape, but for OUTBOUND actions instead of inbound
-- deliveries: idempotency_key is caller-supplied (e.g. "wfstep:<jobRunId>:<stepId>" for workflow
-- steps) rather than a provider delivery id, and is globally unique — a given key must resolve to
-- exactly one invocation regardless of connection.
CREATE TABLE action_invocation (
    id                VARCHAR(36) PRIMARY KEY,
    project_id        VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    connection_id     VARCHAR(36) NOT NULL REFERENCES connection(id) ON DELETE CASCADE,
    connector_id      VARCHAR(64) NOT NULL,
    action_id         VARCHAR(100) NOT NULL,
    idempotency_key   VARCHAR(255) NOT NULL UNIQUE,
    input_json        JSONB NOT NULL DEFAULT '{}',
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts          INT NOT NULL DEFAULT 0,
    last_attempted_at TIMESTAMP WITH TIME ZONE,
    error_message     TEXT,
    output_json       JSONB,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_action_invocation_status ON action_invocation (status, last_attempted_at);
CREATE INDEX idx_action_invocation_connection ON action_invocation (connection_id);
