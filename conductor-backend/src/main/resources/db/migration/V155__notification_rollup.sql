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
