-- COND-23: an approval has to cover an entire publish bundle, not merely "this Work Item was once
-- approved". Two independent ways a stale approval could otherwise let something publish:
--
-- 1. The bundle changes under a standing approval. A reviewer approves a Post, the author then edits
--    the caption, adds a target account, moves the fire time, or swaps the media -- and the APPROVED
--    review row still satisfies the gate. bundle_hash pins an approval to the exact bundle it saw:
--    the gate holds only while the Post still hashes to the value recorded here.
--
-- 2. A stale approval survives a review round. reviews upserts per (work_item, reviewer), so a single
--    reviewer flipping to CHANGES_REQUESTED overwrites their own APPROVED row and the gate correctly
--    re-blocks. But when reviewer A approves and reviewer B then requests changes, A's APPROVED row
--    survives the routing back to CHANGES_REQUESTED and satisfies the gate the moment the Post is
--    resubmitted. review_round scopes an approval to the round it was cast in; work_items carries the
--    round counter, bumped whenever a CHANGES_REQUESTED verdict routes an item out of review.
--
-- Both review columns are NULLABLE on purpose, and null means "unbound, behaves exactly as before":
-- every pre-existing review (all ENGINEERING approvals among them) keeps satisfying its gate with no
-- backfill. Only reviews written after this migration carry a round, and only reviews on an item that
-- actually has publish targets carry a bundle hash.
ALTER TABLE reviews
    ADD COLUMN bundle_hash  VARCHAR(64),   -- hex SHA-256 of the publish bundle; null = not bundle-bound
    ADD COLUMN review_round INTEGER;       -- round this verdict was cast in; null = pre-existing review

-- The item-side counter the review round is compared against. NOT NULL DEFAULT 0 so every existing row
-- starts at round 0 and matches nothing (pre-existing reviews are null-round and skip the check anyway).
ALTER TABLE work_items
    ADD COLUMN current_review_round INTEGER NOT NULL DEFAULT 0;
