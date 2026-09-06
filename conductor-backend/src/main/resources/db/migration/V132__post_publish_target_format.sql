-- The shape a destination publishes in: feed (the platform's ordinary post, and what every existing row
-- is), reel, or story. Stored on the target because the same Post can go out as a feed post on one
-- account and a story on another. See PostFormat for what each means and PublishPlatform for which
-- platforms offer which.
ALTER TABLE post_publish_target
    ADD COLUMN format VARCHAR(16) NOT NULL DEFAULT 'FEED';
