-- Dependencies between Work Items. A row reads "work_item KIND related": (A, B, BLOCKS) means A blocks B,
-- (C, D, DISCOVERED_FROM) means C was discovered while working on D. The inverse view (blockedBy, discovered)
-- is a read projection, not a second row. Same-project, the per-item BLOCKS cap and acyclicity are enforced
-- by WorkItemDependencyService; the database guards the shape.
CREATE TABLE work_item_dependencies (
    id                    VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id            VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    work_item_id          VARCHAR(36)  NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    related_work_item_id  VARCHAR(36)  NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    kind                  VARCHAR(24)  NOT NULL,
    created_by            VARCHAR(36)  REFERENCES users(id) ON DELETE SET NULL,
    created_by_label      VARCHAR(128),
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_work_item_dependency_kind CHECK (kind IN ('BLOCKS', 'DISCOVERED_FROM')),
    CONSTRAINT chk_work_item_dependency_not_self CHECK (work_item_id <> related_work_item_id),
    CONSTRAINT uq_work_item_dependency UNIQUE (work_item_id, related_work_item_id, kind)
);

CREATE INDEX idx_work_item_dependencies_item ON work_item_dependencies (work_item_id, kind);
CREATE INDEX idx_work_item_dependencies_related ON work_item_dependencies (related_work_item_id, kind);
CREATE INDEX idx_work_item_dependencies_project ON work_item_dependencies (project_id);
