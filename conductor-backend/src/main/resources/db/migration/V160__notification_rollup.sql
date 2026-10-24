-- The rolling Discord message for a notification channel.
--
-- One row per notification_group_config: the Discord message currently being edited in place, when it
-- was posted and last touched, and one line per Work Item it summarises. Nothing here is scheduled or
-- swept -- a row is only read and rewritten when the next notification event for that channel arrives,
-- and whether it is still "current" is a comparison of updated_at against the clock at that moment.
--
-- lines is a jsonb object keyed by Work Item id; each value carries the display id, title, noun, latest
-- status (id, label, category), link, an optional verdict note and the order the item was first seen in
-- (jsonb does not keep key order, so the order is data).
CREATE TABLE notification_rollup (
    config_id   VARCHAR(36) NOT NULL PRIMARY KEY
                REFERENCES notification_group_config(id) ON DELETE CASCADE,
    message_id  VARCHAR(64) NOT NULL,
    started_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    lines       JSONB NOT NULL DEFAULT '{}'::jsonb
);

-- Per-channel delivery setting, changed at runtime from Settings -> Notifications. EACH sends every
-- event as its own message; GROUPED edits one rolling message while the channel was touched within
-- group_window_minutes. Existing channels move to GROUPED (15 min) on purpose, so they get consolidated
-- delivery by default even though they sent one message per event until now; a channel can switch back to
-- EACH in Settings -> Notifications.
ALTER TABLE notification_group_config
    ADD COLUMN delivery_mode VARCHAR(20) NOT NULL DEFAULT 'GROUPED',
    ADD COLUMN group_window_minutes INT NOT NULL DEFAULT 15,
    ADD CONSTRAINT ck_notification_group_delivery_mode CHECK (delivery_mode IN ('EACH', 'GROUPED')),
    ADD CONSTRAINT ck_notification_group_window CHECK (group_window_minutes BETWEEN 1 AND 1440);
