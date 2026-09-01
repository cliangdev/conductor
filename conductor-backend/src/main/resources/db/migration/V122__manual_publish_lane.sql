-- COND-23 / MKT-2: the MANUAL publish lane — a destination a human posts to by hand.
--
-- Before this, post_publish_target.connection_id was NOT NULL REFERENCES connection(id), which made a
-- publish target impossible without a connected account. Combined with PostScheduleValidator's "you must
-- pick at least one target" rule at the approval gate, that meant a project with no social integration
-- could not move a Post past In Review at all — no approval, no schedule, no publish, and no way to record
-- a post that went out by hand. A MANUAL target is that missing row: same Work Item, same platform, same
-- fire time, same review gate and media rules, but no account behind it and no API call at fire time.
ALTER TABLE post_publish_target ALTER COLUMN connection_id DROP NOT NULL;

COMMENT ON COLUMN post_publish_target.connection_id IS
    'The connected account this target publishes through. NULL only for the MANUAL lane, which reaches its '
    'platform through a human rather than a credential.';

-- uq_post_publish_target_item_platform_connection cannot police the manual rows: SQL treats every NULL as
-- distinct, so that constraint would happily admit ten manual TikTok targets on one Post. This partial index
-- restores "one destination per (Post, platform)" over exactly the rows the constraint stops seeing.
CREATE UNIQUE INDEX uq_post_publish_target_item_platform_manual
    ON post_publish_target (work_item_id, platform)
    WHERE connection_id IS NULL;

-- A manual row is the one kind that legitimately has no account behind it; every other lane must still
-- name one. Without this, a bug that dropped a connection id would silently downgrade an automated target
-- into one nothing publishes -- the post would simply never go out, and the row would look scheduled.
ALTER TABLE post_publish_target ADD CONSTRAINT ck_post_publish_target_manual_has_no_connection
    CHECK ((lane = 'MANUAL') = (connection_id IS NULL));
