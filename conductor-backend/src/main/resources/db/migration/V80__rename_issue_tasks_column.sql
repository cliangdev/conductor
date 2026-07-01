-- Final naming pass of the Issue -> Work Item migration (#240): rename the last "issue"-named
-- column on work_items now that the v1 issue surface is fully retired. Metadata-only rename;
-- existing JSONB data is preserved.
ALTER TABLE work_items RENAME COLUMN issue_tasks TO work_item_tasks;
