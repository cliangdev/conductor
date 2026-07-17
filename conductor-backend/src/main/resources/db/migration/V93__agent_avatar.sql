-- Emoji avatars for Agents (AgentAvatarDefaults picks a deterministic default from the slug when
-- unset, so both columns stay nullable with no backfill -- existing rows simply read through the
-- default at the API layer until they're explicitly set or re-provisioned).
ALTER TABLE agents ADD COLUMN avatar_emoji VARCHAR(16), ADD COLUMN avatar_color VARCHAR(32);
