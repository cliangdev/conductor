-- The built-in MARKETING_CHANGES lifecycle ("Changes") is written by MCP and the marketing report skill, so as
-- a Marketing sidebar row it sat empty and confused users. New workspaces now seed it hidden (like TASK); this
-- hides it in existing ones. Open Changes surface on Marketing Overview instead; /marketing/changes still routes.
-- Matches name too, so a user-made workflow that merely reuses the slug is left alone, and a row a user already
-- hid is skipped. sidebar_enabled is a header column only (V73): it is not in the definition JSON or snapshots.
-- Replays are no-ops.
UPDATE workflow_definitions
SET sidebar_enabled = false,
    updated_at = NOW()
WHERE definition ->> 'id' = 'MARKETING_CHANGES'
  AND name = 'MARKETING_CHANGES'
  AND sidebar_enabled = true;
