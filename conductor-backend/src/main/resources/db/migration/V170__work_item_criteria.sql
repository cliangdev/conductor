-- Acceptance criteria on a Work Item: ordered rows with a verification kind ('auto' = test-verified,
-- 'manual' = a person checks), a met flag with evidence, and an optional external reference back to the
-- id the criterion carries in the PRD (e.g. AC-P0-1.1).
CREATE TABLE work_item_criteria (
    id            VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id    VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    work_item_id  VARCHAR(36)  NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    position      INTEGER      NOT NULL DEFAULT 0,
    text          TEXT         NOT NULL,
    kind          VARCHAR(16)  NOT NULL DEFAULT 'auto',
    external_ref  VARCHAR(32),
    met           BOOLEAN      NOT NULL DEFAULT FALSE,
    evidence      TEXT,
    met_at        TIMESTAMP WITH TIME ZONE,
    met_by_label  VARCHAR(128),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_work_item_criterion_kind CHECK (kind IN ('auto', 'manual'))
);

CREATE INDEX idx_work_item_criteria_item ON work_item_criteria (work_item_id, position);
