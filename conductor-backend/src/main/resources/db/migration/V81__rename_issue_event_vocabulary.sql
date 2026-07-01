-- #240 Phase 3: big-bang rename of the last "issue"-named event vocabulary, now that v1 is gone.
--   * EventType enum name : ISSUE_STATUS_CHANGED            -> WORK_ITEM_STATUS_CHANGED
--   * automation trigger  : conductor.issue.status_changed -> conductor.work_item.status_changed
--   * event metadata keys : issueId / issueTitle           -> workItemId / workItemTitle
--   * interpolation refs  : event.issueId / event.issueTitle -> event.workItemId / event.workItemTitle
--
-- The Java code moved to the new names in the same commit; this migration rewrites the persisted rows so
-- no stored value dangles against the renamed contract. Each statement is scoped with a LIKE guard so it
-- only rewrites affected rows and is safe to re-run.

-- 1. Notification subscriptions store the EventType name as a string.
UPDATE notification_channel_config
   SET event_type = 'WORK_ITEM_STATUS_CHANGED'
 WHERE event_type = 'ISSUE_STATUS_CHANGED';

UPDATE notification_group_config_event
   SET event_type = 'WORK_ITEM_STATUS_CHANGED'
 WHERE event_type = 'ISSUE_STATUS_CHANGED';

-- 2. Workflow runs record the trigger type string and an event payload (type + metadata keys).
UPDATE workflow_runs
   SET trigger_type = 'conductor.work_item.status_changed'
 WHERE trigger_type = 'conductor.issue.status_changed';

UPDATE workflow_runs
   SET event_payload = REPLACE(REPLACE(REPLACE(event_payload::text,
           'conductor.issue.status_changed', 'conductor.work_item.status_changed'),
           '"issueId"',    '"workItemId"'),
           '"issueTitle"', '"workItemTitle"')::jsonb
 WHERE event_payload::text LIKE '%conductor.issue.status_changed%'
    OR event_payload::text LIKE '%"issueId"%'
    OR event_payload::text LIKE '%"issueTitle"%';

-- 3. Daemon events (self-hosted runner queue) carry the same payload shape.
UPDATE daemon_events
   SET payload = REPLACE(REPLACE(REPLACE(payload::text,
           'conductor.issue.status_changed', 'conductor.work_item.status_changed'),
           '"issueId"',    '"workItemId"'),
           '"issueTitle"', '"workItemTitle"')::jsonb
 WHERE payload::text LIKE '%conductor.issue.status_changed%'
    OR payload::text LIKE '%"issueId"%'
    OR payload::text LIKE '%"issueTitle"%';

-- 4. Automation workflow YAML: the trigger key and any ${{ event.issueId/issueTitle }} interpolation refs.
UPDATE workflow_definitions
   SET yaml = REPLACE(REPLACE(REPLACE(yaml,
           'conductor.issue.status_changed', 'conductor.work_item.status_changed'),
           'event.issueId',    'event.workItemId'),
           'event.issueTitle', 'event.workItemTitle')
 WHERE yaml LIKE '%conductor.issue.status_changed%'
    OR yaml LIKE '%event.issueId%'
    OR yaml LIKE '%event.issueTitle%';

-- 5. Definition JSONB (header + immutable version snapshots) may embed the same trigger / interpolation refs.
UPDATE workflow_definitions
   SET definition = REPLACE(REPLACE(REPLACE(definition::text,
           'conductor.issue.status_changed', 'conductor.work_item.status_changed'),
           'event.issueId',    'event.workItemId'),
           'event.issueTitle', 'event.workItemTitle')::jsonb
 WHERE definition::text LIKE '%conductor.issue.status_changed%'
    OR definition::text LIKE '%event.issueId%'
    OR definition::text LIKE '%event.issueTitle%';

UPDATE workflow_definition_versions
   SET definition = REPLACE(REPLACE(REPLACE(definition::text,
           'conductor.issue.status_changed', 'conductor.work_item.status_changed'),
           'event.issueId',    'event.workItemId'),
           'event.issueTitle', 'event.workItemTitle')::jsonb
 WHERE definition::text LIKE '%conductor.issue.status_changed%'
    OR definition::text LIKE '%event.issueId%'
    OR definition::text LIKE '%event.issueTitle%';
