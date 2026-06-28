-- Wave 5: retain an immutable snapshot of every published Workflow definition so in-flight Work Items pin
-- to the version they started on. The workflow_definitions row stays the "header" (identity + editable DRAFT
-- working copy + latest-published pointer); each publish inserts a snapshot here.

CREATE TABLE workflow_definition_versions (
    id                     VARCHAR(36) NOT NULL PRIMARY KEY,
    workflow_definition_id VARCHAR(36) NOT NULL REFERENCES workflow_definitions(id) ON DELETE CASCADE,
    version                INTEGER NOT NULL,
    definition             JSONB NOT NULL,
    schema_version         INTEGER,
    published_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    published_by           VARCHAR(36) REFERENCES users(id),
    CONSTRAINT uq_workflow_definition_version UNIQUE (workflow_definition_id, version)
);

CREATE INDEX idx_workflow_definition_versions_def ON workflow_definition_versions(workflow_definition_id);

-- Backfill a snapshot for every already-PUBLISHED statechart definition at its current version.
INSERT INTO workflow_definition_versions (id, workflow_definition_id, version, definition, schema_version, published_at)
SELECT gen_random_uuid()::text, w.id, COALESCE(w.version, 1), w.definition, w.schema_version, w.updated_at
FROM workflow_definitions w
WHERE w.state = 'PUBLISHED' AND w.definition IS NOT NULL;
