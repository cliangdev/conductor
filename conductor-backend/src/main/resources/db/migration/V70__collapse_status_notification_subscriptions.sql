-- Wave 2: the per-status notification events (ISSUE_SUBMITTED, ISSUE_APPROVED, ISSUE_IN_PROGRESS,
-- ISSUE_IN_CODE_REVIEW, ISSUE_COMPLETED) were collapsed into the single Workflow-agnostic
-- ISSUE_STATUS_CHANGED. Migrate existing subscriptions so a project that subscribed to any removed status
-- event keeps receiving status notifications via ISSUE_STATUS_CHANGED, then drop the defunct rows.

-- Group-based config (current model): a per-(config, event_type) row set.
INSERT INTO notification_group_config_event (config_id, event_type)
SELECT DISTINCT config_id, 'ISSUE_STATUS_CHANGED'
FROM notification_group_config_event
WHERE event_type IN ('ISSUE_SUBMITTED', 'ISSUE_APPROVED', 'ISSUE_IN_PROGRESS',
                     'ISSUE_IN_CODE_REVIEW', 'ISSUE_COMPLETED')
ON CONFLICT (config_id, event_type) DO NOTHING;

DELETE FROM notification_group_config_event
WHERE event_type IN ('ISSUE_SUBMITTED', 'ISSUE_APPROVED', 'ISSUE_IN_PROGRESS',
                     'ISSUE_IN_CODE_REVIEW', 'ISSUE_COMPLETED');

-- Legacy per-event channel config (V10, superseded): remap one row per project to ISSUE_STATUS_CHANGED
-- (unique on project_id+event_type), then drop the rest so no row references a removed EventType.
UPDATE notification_channel_config c
SET event_type = 'ISSUE_STATUS_CHANGED'
WHERE c.event_type IN ('ISSUE_SUBMITTED', 'ISSUE_APPROVED', 'ISSUE_IN_PROGRESS',
                       'ISSUE_IN_CODE_REVIEW', 'ISSUE_COMPLETED')
  AND NOT EXISTS (
        SELECT 1 FROM notification_channel_config c2
        WHERE c2.project_id = c.project_id AND c2.event_type = 'ISSUE_STATUS_CHANGED');

DELETE FROM notification_channel_config
WHERE event_type IN ('ISSUE_SUBMITTED', 'ISSUE_APPROVED', 'ISSUE_IN_PROGRESS',
                     'ISSUE_IN_CODE_REVIEW', 'ISSUE_COMPLETED');
