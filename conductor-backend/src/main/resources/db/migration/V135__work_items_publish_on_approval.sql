-- "Publish as soon as it is approved": the Post carries no fire time until it enters its scheduled status,
-- and then takes the earliest time every destination accepts (each platform's minimum notice from that
-- moment). The stamped time is derived, so it is not part of the approved bundle.
ALTER TABLE work_items ADD COLUMN publish_on_approval BOOLEAN NOT NULL DEFAULT FALSE;
