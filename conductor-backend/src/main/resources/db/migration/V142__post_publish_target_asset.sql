-- COND-23 follow-up: per-target media selection.
--
-- Until now a Post had one media set and every destination got all of it -- and then each publish
-- action quietly collapsed that set to a single asset (video winning over image). That is wrong in
-- both directions. A 9:16 Reel and a 4:5 feed image are the same Post but not the same file, so an
-- author must be able to say which file goes where; and Instagram, Facebook and TikTok all publish
-- several assets in one post, which the single-asset collapse made unreachable.
--
-- Selection is expressed as an ordered subset of the Post's own assets. There is no crop, resize or
-- re-encode anywhere in this feature: the author uploads the versions they want and picks per
-- destination, which is what every mainstream scheduler does and the only approach that does not
-- guess at an output the platform may reject anyway.

-- Inherit-vs-explicit is a flag, not "the join table is empty".
--
-- Those two states have to be distinguishable. A target inheriting the Post's media follows the Post
-- as files are added and removed, which is the right default and what every existing row means. A
-- target with an explicit selection whose files were then deleted has no media at all -- and must be
-- reported at the approval gate, not silently re-inherit the whole Post and publish files nobody
-- chose. Without this column the second state is indistinguishable from the first.
ALTER TABLE post_publish_target ADD COLUMN custom_media BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN post_publish_target.custom_media IS
    'FALSE: this target publishes the Post''s whole media set, in Post order, and follows it as it '
    'changes. TRUE: it publishes exactly the rows in post_publish_target_asset, in their position '
    'order -- including when there are none left, which the approval gate refuses rather than '
    'treating as "everything".';

CREATE TABLE post_publish_target_asset (
    target_id VARCHAR(36) NOT NULL REFERENCES post_publish_target(id) ON DELETE CASCADE,
    -- ON DELETE CASCADE, unlike connection_id on the parent: an asset can only be deleted before the
    -- Post goes for review (AssetService guards it), so a cascade here can never rewrite a bundle
    -- somebody has already approved. It leaves the target explicit-and-empty, which custom_media
    -- keeps legible and the gate then refuses.
    asset_id  VARCHAR(36) NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    -- Carousel order is content, not presentation: Instagram crops every item to the first item's
    -- aspect ratio, and TikTok takes its cover from the first photo. So the order is stored, and it
    -- is part of what an approval is bound to.
    position  INT NOT NULL,
    PRIMARY KEY (target_id, asset_id),
    -- One asset cannot occupy two positions in the same target, nor two assets one position. The
    -- primary key already stops an asset appearing twice.
    CONSTRAINT uq_post_publish_target_asset_position UNIQUE (target_id, position)
);

-- "Which targets does this asset appear in?" -- read whenever an asset is deleted or a Post's media
-- is being reconciled, and unindexed the primary key cannot answer it.
CREATE INDEX idx_post_publish_target_asset_asset ON post_publish_target_asset (asset_id);

COMMENT ON TABLE post_publish_target_asset IS
    'The ordered media one publish target sends, when it does not simply inherit the Post''s. Read '
    'only where custom_media is TRUE.';
