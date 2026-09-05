-- MKT-1: the creator's posting consent, persisted and therefore enforceable.
--
-- TikTok's Content Sharing Guidelines require that a creator sees a preview of the content and the
-- account it will post to, and expressly consents, *before* anything is uploaded. TIK-2 built that step
-- and TIK-4 gated the status control on it -- but the consent itself lived in React component state. It
-- did not survive a reload, and, far worse, it was invisible to every client that is not the web UI: the
-- MCP server, the CLI and any agent driving the pipeline could take a TikTok-targeted Post through review
-- and out to the platform having asked nobody. A compliance control that exists in one client is not a
-- control, so consent moves here where the backend can refuse the transition.
--
-- ## Its own table, not columns on work_items
--
-- work_items is the one table every Workflow shares -- every ENGINEERING PRD, every knowledge item. Three
-- publishing-specific columns there would be NULL on all of them, and the entity that the whole
-- application loads on every read would carry a marketing compliance concern. This is the same call V126
-- made for post_publish_target: a Post's publishing state hangs off work_items rather than widening it.
--
-- The absence of a row is meaningful and needs no backfill: no row means consent was never given, which is
-- the correct reading of every Post that exists today. One row per Work Item (uq below), rewritten when
-- consent is given again -- what the gate asks is "is consent valid *now*", so a superseded consent has no
-- reader. Withdrawing consent deletes the row.
--
-- ## Why a hash and not a boolean
--
-- Consent is to *this* content going to *these* accounts under *these* options. Swap the destination
-- account, change a privacy level, or upload a different cut and what the creator agreed to no longer
-- exists -- so a boolean would keep saying yes to a post nobody has ever seen. consent_hash records what
-- was consented to: a hex SHA-256 over the Post's target set (platform, account, publish options) and its
-- uploaded asset set, canonicalised exactly the way PublishBundleHasher canonicalises its bundle hash
-- (sorted keys, collections ordered by their own serialization). Consent is valid only while the Post
-- still hashes to the value stored here; any of those edits silently withdraws it, which is the point.
--
-- Deliberately a narrower subject than reviews.bundle_hash (V129): that one also covers the caption and
-- the fire time, because that is what a *reviewer* approved. A creator consents to a preview of accounts,
-- options and media, so moving a Post's schedule must not silently withdraw their consent.
CREATE TABLE publish_consent (
    id           VARCHAR(36) PRIMARY KEY,
    work_item_id VARCHAR(36) NOT NULL REFERENCES work_items(id) ON DELETE CASCADE,
    -- Hex SHA-256 of the target set + uploaded asset set the creator consented to. NOT NULL: a consent
    -- that does not say what it covers is the boolean this table exists to avoid.
    consent_hash VARCHAR(64) NOT NULL,
    -- Who consented. No ON DELETE CASCADE, matching created_by elsewhere: the consent record must not
    -- quietly disappear because an account was removed.
    consented_by VARCHAR(36) NOT NULL REFERENCES users(id),
    consented_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_publish_consent_work_item UNIQUE (work_item_id)
);
