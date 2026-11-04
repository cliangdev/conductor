-- Milestones: a named, optionally dated grouping of Work Items within a project (a release, a quarter's
-- theme). Deleting one detaches its items rather than deleting them.
CREATE TABLE milestones (
    id          VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id  VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    description TEXT,
    target_on   DATE,
    state       VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    sort_order  INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_milestone_project_name UNIQUE (project_id, name),
    CONSTRAINT chk_milestone_state CHECK (state IN ('OPEN', 'CLOSED'))
);

CREATE INDEX idx_milestones_project ON milestones (project_id, sort_order);

ALTER TABLE work_items
    ADD CONSTRAINT work_items_milestone_fk
        FOREIGN KEY (milestone_id) REFERENCES milestones(id) ON DELETE SET NULL;
