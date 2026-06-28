-- COND-22: opt-in sidebar visibility for lifecycle Workflows.
-- Not part of the versioned statechart definition, so it toggles live (PATCH .../workflows/{id}/sidebar)
-- without republishing. Defaults to false; the V74 seed flips ENGINEERING to true per project.
ALTER TABLE workflow_definitions
    ADD COLUMN sidebar_enabled BOOLEAN NOT NULL DEFAULT false;
