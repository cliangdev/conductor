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

-- Legacy per-event channel config (V10, superseded by group config): for each project that subscribed to a
-- removed status event but has no ISSUE_STATUS_CHANGED row, promote exactly ONE removed-event row to
-- ISSUE_STATUS_CHANGED (DISTINCT ON avoids violating the unique (project_id, event_type) constraint), then
-- drop all remaining removed-event rows so none references a deleted EventType.
INSERT INTO notification_channel_config (id, project_id, event_type, provider, webhook_url, enabled, created_at, updated_at)
SELECT gen_random_uuid()::text, sub.project_id, 'ISSUE_STATUS_CHANGED', sub.provider, sub.webhook_url, sub.enabled, NOW(), NOW()
FROM (
    SELECT DISTINCT ON (project_id) project_id, provider, webhook_url, enabled
    FROM notification_channel_config
    WHERE event_type IN ('ISSUE_SUBMITTED', 'ISSUE_APPROVED', 'ISSUE_IN_PROGRESS',
                         'ISSUE_IN_CODE_REVIEW', 'ISSUE_COMPLETED')
    ORDER BY project_id
) sub
WHERE NOT EXISTS (
        SELECT 1 FROM notification_channel_config c2
        WHERE c2.project_id = sub.project_id AND c2.event_type = 'ISSUE_STATUS_CHANGED');

DELETE FROM notification_channel_config
WHERE event_type IN ('ISSUE_SUBMITTED', 'ISSUE_APPROVED', 'ISSUE_IN_PROGRESS',
                     'ISSUE_IN_CODE_REVIEW', 'ISSUE_COMPLETED');
