-- Freeform tags on a Work Item, so work can be grouped across type, status and Workflow.
--
-- A table rather than a column because an item carries several ("autumn-campaign" AND "paid"), and
-- because the set of tags a project uses has to be discoverable — you cannot offer what people already
-- typed if the values are buried in an array. This is the "labels + saved views" shape CLAUDE.md names
-- as the intended way to group work, rather than a container above projects.
--
-- Deliberately NOT the single `tag` column agents and workflows got in V107: one tag per item cannot say
-- "autumn campaign" and "paid" at once, which is usually the whole point of tagging.
CREATE TABLE work_item_tag (
    work_item_id VARCHAR(36) NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    tag          VARCHAR(64) NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- One row per (item, tag): re-adding a tag an item already has is a no-op, not a duplicate.
    PRIMARY KEY (work_item_id, tag)
);

-- Filtering is "which items carry this tag", so the index leads with the tag. The primary key already
-- covers the other direction (an item's own tags).
CREATE INDEX idx_work_item_tag_tag ON work_item_tag (tag);

COMMENT ON TABLE work_item_tag IS
    'Freeform, project-scoped labels on a Work Item. Tags are normalised to lower case on write so '
    '"Autumn" and "autumn" are the same tag rather than two that look identical in a filter list.';
