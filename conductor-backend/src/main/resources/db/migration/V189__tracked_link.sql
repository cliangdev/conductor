-- Tracked links: App Store campaign links (pt provider token + ct campaign token) that tell Apple which Post, bio,
-- creator or email a first-time download came from. Additive only.
--
-- A link's token is the `ct` of the URL and the dimension key `ct:{token}` of the STORE `campaign_downloads` metric,
-- which is how its downloads are read back (see TrackedLinkService). Apple omits a token's rows below its privacy
-- threshold (about 5 downloads), so tokens roll up by `family`: a family's downloads are the sum over its tokens.
--
--   kind          what the link is for: POST | BIO | CREATOR | EMAIL | OTHER. One kind per family (the application
--                 enforces it); the family `posts` holds the automatic per-Post links.
--   url           the link as it was handed out, built from the App Store Connect connection's appId and
--                 providerToken at creation time. Not rebuilt later, so a changed provider token never rewrites it.
--   work_item_id  the Post (or creator) the link belongs to. Deleting the Work Item keeps the link, because the
--                 downloads it brought were real: the column is nulled.
--
-- A Post has at most one link (the partial unique index), and a token is unique within a workspace.

CREATE TABLE tracked_link (
    id           VARCHAR(36) PRIMARY KEY,
    project_id   VARCHAR(36) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    kind         VARCHAR(16) NOT NULL,
    family       VARCHAR(64) NOT NULL,
    token        VARCHAR(40) NOT NULL,
    url          TEXT NOT NULL,
    work_item_id VARCHAR(36) REFERENCES work_items(id) ON DELETE SET NULL,
    label        VARCHAR(200),
    created_by   VARCHAR(36),
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_tracked_link_token UNIQUE (project_id, token),
    CONSTRAINT ck_tracked_link_kind CHECK (kind IN ('POST', 'BIO', 'CREATOR', 'EMAIL', 'OTHER'))
);

CREATE UNIQUE INDEX uq_tracked_link_post ON tracked_link (work_item_id) WHERE kind = 'POST';

CREATE INDEX idx_tracked_link_project_family ON tracked_link (project_id, family);
CREATE INDEX idx_tracked_link_work_item ON tracked_link (work_item_id) WHERE work_item_id IS NOT NULL;

COMMENT ON TABLE tracked_link IS
    'App Store campaign links (pt/ct) per Post, bio, creator or email; downloads are read from STORE campaign_downloads by token.';
