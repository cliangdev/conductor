-- Health of a Work Item (typically a PRD): an agent drafts an update, a person confirms it. The current health
-- is the latest *confirmed* row; a newer unconfirmed row (under 48 hours old) is a pending draft.
CREATE TABLE work_item_health_updates (
    id                VARCHAR(36)  NOT NULL PRIMARY KEY,
    project_id        VARCHAR(36)  NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    work_item_id      VARCHAR(36)  NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    health            VARCHAR(12)  NOT NULL,
    note              TEXT         NOT NULL,
    drafted_by_label  VARCHAR(255),
    confirmed_by      VARCHAR(36)  REFERENCES users(id) ON DELETE SET NULL,
    confirmed_at      TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_work_item_health CHECK (health IN ('ON_TRACK', 'AT_RISK', 'OFF_TRACK'))
);

CREATE INDEX idx_work_item_health_updates_item
    ON work_item_health_updates (work_item_id, created_at DESC);
