-- Extend lifecycle state machine with DISABLED.
-- A DISABLED workflow is hidden from sidebar + new WI creation by the state=PUBLISHED filter.
-- Existing Work Items keep resolving their pinned (slug, version) snapshot.
ALTER TABLE workflow_definitions DROP CONSTRAINT workflow_definitions_state_check;

ALTER TABLE workflow_definitions
    ADD CONSTRAINT workflow_definitions_state_check
        CHECK (state IS NULL OR state IN ('DRAFT', 'PUBLISHED', 'DISABLED'));
